import { Component, OnDestroy, OnInit } from "@angular/core";
import { Subscription, interval, startWith, switchMap } from "rxjs";
import { ModelApiService } from "./services/model-api.service";
import { ToastService } from "src/app/core/service/toast.service";
import { ConfirmService } from "src/app/core/service/confirm.service";

interface MapRow {
  prop: string;
  column: string;
  /** node only: is this property part of the MERGE key? (composite keys allowed) */
  isKey?: boolean;
}

/**
 * Model & Pipelines screen.
 *
 * Shows EVERY node and relationship of the static model (schema.json), joined
 * with its editable pipeline slot (pipelines.json), so nothing is silently
 * missing. Each slot exposes:
 *   select  - the Oracle SELECT that produces its rows (placeholder until filled)
 *   key     - the graph identity property/ies
 *   map     - graph property -> row column
 * Relationship slots additionally carry a from/to endpoint key map.
 *
 * Saving PUTs the slot back to the backend, which persists pipelines.json.
 */
@Component({
  selector: "app-model",
  template: `
    <div class="md">
      <div class="md-head">
        <div>
          <h4>Model &amp; Pipelines</h4>
          <div class="md-sub">
            Static model (schema.json) + editable mapping (pipelines.json) - nothing hardcoded in code
          </div>
        </div>
        <div class="md-tools">
          <span class="pill">rows / run: {{ rowsPerRun || "…" }}</span>
          <a class="btn" routerLink="/config">Settings</a>
          <span class="pill live" *ngIf="anyRunning">
            syncing… <a routerLink="/activity">watch</a>
          </span>
          <span class="pill" [class.ok]="coverage && isComplete()" [class.bad]="coverage && !isComplete()">
            {{ coverageText() }}
          </span>
          <span class="pill del" *ngIf="deletedText()" [title]="deletedTitle()">{{ deletedText() }}</span>
          <button class="btn" (click)="reload()" [disabled]="busy">Reload files</button>
        </div>
      </div>

      <div class="md-msg" *ngIf="message" [class.err]="isError">{{ message }}</div>

      <div class="md-tabs">
        <button [class.active]="tab === 'nodes'" (click)="tab = 'nodes'">
          Nodes ({{ nodes.length }})
        </button>
        <button [class.active]="tab === 'relationships'" (click)="tab = 'relationships'">
          Relationships ({{ rels.length }})
        </button>
        <button class="new" *ngIf="tab === 'nodes'" (click)="newNode()">+ New node</button>
        <button class="new" *ngIf="tab === 'relationships'" (click)="newRel()">+ New relationship</button>
        <label class="del-toggle" title="Show entries with del_flag = Y (soft-deleted, kept for audit)">
          <input type="checkbox" [(ngModel)]="showDeleted" (ngModelChange)="load()" /> show deleted
        </label>
        <input class="md-filter" placeholder="filter..." [(ngModel)]="filter" />
      </div>

      <div class="run-help">
        <b>Run</b> pulls the next {{ rowsPerRun || "?" }} rows and advances the cursor; <b>All</b> loops
        to the end; <b>&#8635;</b> rewinds to 0. Success and failures are both recorded on the
        <a routerLink="/audit">Audit</a> page. User-defined nodes/relationships live in
        <code>extensions.json</code>, so refreshing the shipped model never removes them.
      </div>

      <div class="md-grid">
        <!-- list -->
        <div class="md-list">
          <ng-container *ngIf="tab === 'nodes'">
            <div class="row" *ngFor="let n of filteredNodes()"
                 [class.sel]="editing && editingKind === 'node' && editing.id === n.id"
                 [class.isdel]="n.delFlag === 'Y'"
                 (click)="editNode(n)">
              <div class="row-main">
                <span class="dot" [class.on]="n.enabled"></span>
                <div class="row-text">
                  <div class="row-title">
                    <span class="row-label">{{ n.label }}</span>
                    <span class="row-id">{{ n.id }}</span>
                  </div>
                  <div class="row-desc" *ngIf="n.description">{{ n.description }}</div>
                </div>
              </div>
              <div class="row-right">
                <span class="rr" *ngIf="chip('node', n.id)" [class.err]="chipErr('node', n.id)">{{ chip('node', n.id) }}</span>
                <span class="tag custom" *ngIf="n.custom">custom</span>
                <span class="tag del" *ngIf="n.delFlag === 'Y'">deleted</span>
                <span class="tag" *ngIf="n.delFlag !== 'Y'">{{ n.layer }}</span>
                <ng-container *ngIf="n.delFlag !== 'Y'">
                  <button class="mini" title="reset cursor" (click)="resetNode(n); $event.stopPropagation()" [disabled]="activeSlots['node:' + n.id]">&#8635;</button>
                  <button class="run all" title="run to completion" (click)="runNodeAll(n); $event.stopPropagation()" [disabled]="activeSlots['node:' + n.id]">All</button>
                  <button class="run" (click)="runNode(n); $event.stopPropagation()" [disabled]="activeSlots['node:' + n.id]">
                    {{ activeSlots['node:' + n.id] ? "…" : "Run" }}
                  </button>
                </ng-container>
                <button class="restore" *ngIf="n.delFlag === 'Y'"
                        (click)="restoreNode(n); $event.stopPropagation()">Restore</button>
              </div>
            </div>
          </ng-container>

          <ng-container *ngIf="tab === 'relationships'">
            <div class="row" *ngFor="let r of filteredRels()"
                 [class.sel]="editing && editingKind === 'rel' && editing.id === r.id"
                 [class.isdel]="r.delFlag === 'Y'"
                 (click)="editRel(r)">
              <div class="row-main">
                <span class="dot" [class.on]="r.enabled"></span>
                <div class="row-text">
                  <div class="row-title">
                    <span class="row-label">{{ r.type }}</span>
                    <span class="row-id">{{ r.id }}</span>
                  </div>
                  <div class="row-desc">
                    {{ r.from?.label }} &rarr; {{ r.to?.label }}<span *ngIf="r.description"> — {{ r.description }}</span>
                  </div>
                </div>
              </div>
              <div class="row-right">
                <span class="rr" *ngIf="chip('rel', r.id)" [class.err]="chipErr('rel', r.id)">{{ chip('rel', r.id) }}</span>
                <span class="tag custom" *ngIf="r.custom">custom</span>
                <span class="tag del" *ngIf="r.delFlag === 'Y'">deleted</span>
                <span class="tag" *ngIf="r.delFlag !== 'Y'">{{ r.layer }}</span>
                <ng-container *ngIf="r.delFlag !== 'Y'">
                  <button class="mini" title="reset cursor" (click)="resetRel(r); $event.stopPropagation()" [disabled]="activeSlots['rel:' + r.id]">&#8635;</button>
                  <button class="run all" title="run to completion" (click)="runRelAll(r); $event.stopPropagation()" [disabled]="activeSlots['rel:' + r.id]">All</button>
                  <button class="run" (click)="runRel(r); $event.stopPropagation()" [disabled]="activeSlots['rel:' + r.id]">
                    {{ activeSlots['rel:' + r.id] ? "…" : "Run" }}
                  </button>
                </ng-container>
                <button class="restore" *ngIf="r.delFlag === 'Y'"
                        (click)="restoreRel(r); $event.stopPropagation()">Restore</button>
              </div>
            </div>
          </ng-container>

          <div class="empty" *ngIf="(tab === 'nodes' && !filteredNodes().length) || (tab === 'relationships' && !filteredRels().length)">
            nothing matches "{{ filter }}"
          </div>
        </div>

        <!-- editor -->
        <div class="md-editor">
          <div class="editor-empty" *ngIf="!editing">
            Select a {{ tab === 'nodes' ? 'node' : 'relationship' }} to view or edit its mapping.
          </div>

          <ng-container *ngIf="editing">
            <div class="md-msg err" *ngIf="editing.delFlag === 'Y'">
              This entry is soft-deleted (del_flag = Y): hidden from syncs and the lists, kept in
              <code>pipelines.json</code> or <code>extensions.json</code> for audit. Graph data is
              untouched. Restore it to edit again, or detach delete its data below.
            </div>

            <div class="editor-title">
              <input class="title-input" *ngIf="creating && editingKind === 'node'" [(ngModel)]="editing.label"
                     placeholder="Node label, e.g. ComplianceReview" />
              <input class="title-input" *ngIf="creating && editingKind === 'rel'" [(ngModel)]="editing.type"
                     placeholder="Relationship type, e.g. REVIEWS" />
              <strong *ngIf="!creating">{{ editingKind === 'node' ? editing.label : editing.type }}</strong>
              <span class="tag custom" *ngIf="editing.custom">custom</span>
              <span class="row-id" *ngIf="editingKind === 'rel' && !creating">
                {{ editing.from?.label }} &rarr; {{ editing.to?.label }}
              </span>
              <label class="sw" *ngIf="!creating">
                <input type="checkbox" [(ngModel)]="editing.enabled" /> enabled
              </label>
            </div>

            <label>Description</label>
            <input [(ngModel)]="editing.description"
                   placeholder="what this represents, e.g. Customer accounts" />

            <label>Oracle SELECT</label>
            <textarea rows="8" [(ngModel)]="editing.select" spellcheck="false"></textarea>
            <div class="hint">
              No bind placeholders - paging is automatic (each Run pulls the next window).
              Placeholders start with <code>-- PLACEHOLDER</code> - replace them with real Finacle SQL.
              Save re-reads the query's columns and rebuilds the property rows below.
            </div>

            <!-- relationship endpoints: user-defined ones choose from the known nodes -->
            <ng-container *ngIf="editingKind === 'rel'">
              <label>From endpoint</label>
              <select *ngIf="editing.custom" [(ngModel)]="editing.from.label" (ngModelChange)="onEndpoint('from')">
                <option value="">— choose a node —</option>
                <option *ngFor="let n of nodeChoices()" [value]="n.label">
                  {{ n.label }}{{ n.description ? " — " + n.description : "" }}
                </option>
              </select>
              <div class="endpoint" *ngIf="!editing.custom">{{ editing.from?.label }}</div>

              <label>To endpoint</label>
              <select *ngIf="editing.custom" [(ngModel)]="editing.to.label" (ngModelChange)="onEndpoint('to')">
                <option value="">— choose a node —</option>
                <option *ngFor="let n of nodeChoices()" [value]="n.label">
                  {{ n.label }}{{ n.description ? " — " + n.description : "" }}
                </option>
              </select>
              <div class="endpoint" *ngIf="!editing.custom">{{ editing.to?.label }}</div>
              <div class="hint" *ngIf="editing.custom">
                Choose an existing node (shipped or your own). Changing an endpoint affects what
                <em>future</em> runs create; edges already in Neo4j are not moved.
              </div>
            </ng-container>

            <!-- node: map rows, each can be marked as part of the key -->
            <ng-container *ngIf="editingKind === 'node'">
              <label>Property map (graph property &rarr; row column)</label>
              <div class="hint">
                Tick <b>key</b> on the columns that identify the node (the MERGE key). Leave
                unticked for plain properties - they may be null. A key column that is null
                causes its row to be skipped, so keys must be NOT NULL in the source.
              </div>
              <div class="maprow" *ngFor="let row of mapRows; let i = index">
                <label class="keychk" title="part of the MERGE key">
                  <input type="checkbox" [(ngModel)]="row.isKey" /> key
                </label>
                <input [(ngModel)]="row.prop" placeholder="graph property" />
                <span class="arrow">&rarr;</span>
                <input [(ngModel)]="row.column" placeholder="row column" />
                <button class="x" (click)="removeRow('map', i)">&times;</button>
              </div>
              <button class="add" (click)="addRow('map')">+ add property</button>
              <div class="hint" *ngIf="!keyProps().length">
                No key ticked yet - a node needs at least one key column to be MERGEd.
              </div>
            </ng-container>

            <!-- relationship: endpoint keys + rel props -->
            <ng-container *ngIf="editingKind === 'rel'">
              <label>From "{{ editing.from?.label }}" key (graph property &rarr; row column)</label>
              <div class="maprow" *ngFor="let row of fromRows; let i = index">
                <input [(ngModel)]="row.prop" placeholder="graph property" />
                <span class="arrow">&rarr;</span>
                <input [(ngModel)]="row.column" placeholder="row column" />
                <button class="x" (click)="removeRow('from', i)">&times;</button>
              </div>
              <button class="add" (click)="addRow('from')">+ add from key</button>

              <label>To "{{ editing.to?.label }}" key (graph property &rarr; row column)</label>
              <div class="maprow" *ngFor="let row of toRows; let i = index">
                <input [(ngModel)]="row.prop" placeholder="graph property" />
                <span class="arrow">&rarr;</span>
                <input [(ngModel)]="row.column" placeholder="row column" />
                <button class="x" (click)="removeRow('to', i)">&times;</button>
              </div>
              <button class="add" (click)="addRow('to')">+ add to key</button>

              <label>Relationship properties (graph property &rarr; row column)</label>
              <div class="maprow" *ngFor="let row of mapRows; let i = index">
                <input [(ngModel)]="row.prop" placeholder="graph property" />
                <span class="arrow">&rarr;</span>
                <input [(ngModel)]="row.column" placeholder="row column" />
                <button class="x" (click)="removeRow('map', i)">&times;</button>
              </div>
              <button class="add" (click)="addRow('map')">+ add relationship property</button>
            </ng-container>

            <label>Notes</label>
            <input [(ngModel)]="editing.notes" placeholder="free text" />

            <label>Cypher (MERGE) - what actually runs against Neo4j</label>
            <textarea rows="8" [(ngModel)]="editing.cypher" spellcheck="false"
                      placeholder="Leave empty to auto-generate from the key ticks and property map above."></textarea>
            <div class="hint">
              Empty = auto-generated from the key ticks and the property map above. Type here to override
              it verbatim - the override is saved with the slot and used on every Run.
              <span *ngIf="editingKind === 'node' && !keyProps().length" class="warn">
                No key ticked, so nothing can be auto-generated.
              </span>
            </div>
            <div class="subbtns">
              <button class="btn" (click)="regenerateCypher()" [disabled]="busy">Regenerate from map</button>
              <button class="btn" (click)="clearCypher()">Use auto-generated</button>
            </div>

            <div class="editor-btns">
              <ng-container *ngIf="editing.delFlag !== 'Y'">
                <button class="btn primary" (click)="save()" [disabled]="busy || activeSlots[editingKind + ':' + editing.id]">
                  {{ creating ? "Create" : "Save" }}
                </button>
                <button class="btn run" *ngIf="!creating" (click)="runEditing()" [disabled]="activeSlots[editingKind + ':' + editing.id]">
                  {{ activeSlots[editingKind + ':' + editing.id] ? "Running…" : "Run (next window)" }}
                </button>
                <button class="btn run all" *ngIf="!creating" (click)="runEditingAll()" [disabled]="activeSlots[editingKind + ':' + editing.id]">
                  Run all
                </button>
                <button class="btn" *ngIf="!creating" (click)="resetEditing()" [disabled]="activeSlots[editingKind + ':' + editing.id]">
                  &#8635; Reset
                </button>
              </ng-container>
              <button class="btn" (click)="closeEditor()">Close</button>
            </div>

            <!-- Two SEPARATE destructive actions: the mapping and the data. -->
            <div class="editor-danger" *ngIf="!creating">
              <span class="dz">Delete</span>
              <button class="btn danger" *ngIf="editing.delFlag !== 'Y'" (click)="deleteEditing()" [disabled]="busy">
                Delete mapping
              </button>
              <button class="btn" *ngIf="editing.delFlag === 'Y'" (click)="restoreEditing()" [disabled]="busy">
                Restore mapping
              </button>
              <button class="btn danger" (click)="detachDeleteEditing()"
                      [disabled]="busy || activeSlots[editingKind + ':' + editing.id]">
                Detach delete data
              </button>
              <span class="dz-hint">
                <b>Delete mapping</b> hides the slot and keeps the data. <b>Detach delete data</b>
                removes nodes/edges from Neo4j and keeps the mapping.
              </span>
            </div>
            <div class="hint" *ngIf="activeSlots[editingKind + ':' + editing.id]">
              A sync job is running for this slot - Save/Run/Reg are paused for it until it finishes.
              Other slots and the rest of the page stay usable.
            </div>
            <div class="md-msg" *ngIf="mapNote">{{ mapNote }}</div>
          </ng-container>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .md { display: flex; flex-direction: column; gap: 12px; }
      .md-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
      .md-head h4 { margin: 0; }
      .md-sub { font-size: 12px; color: #6b7280; }
      .md-tools { display: flex; align-items: center; gap: 8px; }
      .pill { font-size: 12px; padding: 4px 10px; border-radius: 999px; background: #f3f4f6; color: #6b7280; border: 1px solid #e5e7eb; }
      .pill.live { background: #2563eb; color: #fff; border-color: #2563eb; }
      .pill.live a { color: #fff; text-decoration: underline; }
      .pill.ok { background: #f0fdf4; color: #15803d; border-color: #bbf7d0; }
      .pill.bad { background: #fef2f2; color: #b91c1c; border-color: #fecaca; }
      .pill.del { background: #fffbeb; color: #92400e; border-color: #fde68a; }
      .btn { border: 1px solid #d1d5db; background: #fff; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; }
      .btn.primary { background: #2563eb; color: #fff; border-color: #2563eb; }
      .btn:disabled { opacity: 0.5; cursor: default; }
      .md-msg { font-size: 12px; color: #15803d; background: #f0fdf4; border: 1px solid #bbf7d0; padding: 8px; border-radius: 6px; }
      .md-msg.err { color: #b91c1c; background: #fef2f2; border-color: #fecaca; }
      .md-tabs { display: flex; gap: 6px; align-items: center; }
      .md-tabs button { border: 1px solid #e5e7eb; background: #fff; border-radius: 6px; padding: 6px 14px; font-size: 13px; cursor: pointer; }
      .md-tabs button.active { background: #111827; color: #fff; border-color: #111827; }
      .md-tabs button.new { border-style: dashed; color: #2563eb; }
      .del-toggle {
        font-size: 11px;
        color: #6b7280;
        display: inline-flex;
        align-items: center;
        gap: 4px;
        margin: 0 0 0 auto;
        cursor: pointer;
      }
      .del-toggle input { width: auto; }
      .md-filter { margin-left: 0; width: 200px; padding: 6px 9px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
      .run-help { font-size: 12px; color: #4b5563; background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px 12px; }
      .run-help a { color: #2563eb; }
      .md-grid { display: grid; grid-template-columns: 380px 1fr; gap: 14px; align-items: start; }
      @media (max-width: 992px) { .md-grid { grid-template-columns: 1fr; } }
      .md-list { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; overflow: auto; max-height: 70vh; }
      .row { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; border-bottom: 1px solid #f3f4f6; cursor: pointer; }
      .row:hover { background: #f9fafb; }
      .row.sel { background: #eff6ff; }
      .row-main { display: flex; align-items: center; gap: 8px; min-width: 0; }
      .row-text { min-width: 0; }
      .row-title { display: flex; align-items: baseline; gap: 6px; }
      .row-desc {
        font-size: 11px;
        color: #6b7280;
        max-width: 230px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .row-right { display: flex; align-items: center; gap: 8px; flex: none; }
      .row.isdel { opacity: 0.55; }
      .rr { font-size: 10px; border-radius: 4px; padding: 2px 6px; background: #eff6ff; color: #1d4ed8; }
      .rr.err { background: #fef2f2; color: #b91c1c; }
      .run { border: 1px solid #2563eb; background: #2563eb; color: #fff; border-radius: 6px; padding: 3px 10px; font-size: 11px; cursor: pointer; }
      .run:disabled { opacity: 0.5; cursor: default; }
      .run.all { background: #fff; color: #2563eb; }
      .mini { border: 1px solid #d1d5db; background: #fff; color: #6b7280; border-radius: 6px; padding: 3px 8px; font-size: 12px; cursor: pointer; }
      .mini:disabled { opacity: 0.5; cursor: default; }
      .md-tools a.btn { text-decoration: none; line-height: 1.6; }
      .dot { width: 8px; height: 8px; border-radius: 50%; background: #d1d5db; flex: none; }
      .dot.on { background: #16a34a; }
      .row-label { font-size: 13px; font-weight: 600; }
      .row-id { font-size: 11px; color: #9ca3af; }
      .tag { font-size: 10px; text-transform: uppercase; color: #6b7280; background: #f3f4f6; border-radius: 4px; padding: 2px 6px; }
      .tag.custom { background: #eef2ff; color: #4338ca; }
      .tag.del { background: #fef2f2; color: #b91c1c; }
      .empty { padding: 16px; color: #9ca3af; font-size: 12px; }
      .md-editor { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; padding: 14px; }
      .editor-empty { color: #9ca3af; font-size: 13px; padding: 24px 4px; }
      .editor-title { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
      .sw { margin-left: auto; font-size: 12px; color: #6b7280; display: flex; align-items: center; gap: 4px; }
      label { display: block; font-size: 12px; color: #6b7280; margin-top: 10px; margin-bottom: 4px; }
      textarea, input { width: 100%; padding: 7px 9px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; box-sizing: border-box; }
      textarea { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
      select { width: 100%; padding: 7px 9px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; box-sizing: border-box; background: #fff; }
      .endpoint { font-size: 13px; padding: 7px 9px; border: 1px solid #e5e7eb; border-radius: 6px; background: #f9fafb; }
      .title-input { max-width: 380px; font-weight: 600; }
      .maprow { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
      .maprow input { flex: 1; }
      .maprow input[type="checkbox"] { flex: 0 0 auto; }
      .keychk {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        flex: 0 0 auto;
        font-size: 11px;
        font-weight: 600;
        color: #2563eb;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        margin: 0;
        cursor: pointer;
        user-select: none;
      }
      .arrow { color: #9ca3af; }
      .x { border: none; background: transparent; color: #b91c1c; cursor: pointer; font-size: 16px; line-height: 1; }
      .add { margin-top: 4px; border: 1px dashed #d1d5db; background: #fff; border-radius: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer; color: #2563eb; }
      .subbtns { display: flex; gap: 8px; margin-top: 6px; }
      .warn { color: #b45309; }
      .btn.danger { color: #b91c1c; border-color: #fecaca; background: #fff5f5; }
      .restore { border: 1px solid #16a34a; background: #fff; color: #15803d; border-radius: 6px; padding: 3px 10px; font-size: 11px; cursor: pointer; }
      .editor-btns { display: flex; gap: 8px; margin-top: 14px; }
      .editor-danger {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
        margin-top: 10px;
        padding-top: 10px;
        border-top: 1px dashed #e5e7eb;
      }
      .dz { font-size: 10px; text-transform: uppercase; letter-spacing: 0.05em; color: #b91c1c; font-weight: 700; }
      .dz-hint { flex: 1 1 100%; font-size: 11px; color: #9ca3af; }
      .hint { font-size: 11px; color: #9ca3af; margin-top: 6px; }
      code { background: #f3f4f6; padding: 1px 4px; border-radius: 3px; }
      details { margin-top: 10px; }
      summary { font-size: 12px; color: #6b7280; cursor: pointer; }
      pre { font-size: 11px; background: #f9fafb; padding: 8px; border-radius: 6px; overflow: auto; max-height: 200px; }
    `,
  ],
})
export class ModelComponent implements OnInit, OnDestroy {
  tab: "nodes" | "relationships" = "nodes";
  nodes: any[] = [];
  rels: any[] = [];
  coverage: any = null;
  filter = "";
  message = "";
  isError = false;
  busy = false;
  cypher = "";
  results: { [key: string]: any } = {};
  state: { [slot: string]: { count: number; done: boolean } } = {};
  /** slot -> true while a background job for that slot is actually RUNNING (polled). */
  activeSlots: { [slot: string]: boolean } = {};
  anyRunning = false;
  private announced: { [jobId: string]: boolean } = {};
  private jobsSub?: Subscription;
  private watchdog?: any;
  rowsPerRun = 0;

  /** Show soft-deleted (delFlag = Y) user-defined entries. */
  showDeleted = false;
  /** True while the editor holds a brand-new user-defined slot (POST, not PUT). */
  creating = false;

  editing: any = null;
  editingKind: "node" | "rel" | null = null;
  mapRows: MapRow[] = [];
  fromRows: MapRow[] = [];
  toRows: MapRow[] = [];
  /** What the last Save changed in the property map (dropped/added columns). */
  mapNote = "";

  constructor(private api: ModelApiService, private toast: ToastService, private confirmSvc: ConfirmService) {}

  ngOnInit(): void {
    this.load();
    this.pollJobs();
    // Safety net: never let a stuck busy flag permanently grey out the page.
    this.watchdog = setInterval(() => {
      this.busy = false;
    }, 4000);
  }

  ngOnDestroy(): void {
    this.jobsSub?.unsubscribe();
    if (this.watchdog) clearInterval(this.watchdog);
  }

  /** Poll background jobs so row buttons reflect REAL server-side activity. */
  private pollJobs(): void {
    this.jobsSub = interval(1500)
      .pipe(startWith(0), switchMap(() => this.api.jobs()))
      .subscribe({
        next: (jobs) => {
          const next: { [slot: string]: boolean } = {};
          for (const j of jobs || []) {
            if (j.status === "RUNNING") {
              next[j.slot] = true;
            } else {
              this.announceJob(j);
            }
          }
          this.activeSlots = next;
          this.anyRunning = Object.keys(next).length > 0;
        },
        error: () => {},
      });
  }

  /** Fire a completion popup ONCE per finished job, with the right condition. */
  private announceJob(j: any): void {
    if (!j.id || j.status === "INTERRUPTED" || this.announced[j.id]) return;
    this.announced[j.id] = true;

    if (j.action === "detach-delete") {
      if (j.status === "DONE") {
        this.toast.show(
          "success",
          "Data deleted",
          `${j.target}: ${Number(j.deletedNodes) || 0} node(s) and ` +
            `${Number(j.deletedRelationships) || 0} relationship(s) removed. The mapping is kept.`
        );
      } else if (j.status === "FAILED") {
        this.toast.show("error", "Detach delete failed", `${j.target}: ${j.error || "see the Audit page."}`);
      }
      this.load();
      return;
    }

    const label = `${j.action === "run-all" ? "Run all" : "Run"} ${j.slot}`;
    if (j.status === "DONE") {
      const read = Number(j.selected) || 0;
      const newNodes = Number(j.created) || 0;
      if (read && !newNodes) {
        // Rows were re-read and MERGE matched what is already in the graph.
        this.toast.show(
          "empty",
          "All records already added",
          `${label}: ${read} rows re-checked, 0 new — everything was already in the graph (MERGE update only).`
        );
      } else if (read) {
        this.toast.show(
          "success",
          "Sync finished",
          `${label}: ${read} rows read, ${newNodes} new, ${Math.max(0, read - newNodes)} already present (${j.ms} ms).`
        );
      } else {
        this.toast.show("empty", "Nothing to sync", `${label}: no rows left — this slot is fully synced.`);
      }
      this.loadState();
    } else if (j.status === "FAILED") {
      this.toast.show("error", "Sync failed", `${label}: ${j.error || "see the Audit page for the SQL and the cause."}`);
    } else if (j.status === "CANCELLED") {
      this.toast.show("info", "Sync cancelled", `${label} was cancelled; rows already pushed are kept.`);
    }
  }

  load(): void {
    this.api.coverage().subscribe({
      next: (c) => (this.coverage = c),
      error: () => (this.coverage = null),
    });
    this.api.nodes(this.showDeleted).subscribe({
      next: (n) => (this.nodes = n),
      error: (e) => this.fail(e),
    });
    this.api.relationships(this.showDeleted).subscribe({
      next: (r) => (this.rels = r),
      error: (e) => this.fail(e),
    });
    this.api.config().subscribe({
      next: (c) => (this.rowsPerRun = c && c.rowsPerRun ? c.rowsPerRun : 0),
      error: () => {},
    });
    this.loadState();
  }

  filteredNodes(): any[] {
    const f = this.filter.trim().toLowerCase();
    if (!f) return this.nodes;
    return this.nodes.filter(
      (n) => (n.label || "").toLowerCase().includes(f) || (n.id || "").toLowerCase().includes(f)
    );
  }

  filteredRels(): any[] {
    const f = this.filter.trim().toLowerCase();
    if (!f) return this.rels;
    return this.rels.filter(
      (r) =>
        (r.type || "").toLowerCase().includes(f) ||
        (r.id || "").toLowerCase().includes(f) ||
        ((r.from && r.from.label) || "").toLowerCase().includes(f) ||
        ((r.to && r.to.label) || "").toLowerCase().includes(f)
    );
  }

  isComplete(): boolean {
    return !!this.coverage && !this.coverage.missingNodes?.length && !this.coverage.missingRelationships?.length;
  }

  coverageText(): string {
    if (!this.coverage) return "coverage: ?";
    if (this.isComplete()) {
      return `coverage: complete (${this.coverage.schemaNodes} nodes / ${this.coverage.schemaRelationships} rels)`;
    }
    return `coverage: ${this.coverage.missingNodes.length} nodes + ${this.coverage.missingRelationships.length} rels missing`;
  }

  /** Soft-deleted shipped slots: still covered, but deliberately switched off. */
  deletedText(): string {
    const n = (this.coverage?.deletedNodes || 0) + (this.coverage?.deletedRelationships || 0);
    return n ? `${n} deleted` : "";
  }

  deletedTitle(): string {
    return `${this.coverage?.deletedNodes || 0} node + ${this.coverage?.deletedRelationships || 0} relationship `
      + `mapping(s) soft-deleted. They still count as covered; their data is untouched.`;
  }

  editNode(n: any): void {
    this.creating = false;
    this.editingKind = "node";
    this.editing = JSON.parse(JSON.stringify(n));
    this.mapRows = toRows(this.editing.map, this.editing.key);
    this.fromRows = [];
    this.toRows = [];
    this.resetEditorMessages();
  }

  editRel(r: any): void {
    this.creating = false;
    this.editingKind = "rel";
    this.editing = JSON.parse(JSON.stringify(r));
    this.mapRows = toRows(this.editing.map, this.editing.key);
    this.fromRows = toRows(this.editing.from && this.editing.from.key);
    this.toRows = toRows(this.editing.to && this.editing.to.key);
    this.resetEditorMessages();
  }

  /** Open a blank user-defined node. It starts as a template - fill the SELECT and Save. */
  newNode(): void {
    this.tab = "nodes";
    this.editingKind = "node";
    this.creating = true;
    this.editing = {
      id: "",
      label: "",
      layer: "usecase",
      custom: true,
      description: "",
      enabled: true,
      key: [],
      map: {},
      select: NODE_TEMPLATE,
      notes: "",
      cypher: "",
      delFlag: "N",
    };
    this.mapRows = [];
    this.fromRows = [];
    this.toRows = [];
    this.resetEditorMessages();
  }

  /** Open a blank user-defined relationship; endpoints are chosen from existing nodes. */
  newRel(): void {
    this.tab = "relationships";
    this.editingKind = "rel";
    this.creating = true;
    this.editing = {
      id: "",
      type: "",
      layer: "compliance",
      custom: true,
      description: "",
      enabled: true,
      from: { label: "", key: {} },
      to: { label: "", key: {} },
      map: {},
      select: REL_TEMPLATE,
      notes: "",
      cypher: "",
      delFlag: "N",
    };
    this.mapRows = [];
    this.fromRows = [];
    this.toRows = [];
    this.resetEditorMessages();
  }

  /** Nodes an endpoint can point at (deleted ones are not offered). */
  nodeChoices(): any[] {
    return this.nodes.filter((n) => n.delFlag !== "Y");
  }

  /**
   * Prefill an endpoint's key rows from the chosen node's key properties.
   * The row column is the endpoint node's own column mapping where it exists,
   * so the common case (same table/aliases) needs no typing.
   */
  onEndpoint(side: "from" | "to"): void {
    const endpoint = side === "from" ? this.editing.from : this.editing.to;
    const node = this.nodes.find((n) => n.label === endpoint.label);
    const previous = side === "from" ? this.fromRows : this.toRows;
    const columns = new Map<string, string>();
    for (const r of previous) columns.set((r.prop || "").trim(), r.column);
    const rows: MapRow[] = ((node && node.key) || []).map((prop: string) => ({
      prop,
      column: columns.get(prop) || (node && node.map ? node.map[prop] || "" : ""),
    }));
    if (side === "from") this.fromRows = rows;
    else this.toRows = rows;
  }

  closeEditor(): void {
    this.editing = null;
    this.editingKind = null;
    this.creating = false;
  }

  /** The graph properties ticked as key (in row order); the node's MERGE key. */
  keyProps(): string[] {
    return this.mapRows.filter((r) => r.isKey && r.prop && r.prop.trim()).map((r) => r.prop.trim());
  }

  addRow(target: "map" | "from" | "to"): void {
    this.rowsFor(target).push({ prop: "", column: "" });
  }

  removeRow(target: "map" | "from" | "to", i: number): void {
    this.rowsFor(target).splice(i, 1);
  }

  /**
   * Save, but FIRST re-derive the property rows from the SELECT's real columns.
   * Rows whose column no longer exists are dropped (with their key tick); rows
   * for new columns are appended, pre-filled so only a key tick is needed.
   */
  save(): void {
    if (!this.editing) return;
    if (this.creating && !this.validateNew()) return;
    this.busy = true;
    // A brand-new slot starts from an angle-bracket template: there is nothing
    // to describe yet, and parsing placeholders would invent junk rows.
    if (this.creating && /<[^>]+>/.test(this.editing.select || "")) {
      this.persist("");
      return;
    }
    this.api.columns(this.editing.select || "").subscribe({
      next: (res) => {
        const cols: string[] = (res && res.columns) || [];
        // Drop stale rows only when the column list came from Oracle itself.
        // A text-parsed fallback may be wrong, so it may add but never remove.
        const trusted = res && res.source === "oracle";
        if (cols.length) this.reconcileRows(cols, trusted);
        const warning = res && res.warning ? res.warning : "";
        this.persist(warning);
      },
      error: (e) => {
        // Could not read the columns (Oracle down / bad SQL): save as-is rather
        // than throw away the mapping the user already built.
        this.mapNote = "Could not read the query columns (" + this.errText(e) + ") - saved the mapping as-is.";
        this.persist("");
      },
    });
  }

  /** A new entry needs an identity before it can be persisted. */
  private validateNew(): boolean {
    if (this.editingKind === "node") {
      if (!(this.editing.label || "").trim()) {
        this.failMsg("Give the node a label (e.g. ComplianceReview).");
        return false;
      }
    } else {
      if (!(this.editing.type || "").trim()) {
        this.failMsg("Give the relationship a type (e.g. REVIEWS).");
        return false;
      }
      if (!this.editing.from?.label || !this.editing.to?.label) {
        this.failMsg("Choose both a from and a to endpoint.");
        return false;
      }
    }
    return true;
  }

  private failMsg(msg: string): void {
    this.busy = false;
    this.isError = true;
    this.message = msg;
  }

  /**
   * Rebuild map/from/to rows against the query's column list.
   * Stale rows go only when the list is trusted (live Oracle describe); new
   * columns always arrive pre-filled (prop = column, not a key).
   */
  private reconcileRows(cols: string[], dropStale: boolean): void {
    const have = new Set(cols.map((c) => (c || "").trim().toLowerCase()));
    const dropped: string[] = [];
    const added: string[] = [];

    const rebuild = (rows: MapRow[], autoAdd: boolean): MapRow[] => {
      const kept: MapRow[] = [];
      for (const r of rows) {
        const col = (r.column || "").trim().toLowerCase();
        if (dropStale && col && !have.has(col)) {
          dropped.push(r.column);
          continue;
        }
        kept.push(r);
      }
      if (autoAdd) {
        const mapped = new Set(kept.map((r) => (r.column || "").trim().toLowerCase()));
        for (const c of cols) {
          const col = (c || "").trim();
          if (!col || mapped.has(col.toLowerCase())) continue;
          kept.push({ prop: col, column: col, isKey: false });
          added.push(col);
        }
      }
      return kept;
    };

    if (this.editingKind === "node") {
      this.mapRows = rebuild(this.mapRows, true);
      this.editing.key = this.keyProps();
    } else {
      // Endpoint keys only lose stale rows - we cannot guess which new column
      // belongs to which endpoint. Rel properties are auto-filled like nodes.
      this.fromRows = rebuild(this.fromRows, false);
      this.toRows = rebuild(this.toRows, false);
      this.mapRows = rebuild(this.mapRows, true);
    }

    this.mapNote = "";
    if (dropped.length) this.mapNote += `dropped ${dropped.length} stale: ${dropped.join(", ")}. `;
    if (added.length) this.mapNote += `added ${added.length} new: ${added.join(", ")}. `;
  }

  private persist(warning: string): void {
    const body = this.payload();
    const creating = this.creating;
    const obs = creating
      ? this.editingKind === "node"
        ? this.api.createNode(body)
        : this.api.createRelationship(body)
      : this.editingKind === "node"
        ? this.api.saveNode(body.id, body)
        : this.api.saveRelationship(body.id, body);
    obs.subscribe({
      next: () =>
        this.afterSave(
          creating
            ? this.editingKind === "node"
              ? "Node created from the template - replace its SELECT, then Save."
              : "Relationship created from the template - replace its SELECT, then Save."
            : this.editingKind === "node"
              ? "Node mapping saved."
              : "Relationship mapping saved."
        ),
      error: (e) => this.fail(e),
    });
    if (warning) this.mapNote = (this.mapNote || "") + warning;
  }

  /** The slot body the API expects: rows become maps, key ticks become the key list. */
  private payload(): any {
    const body = JSON.parse(JSON.stringify(this.editing));
    body.map = fromRows(this.mapRows);
    if (this.editingKind === "node") {
      body.key = this.keyProps();
    } else {
      body.from.key = fromRows(this.fromRows);
      body.to.key = fromRows(this.toRows);
    }
    return body;
  }

  /** Soft-delete the open slot's MAPPING (del_flag = Y; graph data is untouched). */
  async deleteEditing(): Promise<void> {
    if (!this.editing || !this.editingKind) return;
    const kind = this.editingKind;
    const id = this.editing.id;
    const name = this.nameOf(kind);
    const details: string[] = [];
    const cascaded = kind === "node" ? this.cascadedRels(name) : [];
    if (cascaded.length) {
      details.push(
        `${cascaded.length} relationship mapping(s) point at ${name} and will be hidden too: ` +
          cascaded.map((r) => `${r.type} (${r.id})`).join(", ") + "."
      );
      details.push("Each one can be restored individually afterwards.");
    }
    details.push("Graph data in Neo4j is NOT removed - use Detach delete data for that.");

    const ok = await this.confirmSvc.confirm({
      title: `Delete mapping: ${name}?`,
      message:
        `This sets del_flag = Y on ${kind === "node" ? "node" : "relationship"} ${id}. ` +
        "The slot leaves the model (no sync, no index) but stays on disk for audit.",
      details,
      confirmText: "Delete mapping",
      danger: true,
    });
    if (!ok) return;

    this.busy = true;
    const obs = kind === "node" ? this.api.deleteNode(id) : this.api.deleteRelationship(id);
    obs.subscribe({
      next: (res) => {
        this.busy = false;
        const hidden: string[] = (res && res.cascaded) || [];
        this.toast.show(
          "info",
          "Mapping deleted",
          `${name} hidden (del_flag = Y).` +
            (hidden.length ? ` Also hid ${hidden.length} relationship mapping(s): ${hidden.join(", ")}.` : "") +
            " Graph data is untouched."
        );
        this.message = `${name} mapping soft-deleted.`;
        this.isError = false;
        this.closeEditor();
        this.load();
      },
      error: (e) => this.fail(e),
    });
  }

  /**
   * Remove the open slot's GRAPH DATA, keeping the mapping.
   *
   * Counts are fetched first and shown in the dialog; the delete itself is a
   * background job on the sync worker, so a large label cannot freeze the page.
   */
  async detachDeleteEditing(): Promise<void> {
    if (!this.editing || !this.editingKind) return;
    const kind = this.editingKind;
    const id = this.editing.id;
    const name = this.nameOf(kind);
    const key = kind + ":" + id;
    if (this.activeSlots[key]) return;

    this.busy = true;
    const data$ = kind === "node" ? this.api.nodeData(id) : this.api.relationshipData(id);
    data$.subscribe({
      next: async (info) => {
        this.busy = false;
        const nodes = Number(info?.nodes) || 0;
        const rels = Number(info?.relationships) || 0;
        const details: string[] = [];
        if (kind === "node") {
          details.push(`${nodes.toLocaleString()} node(s) labelled ${name} will be removed.`);
          details.push(
            `${rels.toLocaleString()} relationship(s) touching them will be removed - ` +
              "including edges owned by other slots."
          );
        } else {
          details.push(
            `${rels.toLocaleString()} ${name} relationship(s) ` +
              `${info?.from} -> ${info?.to} will be removed.`
          );
          details.push("The same relationship type between other labels is untouched.");
        }
        details.push("This cannot be undone. The mapping is kept, so a later Run re-creates the data from Oracle.");

        if (!nodes && !rels) {
          this.toast.show("empty", "Nothing to delete", `${name} has no graph data right now.`);
          return;
        }

        const ok = await this.confirmSvc.confirm({
          title: `Detach delete data: ${name}?`,
          message: "PERMANENT: this removes graph data from Neo4j and cannot be undone.",
          details,
          confirmText: "Delete permanently",
          danger: true,
          requireTyped: name,
        });
        if (!ok) return;

        const obs =
          kind === "node" ? this.api.detachDeleteNode(id) : this.api.detachDeleteRelationship(id);
        obs.subscribe({
          next: (job) => {
            this.activeSlots[key] = true; // immediate guard; the poller confirms it
            this.isError = false;
            this.message =
              `Job ${job.id} started (detach-delete ${job.slot}) - it runs in the background, track it in Activity.`;
            this.toast.show(
              "running",
              "Detach delete started",
              `Job ${job.id} is removing ${name} from Neo4j. It cannot be cancelled once started.`,
              5000
            );
            this.loadState();
          },
          error: (e) => this.fail(e),
        });
      },
      error: (e) => this.fail(e),
    });
  }

  /** Active relationship slots whose endpoint is this node label (cascade preview). */
  private cascadedRels(label: string): any[] {
    return (this.rels || []).filter(
      (r) =>
        r.delFlag !== "Y" &&
        ((r.from && r.from.label === label) || (r.to && r.to.label === label))
    );
  }

  private nameOf(kind: "node" | "rel"): string {
    return kind === "node" ? this.editing.label : this.editing.type;
  }

  /** Bring the open soft-deleted slot back (it returns disabled, for review). */
  restoreEditing(): void {
    if (!this.editing || !this.editingKind) return;
    const obs =
      this.editingKind === "node"
        ? this.api.restoreNode(this.editing.id)
        : this.api.restoreRelationship(this.editing.id);
    obs.subscribe({
      next: (res) => {
        this.editing = JSON.parse(JSON.stringify(res));
        this.mapRows = toRows(this.editing.map, this.editing.key);
        if (this.editingKind === "rel") {
          this.fromRows = toRows(this.editing.from && this.editing.from.key);
          this.toRows = toRows(this.editing.to && this.editing.to.key);
        }
        this.isError = false;
        this.message = "Restored (disabled) - review the mapping, tick enabled, then Save.";
        this.load();
      },
      error: (e) => this.fail(e),
    });
  }

  /** Fill the Cypher box from the current ticks/map (mirrors the backend generator). */
  regenerateCypher(): void {
    if (!this.editing) return;
    this.editing.cypher =
      this.editingKind === "node" ? this.buildNodeCypher() : this.buildRelCypher();
  }

  clearCypher(): void {
    if (this.editing) this.editing.cypher = "";
  }

  private buildNodeCypher(): string {
    const label = this.editing.label;
    const keys = this.mapRows.filter((r) => r.isKey && r.prop && r.column);
    if (!label || !keys.length) return "";
    const guard = keys.map((r) => `r.${r.column} IS NOT NULL`).join(" AND ");
    const pat = keys.map((r) => `${r.prop}: toString(r.${r.column})`).join(", ");
    let out = `UNWIND $rows AS r\nWITH r WHERE ${guard}\nMERGE (n:${label} {${pat}})`;
    for (const r of this.mapRows) {
      if (r.isKey || !r.prop || !r.column) continue;
      out += `\nSET n.${r.prop} = r.${r.column}`;
    }
    return out;
  }

  private buildRelCypher(): string {
    const type = this.editing.type;
    const from = this.editing.from;
    const to = this.editing.to;
    if (!type || !from?.label || !to?.label) return "";
    const fp = this.patOf(this.fromRows);
    const tp = this.patOf(this.toRows);
    if (!fp || !tp) return "";
    let out = `UNWIND $rows AS r\nMATCH (a:${from.label} ${fp})\nMATCH (b:${to.label} ${tp})`;
    const props = this.mapRows.filter((r) => r.prop && r.column);
    out += props.length ? `\nMERGE (a)-[rel:${type}]->(b)` : `\nMERGE (a)-[:${type}]->(b)`;
    for (const r of props) out += `\nSET rel.${r.prop} = r.${r.column}`;
    return out;
  }

  private patOf(rows: MapRow[]): string {
    const keys = rows.filter((r) => r.prop && r.column);
    if (!keys.length) return "";
    return "{" + keys.map((r) => `${r.prop}: toString(r.${r.column})`).join(", ") + "}";
  }

  runNode(n: any): void {
    this.slice("node", n.id);
  }

  runNodeAll(n: any): void {
    this.all("node", n.id);
  }

  resetNode(n: any): void {
    this.resetSlot("node", n.id);
  }

  runRel(r: any): void {
    this.slice("rel", r.id);
  }

  runRelAll(r: any): void {
    this.all("rel", r.id);
  }

  resetRel(r: any): void {
    this.resetSlot("rel", r.id);
  }

  /** Restore a soft-deleted slot straight from the list. */
  restoreNode(n: any): void {
    this.restoreSlot("node", n.id);
  }

  restoreRel(r: any): void {
    this.restoreSlot("rel", r.id);
  }

  private restoreSlot(kind: "node" | "rel", id: string): void {
    const obs = kind === "node" ? this.api.restoreNode(id) : this.api.restoreRelationship(id);
    obs.subscribe({
      next: () => {
        this.isError = false;
        this.message = `${kind}:${id} restored (disabled) - enable it in the editor when ready.`;
        this.load();
      },
      error: (e) => this.fail(e),
    });
  }

  runEditing(): void {
    if (this.editing && this.editingKind) this.slice(this.editingKind, this.editing.id);
  }

  runEditingAll(): void {
    if (this.editing && this.editingKind) this.all(this.editingKind, this.editing.id);
  }

  resetEditing(): void {
    if (this.editing && this.editingKind) this.resetSlot(this.editingKind, this.editing.id);
  }

  private slice(kind: "node" | "rel", id: string): void {
    const key = kind + ":" + id;
    if (this.activeSlots[key]) return;
    delete this.results[key];
    const obs = kind === "node" ? this.api.runNode(id) : this.api.runRelationship(id);
    obs.subscribe({
      next: (res) => {
        this.results[key] = res;
        this.afterRun(res);
      },
      error: (e) => {
        this.results[key] = { error: true };
        this.toast.show("error", "Could not start sync", this.errText(e));
        this.fail(e);
      },
    });
  }

  /** Run a slot to completion (the server streams the whole SELECT in one pass). */
  private all(kind: "node" | "rel", id: string): void {
    const key = kind + ":" + id;
    if (this.activeSlots[key]) return;
    delete this.results[key];
    const obs = kind === "node" ? this.api.runNodeAll(id) : this.api.runRelationshipAll(id);
    obs.subscribe({
      next: (res) => {
        this.results[key] = { all: true, ...res };
        this.afterAll(res);
      },
      error: (e) => {
        this.results[key] = { error: true };
        this.toast.show("error", "Could not start sync", this.errText(e));
        this.fail(e);
      },
    });
  }

  private resetSlot(kind: "node" | "rel", id: string): void {
    const key = kind + ":" + id;
    const obs = kind === "node" ? this.api.resetNode(id) : this.api.resetRelationship(id);
    obs.subscribe({
      next: () => {
        delete this.results[key];
        this.isError = false;
        this.message = `cursor reset for ${key}`;
        this.loadState();
      },
      error: (e) => this.fail(e),
    });
  }

  private afterRun(res: any): void {
    this.isError = false;
    this.message = `Job ${res.id} started (${res.action} ${res.slot}) - running in the background, track it in Activity`;
    this.toast.show("running", "Sync started", `Job ${res.id} is pulling the next window for ${res.slot}.`, 4000);
    this.loadState();
  }

  private afterAll(res: any): void {
    this.isError = false;
    this.message = `Job ${res.id} started (${res.action} ${res.slot}) - running in the background, track it in Activity`;
    this.toast.show("running", "Full sync started", `Job ${res.id} is streaming ${res.slot} to the end.`, 4000);
    this.loadState();
  }

  chip(kind: "node" | "rel", id: string): string {
    const key = kind + ":" + id;
    const r = this.results[key];
    if (r) {
      if (r.error) return "error";
      if (r.status) return `${r.status === "DONE" ? "\u2713" : "…"}${(r.pushed || 0).toLocaleString()}`;
      if (r.all) return `\u2191${r.pushed}${r.done ? " \u2713" : ""}`;
      return `+${r.selected}${r.done ? " \u2713" : ""}`;
    }
    const s = this.state[key];
    if (s && (s.count || s.done)) return `${s.count}${s.done ? " \u2713" : ""}`;
    return "";
  }

  chipErr(kind: "node" | "rel", id: string): boolean {
    const r = this.results[kind + ":" + id];
    return !!(r && r.error);
  }

  private loadState(): void {
    this.api.syncState().subscribe({
      next: (s) => (this.state = s || {}),
      error: () => {},
    });
  }

  reload(): void {
    this.busy = true;
    this.api.reload().subscribe({
      next: () => {
        this.busy = false;
        this.message = "Reloaded schema.json + pipelines.json + extensions.json from disk.";
        this.isError = false;
        this.closeEditor();
        this.load();
      },
      error: (e) => this.fail(e),
    });
  }

  private rebuildList(): void {
    this.api.nodes(this.showDeleted).subscribe({ next: (n) => (this.nodes = n) });
    this.api.relationships(this.showDeleted).subscribe({ next: (r) => (this.rels = r) });
  }

  private afterSave(msg: string): void {
    this.busy = false;
    this.message = msg;
    this.isError = false;
    this.closeEditor();
    this.rebuildList();
    this.api.coverage().subscribe({ next: (c) => (this.coverage = c) });
  }

  private resetEditorMessages(): void {
    this.message = "";
    this.isError = false;
    this.cypher = "";
    this.mapNote = "";
  }

  private rowsFor(target: "map" | "from" | "to"): MapRow[] {
    if (target === "map") return this.mapRows;
    if (target === "from") return this.fromRows;
    return this.toRows;
  }

  private fail(e: any): void {
    this.busy = false;
    this.isError = true;
    this.message = this.errText(e);
  }

  /** Backend errors arrive as { error: "<Class>: <message>" }. */
  private errText(e: any): string {
    return (
      (e && e.error && e.error.error) ||
      (e && e.error && e.error.message) ||
      (e && e.message) ||
      "Request failed - is the backend running on :9700?"
    );
  }
}

function toRows(obj: any, keys?: string[]): MapRow[] {
  if (!obj) return [];
  const keyList = keys || [];
  return Object.keys(obj).map((prop) => ({ prop, column: obj[prop], isKey: keyList.indexOf(prop) >= 0 }));
}

function fromRows(rows: MapRow[]): { [k: string]: string } {
  const out: { [k: string]: string } = {};
  for (const r of rows) {
    const p = (r.prop || "").trim();
    const c = (r.column || "").trim();
    if (p && c) out[p] = c;
  }
  return out;
}

/** Starter SQL for a user-defined node: angle-bracket placeholders are refused by Run. */
const NODE_TEMPLATE = `-- TEMPLATE: replace with real SQL, then Save to rebuild the property map
SELECT <column> AS <property> FROM <owner>.<table>`;

/** Starter SQL for a user-defined relationship: it must carry both endpoint keys. */
const REL_TEMPLATE = `-- TEMPLATE: replace with the rows that carry both endpoint keys
SELECT <fromKey> AS <fromProperty>, <toKey> AS <toProperty> FROM <owner>.<table>`;

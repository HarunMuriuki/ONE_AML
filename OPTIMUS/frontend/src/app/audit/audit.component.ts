import { Component, OnInit } from "@angular/core";
import { ModelApiService } from "../model/services/model-api.service";

/**
 * Audit trail screen (audit.jsonl).
 *
 * Append-only record of EVERY sync action - one line per Run / Run all / Reset,
 * success or failure - so an AML reviewer can reconstruct what was pulled, when,
 * how much, and what went wrong. Also surfaces the exact SELECT/Cypher used.
 */
@Component({
  selector: "app-audit",
  template: `
    <div class="au">
      <div class="au-head">
        <div>
          <h4>Audit trail</h4>
          <div class="au-sub">
            Append-only log (audit.jsonl) of every sync action. Nothing is rewritten - each
            Run, Run all and Reset adds a line, whether it succeeded or failed.
          </div>
        </div>
        <button class="btn" (click)="load()" [disabled]="busy">Refresh</button>
      </div>

      <div class="au-msg" *ngIf="message" [class.err]="isError">{{ message }}</div>

      <details class="au-help" open>
        <summary>What am I looking at?</summary>
        <ul>
          <li><b>Run</b> - pulled the next keyset window of rows and advanced that slot's cursor.</li>
          <li><b>Run all</b> - streamed the whole slot in one pass (fast; no re-scanning).</li>
          <li><b>Reset</b> - rewound the slot's cursor so it starts over.</li>
        </ul>
        <ul>
          <li><b>selected</b> - rows read from Oracle this action.</li>
          <li><b>pushed</b> - rows sent to Neo4j (MERGE is idempotent - re-runs don't duplicate).</li>
          <li><b>count</b> - total rows this slot has synced so far.</li>
          <li><b>nextKey</b> - cursor position after this action; next Run seeks past it (only new rows).</li>
          <li><b>done</b> - the slot has been fully read.</li>
          <li><b>ms</b> - duration of the action.</li>
          <li><b>status</b> - OK, or FAILED with the reason in <b>error</b>.</li>
        </ul>
      </details>

      <div class="au-tools">
        <input class="au-filter" placeholder="filter by slot / action / status..." [(ngModel)]="filter" />
        <label class="au-chk">
          <input type="checkbox" [(ngModel)]="onlyFailures" /> failures only
        </label>
        <label class="au-chk">
          rows
          <select [(ngModel)]="limit" (ngModelChange)="load()">
            <option [ngValue]="100">100</option>
            <option [ngValue]="200">200</option>
            <option [ngValue]="500">500</option>
          </select>
        </label>
      </div>

      <div class="au-list">
        <div class="au-row au-row-head">
          <span>time</span>
          <span>slot</span>
          <span>action</span>
          <span class="num">count</span>
          <span class="num">selected</span>
          <span class="num">pushed</span>
          <span>done</span>
          <span class="num">ms</span>
          <span>status</span>
        </div>

        <ng-container *ngFor="let e of filtered()">
          <div class="au-row" [class.bad]="e.ok === false">
            <span class="ts">{{ e.ts | date: "short" }}</span>
            <span class="slot">{{ e.slot }}</span>
            <span>{{ e.action }}</span>
            <span class="num">{{ e.count ?? "" }}</span>
            <span class="num">{{ e.selected ?? "" }}</span>
            <span class="num">{{ e.pushed ?? "" }}</span>
            <span>{{ e.done === undefined ? "" : (e.done ? "yes" : "no") }}</span>
            <span class="num">{{ e.ms ?? "" }}</span>
            <span class="st" [class.fail]="e.ok === false">{{ e.ok === false ? "FAILED" : "OK" }}</span>
          </div>
          <div class="au-detail" *ngIf="e.ok === false && e.error">
            <span class="err-label">error</span> {{ e.error }}
          </div>
          <div class="au-detail" *ngIf="e.sql">
            <details>
              <summary>SQL / Cypher used</summary>
              <pre *ngIf="e.sql">SQL:
{{ e.sql }}</pre>
              <pre *ngIf="e.cypher">Cypher:
{{ e.cypher }}</pre>
            </details>
          </div>
        </ng-container>

        <div class="empty" *ngIf="!filtered().length">no audit entries yet</div>
      </div>
    </div>
  `,
  styles: [
    `
      .au { display: flex; flex-direction: column; gap: 12px; }
      .au-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; }
      .au-head h4 { margin: 0; }
      .au-sub { font-size: 12px; color: #6b7280; max-width: 760px; }
      .au-msg { font-size: 12px; color: #15803d; background: #f0fdf4; border: 1px solid #bbf7d0; padding: 8px; border-radius: 6px; }
      .au-msg.err { color: #b91c1c; background: #fef2f2; border-color: #fecaca; }
      .au-help { font-size: 12px; color: #4b5563; background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px 12px; }
      .au-help summary { cursor: pointer; font-weight: 600; color: #374151; }
      .au-help ul { margin: 6px 0 0 0; padding-left: 18px; }
      .au-tools { display: flex; gap: 12px; align-items: center; }
      .au-filter { flex: 1; max-width: 420px; padding: 6px 9px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
      .au-chk { font-size: 12px; color: #6b7280; display: flex; align-items: center; gap: 5px; }
      select { border: 1px solid #d1d5db; border-radius: 6px; padding: 4px 6px; font-size: 12px; }
      .au-list { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; overflow: auto; max-height: 70vh; }
      .au-row { display: grid; grid-template-columns: 150px 130px 80px 70px 80px 70px 60px 60px 80px; gap: 8px; padding: 7px 12px; border-bottom: 1px solid #f3f4f6; font-size: 12px; align-items: center; }
      .au-row-head { font-weight: 600; color: #6b7280; background: #fafafa; position: sticky; top: 0; }
      .au-row.bad { background: #fef2f2; }
      .num { text-align: right; }
      .ts, .slot { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #374151; }
      .st { font-weight: 600; color: #15803d; }
      .st.fail { color: #b91c1c; }
      .au-detail { padding: 4px 12px 8px 12px; font-size: 12px; color: #4b5563; border-bottom: 1px solid #f3f4f6; }
      .au-detail .err-label { font-weight: 600; color: #b91c1c; text-transform: uppercase; font-size: 10px; margin-right: 6px; }
      .au-detail summary { cursor: pointer; color: #6b7280; }
      pre { font-size: 11px; background: #f9fafb; padding: 8px; border-radius: 6px; overflow: auto; max-height: 220px; margin: 6px 0 0 0; white-space: pre-wrap; }
      .empty { padding: 16px; color: #9ca3af; font-size: 12px; }
      .btn { border: 1px solid #d1d5db; background: #fff; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; }
      .btn:disabled { opacity: 0.5; cursor: default; }
    `,
  ],
})
export class AuditComponent implements OnInit {
  entries: any[] = [];
  busy = false;
  message = "";
  isError = false;
  filter = "";
  onlyFailures = false;
  limit = 200;

  constructor(private api: ModelApiService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.busy = true;
    this.api.audit(this.limit).subscribe({
      next: (rows) => {
        this.busy = false;
        this.entries = rows || [];
      },
      error: (e) => this.fail(e),
    });
  }

  filtered(): any[] {
    const f = this.filter.trim().toLowerCase();
    return this.entries.filter((e) => {
      if (this.onlyFailures && e.ok !== false) return false;
      if (!f) return true;
      const status = e.ok === false ? "failed" : "ok";
      return (
        String(e.slot || "").toLowerCase().includes(f) ||
        String(e.action || "").toLowerCase().includes(f) ||
        status.includes(f) ||
        String(e.error || "").toLowerCase().includes(f)
      );
    });
  }

  private fail(e: any): void {
    this.busy = false;
    this.isError = true;
    this.message =
      (e && e.error && e.error.error) ||
      (e && e.message) ||
      "Request failed - is the backend running on :9700?";
  }
}

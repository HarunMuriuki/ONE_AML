import { Component, EventEmitter, OnInit, Output } from "@angular/core";
import { GraphApiService } from "../services/graph-api.service";

@Component({
  selector: "app-sync-control",
  template: `
    <div class="sc">
      <div class="sc-title">Oracle &rarr; Neo4j Sync</div>

      <label>Entity</label>
      <select [(ngModel)]="entity" (ngModelChange)="onEntityChange()">
        <option *ngFor="let e of entities" [value]="e.entity">{{ e.entity }}</option>
      </select>
      <div class="sc-src">{{ source }}</div>

      <label>Rows (max 5000)</label>
      <input type="number" [(ngModel)]="limit" min="1" max="5000" />

      <div class="sc-btns">
        <button class="btn-pref" [disabled]="busy" (click)="preview()">Preview</button>
        <button class="btn-sync" [disabled]="busy" (click)="sync()">
          {{ busy ? "Working..." : "Sync" }}
        </button>
      </div>

      <div *ngIf="message" class="sc-msg" [class.err]="isError">{{ message }}</div>

      <div *ngIf="rows.length" class="sc-table-wrap">
        <table>
          <thead>
            <tr><th *ngFor="let c of columns">{{ c }}</th></tr>
          </thead>
          <tbody>
            <tr *ngFor="let r of rows">
              <td *ngFor="let c of columns">{{ r[c] }}</td>
            </tr>
          </tbody>
        </table>
        <div class="sc-more" *ngIf="count > rows.length">
          showing {{ rows.length }} of {{ count }} rows (full set is synced)
        </div>
      </div>

      <details *ngIf="sql">
        <summary>Oracle SQL</summary>
        <pre>{{ sql }}</pre>
      </details>
      <details *ngIf="cypher">
        <summary>Generated Cypher</summary>
        <pre>{{ cypher }}</pre>
      </details>
    </div>
  `,
  styles: [
    `
      .sc { border: 1px solid #e5e7eb; border-radius: 8px; padding: 14px; background: #fff; }
      .sc-title { font-weight: 600; margin-bottom: 10px; font-size: 14px; }
      label { display: block; font-size: 12px; color: #6b7280; margin-top: 8px; }
      select, input { width: 100%; padding: 7px 9px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; box-sizing: border-box; }
      .sc-src { font-size: 11px; color: #9ca3af; margin-top: 4px; word-break: break-all; }
      .sc-btns { display: flex; gap: 8px; margin-top: 12px; }
      .sc-btns button { flex: 1; padding: 8px; border-radius: 6px; border: none; cursor: pointer; font-size: 13px; font-weight: 600; }
      .btn-pref { background: #f3f4f6; color: #111827; border: 1px solid #d1d5db !important; }
      .btn-sync { background: #2563eb; color: #fff; }
      .sc-msg { margin-top: 10px; font-size: 12px; color: #15803d; background: #f0fdf4; border: 1px solid #bbf7d0; padding: 8px; border-radius: 6px; word-break: break-word; }
      .sc-msg.err { color: #b91c1c; background: #fef2f2; border-color: #fecaca; }
      .sc-table-wrap { margin-top: 10px; overflow: auto; max-height: 260px; border: 1px solid #f3f4f6; border-radius: 6px; }
      table { border-collapse: collapse; width: 100%; font-size: 12px; }
      th, td { text-align: left; padding: 5px 8px; border-bottom: 1px solid #f3f4f6; white-space: nowrap; }
      th { background: #f9fafb; position: sticky; top: 0; }
      .sc-more { font-size: 11px; color: #9ca3af; padding: 6px 8px; }
      details { margin-top: 10px; }
      summary { font-size: 12px; color: #6b7280; cursor: pointer; }
      pre { font-size: 11px; background: #f9fafb; padding: 8px; border-radius: 6px; overflow: auto; max-height: 180px; }
    `,
  ],
})
export class SyncControlComponent implements OnInit {
  @Output() synced = new EventEmitter<void>();

  entities: any[] = [];
  entity = "Account";
  source = "";
  limit = 100;
  busy = false;
  message = "";
  isError = false;
  rows: any[] = [];
  columns: string[] = [];
  count = 0;
  sql = "";
  cypher = "";

  constructor(private api: GraphApiService) {}

  ngOnInit(): void {
    this.api.entities().subscribe({
      next: (res) => {
        this.entities = res.entities || [];
        this.onEntityChange();
      },
      error: (e) => this.fail(e),
    });
  }

  onEntityChange(): void {
    const found = this.entities.find((x) => x.entity === this.entity);
    this.source = found ? found.source : "";
    this.cypher = found ? found.cypher : "";
    this.sql = "";
    this.rows = [];
    this.message = "";
    this.api.sql(this.entity).subscribe({
      next: (res) => (this.sql = res.sql),
      error: () => {},
    });
  }

  preview(): void {
    this.busy = true;
    this.message = "";
    this.isError = false;
    this.api.preview(this.entity, this.limit).subscribe({
      next: (res) => {
        this.rows = res.rows || [];
        this.count = res.count || 0;
        this.columns = this.rows.length ? Object.keys(this.rows[0]) : [];
        this.message = `Preview: ${this.count} rows from Oracle`;
        this.busy = false;
      },
      error: (e) => this.fail(e),
    });
  }

  sync(): void {
    this.busy = true;
    this.message = "";
    this.isError = false;
    this.api.sync(this.entity, this.limit).subscribe({
      next: (res) => {
        this.message = `Synced ${res.pushed} of ${res.selected} rows in ${res.ms} ms`;
        this.cypher = res.cypher;
        this.busy = false;
        this.synced.emit();
      },
      error: (e) => this.fail(e),
    });
  }

  private fail(e: any): void {
    this.busy = false;
    this.isError = true;
    this.message = (e && e.error && e.error.error) || (e && e.message) || "Request failed - is the backend running on :9700?";
  }
}

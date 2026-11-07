import { Component, OnInit } from "@angular/core";
import { ModelApiService } from "../model/services/model-api.service";

/**
 * Settings screen (config.json).
 *
 * The one knob the user controls: how many Oracle rows a single slot "Run"
 * pulls before its cursor advances. Slice-per-click paging means a product
 * user just keeps pressing Run (or "All") and never sees offsets or limits.
 */
@Component({
  selector: "app-config",
  template: `
    <div class="cfg">
      <div class="cfg-head">
        <div>
          <h4>Settings</h4>
          <div class="cfg-sub">Runtime settings (config.json) - persisted on the backend, no restart needed</div>
        </div>
      </div>

      <div class="cfg-msg" *ngIf="message" [class.err]="isError">{{ message }}</div>

      <div class="card">
        <label>Rows per Run</label>
        <div class="hint">
          How many rows one slot "Run" pulls. Each Run appends the next window and
          advances that slot's cursor, so pressing Run repeatedly (or "All") syncs the
          whole result set.
        </div>
        <div class="row">
          <input type="number" min="1" [(ngModel)]="rowsPerRun" />
          <button class="btn primary" (click)="save()" [disabled]="busy || rowsPerRun < 1">Save</button>
        </div>
      </div>
    <div class="card">
        <label>Graph indexes (required for fast sync)</label>
        <div class="hint">
          Every node slot MERGEs on its key property. Without a Neo4j uniqueness
          constraint on that key, each row does a full label scan - so a sync
          takes minutes and gets slower as the graph grows. Apply once (it is
          idempotent, and a sync applies it automatically too).
        </div>
        <div class="row">
          <button class="btn primary" (click)="applyIndexes()" [disabled]="busy">Apply indexes</button>
          <button class="btn" (click)="showIndexes()" [disabled]="busy">Preview</button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .cfg { display: flex; flex-direction: column; gap: 14px; max-width: 640px; }
      .cfg-head h4 { margin: 0; }
      .cfg-sub { font-size: 12px; color: #6b7280; }
      .cfg-msg { font-size: 12px; color: #15803d; background: #f0fdf4; border: 1px solid #bbf7d0; padding: 8px; border-radius: 6px; }
      .cfg-msg.err { color: #b91c1c; background: #fef2f2; border-color: #fecaca; }
      .card { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; padding: 16px; }
      label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 4px; }
      .hint { font-size: 12px; color: #6b7280; margin-bottom: 12px; }
      .row { display: flex; gap: 8px; align-items: center; }
      input { width: 160px; padding: 7px 9px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; box-sizing: border-box; }
      .btn { border: 1px solid #d1d5db; background: #fff; border-radius: 6px; padding: 6px 14px; cursor: pointer; font-size: 12px; }
      .btn.primary { background: #2563eb; color: #fff; border-color: #2563eb; }
      .btn:disabled { opacity: 0.5; cursor: default; }
    `,
  ],
})
export class ConfigComponent implements OnInit {
  rowsPerRun = 500;
  busy = false;
  message = "";
  isError = false;

  constructor(private api: ModelApiService) {}

  ngOnInit(): void {
    this.api.config().subscribe({
      next: (c) => (this.rowsPerRun = c && c.rowsPerRun ? c.rowsPerRun : 500),
      error: (e) => this.fail(e),
    });
  }

  save(): void {
    if (this.rowsPerRun < 1) return;
    this.busy = true;
    this.api.saveConfig({ rowsPerRun: this.rowsPerRun }).subscribe({
      next: (c) => {
        this.busy = false;
        this.rowsPerRun = c.rowsPerRun;
        this.isError = false;
        this.message = `Saved. Rows per Run = ${c.rowsPerRun}.`;
      },
      error: (e) => this.fail(e),
    });
  }

  showIndexes(): void {
    this.busy = true;
    this.api.indexes().subscribe({
      next: (r) => {
        this.busy = false;
        this.isError = false;
        const st: string[] = r && r.statements ? r.statements : [];
        this.message = st.length
          ? st.length + " constraint(s) will be ensured: " + st.join(" | ")
          : "No keyed node slots found yet.";
      },
      error: (e) => this.fail(e),
    });
  }

  applyIndexes(): void {
    this.busy = true;
    this.api.applyIndexes().subscribe({
      next: (r) => {
        this.busy = false;
        const failed = r && r.errors && r.errors.length ? r.errors : [];
        const dropped = r && r.dropped && r.dropped.length ? r.dropped : [];
        const parts: string[] = [`${r.created} constraint(s) ensured.`];
        if (dropped.length) parts.push("Dropped obsolete: " + dropped.join(", ") + ".");
        if (failed.length) {
          this.isError = true;
          this.message = parts.join(" ") + ` ${failed.length} failed: ${failed.join(" | ")}`;
        } else {
          this.isError = false;
          this.message = parts.join(" ");
        }
      },
      error: (e) => this.fail(e),
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

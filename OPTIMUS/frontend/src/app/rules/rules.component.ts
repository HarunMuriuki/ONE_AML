import { Component, OnInit } from "@angular/core";
import { RulesApiService } from "./services/rules-api.service";

/**
 * Rules page.
 *
 * Shows rules.json - the rules discovered from Oracle's data dictionary,
 * grouped by title (Primary Keys, Declared Joins, Candidate Joins, Indexes,
 * Columns, Table Sizes). "Generate" / "Refresh" re-runs the dictionary queries
 * so a newly added table appears with no code change.
 *
 * Read-only by design: the file is produced by the backend, not edited here.
 */
@Component({
  selector: "app-rules",
  template: `
    <div class="rl">
      <div class="rl-head">
        <div>
          <h4>Rules</h4>
          <div class="rl-sub">
            Oracle data dictionary - keys, joins, indexes. Select a rule and Run it to read from Oracle.
          </div>
        </div>
        <div class="rl-tools">
          <span class="pill" *ngIf="catalog?.generatedAt">last run {{ catalog.generatedAt }}</span>
        </div>
      </div>

      <div class="rl-msg" *ngIf="message" [class.err]="isError">{{ message }}</div>

      <div class="rl-tables" *ngIf="catalog?.tables?.length">
        <span class="rl-tables-label">scanned tables:</span>
        <span class="chip" *ngFor="let t of catalog.tables">{{ t }}</span>
      </div>

      <div class="rl-none" *ngIf="!groups.length">
        No rules present - the backend serves them from rules.json.
      </div>

      <div class="group" *ngFor="let g of groups">
        <div class="group-head" (click)="toggle(g)">
          <span class="caret">{{ collapsed[g.id] ? "▶" : "▼" }}</span>
          <span class="group-title">{{ g.title }}</span>
          <span class="badge" *ngIf="g.ok === false">error</span>
          <span class="badge ok" *ngIf="g.ok === true">{{ (g.rows ? g.rows.length : 0) }} rows</span>
          <span class="group-desc">{{ g.description }}</span>
          <button class="run" (click)="run(g); $event.stopPropagation()" [disabled]="running[g.id]">
            {{ running[g.id] ? "Running..." : "Run" }}
          </button>
        </div>

        <div class="group-body" *ngIf="!collapsed[g.id]">
          <div class="group-err" *ngIf="g.error">{{ g.error }}</div>
          <div class="tbl-wrap" *ngIf="g.rows && g.rows.length">
            <table>
              <thead>
                <tr><th *ngFor="let c of columnsOf(g)">{{ c }}</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let r of g.rows">
                  <td *ngFor="let c of columnsOf(g)">{{ r[c] }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="group-empty" *ngIf="g.ok !== false && (!g.rows || !g.rows.length)">
            no rows
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .rl { display: flex; flex-direction: column; gap: 12px; }
      .rl-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
      .rl-head h4 { margin: 0; }
      .rl-sub { font-size: 12px; color: #6b7280; }
      .rl-tools { display: flex; align-items: center; gap: 8px; }
      .pill { font-size: 11px; padding: 4px 10px; border-radius: 999px; background: #f3f4f6; color: #6b7280; border: 1px solid #e5e7eb; }
      .btn { border: 1px solid #d1d5db; background: #fff; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; }
      .btn.primary { background: #2563eb; color: #fff; border-color: #2563eb; }
      .btn:disabled { opacity: 0.5; cursor: default; }
      .rl-msg { font-size: 12px; color: #15803d; background: #f0fdf4; border: 1px solid #bbf7d0; padding: 8px; border-radius: 6px; }
      .rl-msg.err { color: #b91c1c; background: #fef2f2; border-color: #fecaca; }
      .rl-tables { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
      .rl-tables-label { font-size: 11px; color: #9ca3af; }
      .chip { font-size: 11px; background: #f3f4f6; border: 1px solid #e5e7eb; border-radius: 4px; padding: 2px 8px; color: #374151; }
      .rl-none { color: #9ca3af; font-size: 13px; padding: 20px; border: 1px dashed #e5e7eb; border-radius: 8px; }
      .group { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; }
      .group-head { display: flex; align-items: center; gap: 10px; padding: 10px 12px; cursor: pointer; }
      .group-head:hover { background: #f9fafb; }
      .caret { color: #9ca3af; font-size: 10px; }
      .group-title { font-weight: 600; font-size: 13px; }
      .badge { font-size: 10px; border-radius: 4px; padding: 2px 7px; background: #fef2f2; color: #b91c1c; }
      .badge.ok { background: #f0fdf4; color: #15803d; }
      .group-desc { font-size: 11px; color: #9ca3af; margin-left: auto; }
      .run { border: 1px solid #2563eb; background: #2563eb; color: #fff; border-radius: 6px; padding: 3px 12px; cursor: pointer; font-size: 11px; }
      .run:disabled { opacity: 0.5; cursor: default; }
      .group-body { padding: 0 12px 12px; }
      .group-err { font-size: 12px; color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca; padding: 8px; border-radius: 6px; }
      .group-empty { font-size: 12px; color: #9ca3af; padding: 8px 0; }
      .tbl-wrap { overflow: auto; max-height: 360px; border: 1px solid #f3f4f6; border-radius: 6px; }
      table { border-collapse: collapse; width: 100%; font-size: 12px; }
      th, td { text-align: left; padding: 5px 8px; border-bottom: 1px solid #f3f4f6; white-space: nowrap; }
      th { background: #f9fafb; position: sticky; top: 0; }
    `,
  ],
})
export class RulesComponent implements OnInit {
  catalog: any = null;
  groups: any[] = [];
  message = "";
  isError = false;
  collapsed: { [id: string]: boolean } = {};
  running: { [id: string]: boolean } = {};

  constructor(private api: RulesApiService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.rules().subscribe({
      next: (c) => {
        this.catalog = c;
        this.groups = (c && c.groups) || [];
      },
      error: (e) => this.fail(e),
    });
  }

  /** Run a single rule: re-query just this group from Oracle, leave the rest. */
  run(g: any): void {
    this.running[g.id] = true;
    this.message = "";
    this.isError = false;
    this.api.refreshGroup(g.id).subscribe({
      next: (c) => {
        this.running[g.id] = false;
        this.catalog = c;
        this.groups = (c && c.groups) || [];
        this.message = g.title + " updated from Oracle.";
      },
      error: (e) => {
        this.running[g.id] = false;
        this.fail(e);
      },
    });
  }

  toggle(g: any): void {
    this.collapsed[g.id] = !this.collapsed[g.id];
  }

  columnsOf(g: any): string[] {
    if (g.columns && g.columns.length) return g.columns;
    if (g.rows && g.rows.length) return Object.keys(g.rows[0]);
    return [];
  }

  private fail(e: any): void {
    this.isError = true;
    this.message =
      (e && e.error && e.error.error) ||
      (e && e.message) ||
      "Request failed - is the backend up and Oracle reachable?";
  }
}

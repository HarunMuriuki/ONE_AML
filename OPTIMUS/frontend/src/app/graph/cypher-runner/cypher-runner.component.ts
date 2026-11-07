import { Component, EventEmitter, OnInit, Output } from "@angular/core";
import { GraphApiService } from "../services/graph-api.service";

@Component({
  selector: "app-cypher-runner",
  template: `
    <div class="cy">
      <div class="cy-title">Cypher Runner</div>
      <textarea
        [(ngModel)]="query"
        rows="4"
        spellcheck="false"
        placeholder="MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 50"
      ></textarea>
      <div class="cy-btns">
        <button [disabled]="busy" (click)="run()">{{ busy ? "Running..." : "Run" }}</button>
        <span class="cy-hint">reads return rows + graph; writes execute directly</span>
      </div>

      <div *ngIf="error" class="cy-err">{{ error }}</div>

      <div *ngIf="summary" class="cy-sum">{{ summary }}</div>

      <div *ngIf="columns.length" class="cy-table-wrap">
        <table>
          <thead><tr><th *ngFor="let c of columns">{{ c }}</th></tr></thead>
          <tbody>
            <tr *ngFor="let r of rows"><td *ngFor="let c of columns; let i = index">{{ r[i] }}</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  `,
  styles: [
    `
      .cy { border: 1px solid #e5e7eb; border-radius: 8px; padding: 14px; background: #fff; }
      .cy-title { font-weight: 600; margin-bottom: 8px; font-size: 14px; }
      textarea { width: 100%; box-sizing: border-box; font-family: Consolas, monospace; font-size: 13px; padding: 8px; border: 1px solid #d1d5db; border-radius: 6px; resize: vertical; }
      .cy-btns { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
      .cy-btns button { background: #111827; color: #fff; border: none; padding: 7px 18px; border-radius: 6px; cursor: pointer; font-size: 13px; font-weight: 600; }
      .cy-hint { font-size: 11px; color: #9ca3af; }
      .cy-err { margin-top: 8px; font-size: 12px; color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca; padding: 8px; border-radius: 6px; white-space: pre-wrap; }
      .cy-sum { margin-top: 8px; font-size: 12px; color: #374151; }
      .cy-table-wrap { margin-top: 8px; overflow: auto; max-height: 240px; border: 1px solid #f3f4f6; border-radius: 6px; }
      table { border-collapse: collapse; width: 100%; font-size: 12px; }
      th, td { text-align: left; padding: 5px 8px; border-bottom: 1px solid #f3f4f6; white-space: nowrap; }
      th { background: #f9fafb; position: sticky; top: 0; }
    `,
  ],
})
export class CypherRunnerComponent implements OnInit {
  @Output() graph = new EventEmitter<any>();

  query = "MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 50";
  busy = false;
  error = "";
  summary = "";
  columns: string[] = [];
  rows: any[][] = [];

  constructor(private api: GraphApiService) {}

  ngOnInit(): void {
    this.run();
  }

  run(): void {
    this.busy = true;
    this.error = "";
    this.summary = "";
    this.columns = [];
    this.rows = [];
    this.api.cypher(this.query).subscribe({
      next: (res) => {
        this.busy = false;
        if (res.error) {
          this.error = res.error;
          return;
        }
        if (res.ok) {
          this.summary = "Statement executed.";
          return;
        }
        this.columns = res.columns || [];
        this.rows = res.rows || [];
        const n = (res.nodes || []).length;
        const e = (res.edges || []).length;
        this.summary = `${this.rows.length} row(s)` + (n ? ` - ${n} nodes / ${e} relations captured` : "");
        if (n > 0) this.graph.emit({ nodes: res.nodes, edges: res.edges });
      },
      error: (err) => {
        this.busy = false;
        this.error = (err && err.error && err.error.error) || "Request failed - is the backend running on :9700?";
      },
    });
  }
}

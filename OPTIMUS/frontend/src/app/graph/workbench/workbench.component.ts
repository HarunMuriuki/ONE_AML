import { Component, OnInit } from "@angular/core";
import { GraphApiService } from "../services/graph-api.service";
import { GraphData } from "../graph-view/graph-view.component";

@Component({
  selector: "app-workbench",
  template: `
    <div class="wb">
      <div class="wb-head">
        <div>
          <h4>Graph Workbench</h4>
          <div class="wb-sub">Oracle (sitdb) &rarr; Neo4j - manual ETL control</div>
        </div>
        <div class="wb-status">
          <span class="chip" [class.up]="status?.oracle === 'UP'" [class.down]="status?.oracle === 'DOWN'">
            Oracle: {{ status?.oracle || "..." }}
          </span>
          <span class="chip" [class.up]="status?.neo4j === 'UP'" [class.down]="status?.neo4j === 'DOWN'">
            Neo4j: {{ status?.neo4j || "..." }}
          </span>
          <button class="wb-refresh" (click)="refreshAll()">Refresh</button>
        </div>
      </div>

      <div class="wb-stats">
        <div class="stat" *ngFor="let s of stats">
          <div class="stat-n">{{ s.count | number }}</div>
          <div class="stat-l">{{ s.label }}</div>
        </div>
        <div class="stat empty" *ngIf="!stats.length">graph empty - run a sync</div>
      </div>

      <div class="wb-grid">
        <app-sync-control (synced)="onSynced()"></app-sync-control>
        <app-graph-view [data]="graphData"></app-graph-view>
      </div>

      <app-cypher-runner (graph)="onGraph($event)"></app-cypher-runner>

      <div class="wb-alerts" *ngIf="alerts.length">
        <div class="wb-title">Recent alerts</div>
        <table>
          <thead><tr><th>Tran</th><th>Acct</th><th>Amount</th><th>Risk</th><th>Rule</th></tr></thead>
          <tbody>
            <tr *ngFor="let a of alerts">
              <td>{{ a.tranId }}</td><td>{{ a.acid }}</td><td>{{ a.amount | number }}</td>
              <td>{{ a.riskScore }}</td><td>{{ a.rule }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `,
  styles: [
    `
      .wb { display: flex; flex-direction: column; gap: 14px; }
      .wb-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
      .wb-head h4 { margin: 0; }
      .wb-sub { font-size: 12px; color: #6b7280; }
      .wb-status { display: flex; gap: 8px; align-items: center; }
      .chip { font-size: 12px; padding: 4px 10px; border-radius: 999px; background: #f3f4f6; color: #6b7280; border: 1px solid #e5e7eb; }
      .chip.up { background: #f0fdf4; color: #15803d; border-color: #bbf7d0; }
      .chip.down { background: #fef2f2; color: #b91c1c; border-color: #fecaca; }
      .wb-refresh { border: 1px solid #d1d5db; background: #fff; border-radius: 6px; padding: 4px 12px; cursor: pointer; font-size: 12px; }
      .wb-stats { display: flex; gap: 10px; flex-wrap: wrap; }
      .stat { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 16px; min-width: 110px; }
      .stat-n { font-size: 20px; font-weight: 700; }
      .stat-l { font-size: 11px; color: #6b7280; }
      .stat.empty { color: #9ca3af; font-size: 12px; display: flex; align-items: center; }
      .wb-grid { display: grid; grid-template-columns: 360px 1fr; gap: 14px; align-items: start; }
      @media (max-width: 992px) { .wb-grid { grid-template-columns: 1fr; } }
      .wb-alerts { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px; overflow: auto; }
      .wb-title { font-weight: 600; font-size: 13px; margin-bottom: 6px; }
      table { border-collapse: collapse; width: 100%; font-size: 12px; }
      th, td { text-align: left; padding: 5px 8px; border-bottom: 1px solid #f3f4f6; white-space: nowrap; }
      th { background: #f9fafb; }
    `,
  ],
})
export class WorkbenchComponent implements OnInit {
  stats: any[] = [];
  alerts: any[] = [];
  status: any = null;
  graphData: GraphData | null = null;

  private defaultGraphQuery =
    "MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 100";

  constructor(private api: GraphApiService) {}

  ngOnInit(): void {
    this.refreshAll();
    this.loadGraph();
  }

  refreshAll(): void {
    this.api.status().subscribe({
      next: (s) => (this.status = s),
      error: () => (this.status = { oracle: "DOWN", neo4j: "DOWN" }),
    });
    this.api.stats().subscribe({
      next: (res) => (this.stats = res.stats || []),
      error: () => (this.stats = []),
    });
    this.api.alerts(10).subscribe({
      next: (res) => (this.alerts = res.alerts || []),
      error: () => (this.alerts = []),
    });
  }

  onSynced(): void {
    this.refreshAll();
    this.loadGraph();
  }

  onGraph(data: GraphData): void {
    this.graphData = data;
  }

  private loadGraph(): void {
    this.api.cypher(this.defaultGraphQuery).subscribe({
      next: (res) => {
        if (res && res.nodes && res.nodes.length) {
          this.graphData = { nodes: res.nodes, edges: res.edges };
        }
      },
      error: () => {},
    });
  }
}

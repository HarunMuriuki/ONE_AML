import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  ViewChild,
} from "@angular/core";
import * as echarts from "echarts";

export interface GraphData {
  nodes: Array<{ id: number; labels: string[]; props: any }>;
  edges: Array<{ id: number; type: string; from: number; to: number; props: any }>;
}

@Component({
  selector: "app-graph-view",
  template: `
    <div class="gv-wrap">
      <div class="gv-bar">
        <span *ngIf="!data || !data.nodes?.length">No graph loaded - sync an entity or run a Cypher query</span>
        <span *ngIf="data && data.nodes?.length">
          {{ data.nodes.length }} nodes / {{ data.edges.length }} relations
        </span>
        <button *ngIf="data && data.nodes?.length" (click)="fit()">Fit</button>
      </div>
      <div #chartEl class="gv-chart"></div>
    </div>
  `,
  styles: [
    `
      .gv-wrap { display: flex; flex-direction: column; height: 100%; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
      .gv-bar { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: #f9fafb; font-size: 13px; color: #374151; }
      .gv-bar button { border: 1px solid #d1d5db; background: #fff; border-radius: 6px; padding: 2px 10px; cursor: pointer; font-size: 12px; }
      .gv-chart { flex: 1; min-height: 480px; }
    `,
  ],
})
export class GraphViewComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild("chartEl", { static: true }) chartEl!: ElementRef<HTMLDivElement>;
  @Input() data: GraphData | null = null;

  private chart: echarts.ECharts | null = null;
  private ready = false;

  ngAfterViewInit(): void {
    this.chart = echarts.init(this.chartEl.nativeElement);
    this.ready = true;
    this.render();
    window.addEventListener("resize", this.onResize);
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes["data"] && this.ready) this.render();
  }

  ngOnDestroy(): void {
    window.removeEventListener("resize", this.onResize);
    this.chart?.dispose();
  }

  fit(): void {
    this.chart?.dispatchAction({ type: "restore" });
  }

  private onResize = (): void => this.chart?.resize();

  private render(): void {
    if (!this.chart || !this.data || !this.data.nodes.length) {
      this.chart?.clear();
      return;
    }

    const labels = Array.from(
      new Set(this.data.nodes.map((n) => (n.labels && n.labels[0]) || "Node"))
    );
    const nameOf = (n: any): string => {
      const p = n.props || {};
      return (
        p.name ||
        p.accountNum ||
        (p.firstname ? p.firstname + " " + (p.lastname || "") : "") ||
        p.cifId ||
        p.acid ||
        p.solId ||
        p.tranId ||
        p.code ||
        p.type ||
        "#" + n.id
      );
    };

    const option: echarts.EChartsOption = {
      tooltip: {
        formatter: (p: any) => {
          if (p.dataType === "edge") {
            const e: any = p.data;
            return `<b>${e.value}</b>`;
          }
          const d: any = p.data;
          const rows = Object.entries(d.props || {})
            .slice(0, 8)
            .map(([k, v]) => `${k}: ${v}`)
            .join("<br/>");
          return `<b>${d.name}</b><br/><small>${(d.labels || []).join(",")}</small><br/>${rows}`;
        },
      },
      colorBy: "data",
      series: [
        {
          type: "graph",
          layout: "force",
          roam: true,
          draggable: true,
          force: { repulsion: 420, edgeLength: 130, gravity: 0.08 },
          label: { show: true, position: "right", fontSize: 11, color: "#111827" },
          edgeLabel: { show: false },
          emphasis: { focus: "adjacency" },
          categories: labels.map((l) => ({ name: l })),
          data: this.data.nodes.map((n) => ({
            id: String(n.id),
            name: nameOf(n),
            labels: n.labels,
            props: n.props,
            category: Math.max(0, labels.indexOf((n.labels && n.labels[0]) || "Node")),
            symbolSize: 26,
          })),
          links: this.data.edges.map((e) => ({
            source: String(e.from),
            target: String(e.to),
            value: e.type,
          })) as any,
        },
      ],
    };
    this.chart.setOption(option, true);
  }
}

import { Component, OnDestroy, OnInit } from "@angular/core";
import { Subscription, interval, startWith, switchMap } from "rxjs";
import { ModelApiService } from "../../model/services/model-api.service";

/**
 * Minimal top navigation for NEO (the sidebar was intentionally removed).
 * Adds the Model & Pipelines, Activity and Audit screens alongside the Workbench.
 * The Activity badge is polled from the backend so it survives page refreshes.
 */
@Component({
  selector: "app-neo-nav",
  template: `
    <div class="neo-nav">
      <a routerLink="/graph" routerLinkActive="active">Workbench</a>
      <a routerLink="/model" routerLinkActive="active">Model &amp; Pipelines</a>
      <a routerLink="/activity" routerLinkActive="active" class="act">
        Activity
        <span class="badge" *ngIf="running > 0">{{ running }}</span>
      </a>
      <a routerLink="/rules" routerLinkActive="active">Rules</a>
      <a routerLink="/config" routerLinkActive="active">Settings</a>
      <a routerLink="/audit" routerLinkActive="active">Audit</a>
    </div>
  `,
  styles: [
    `
      .neo-nav { display: flex; gap: 6px; }
      .neo-nav a {
        font-size: 13px;
        padding: 6px 14px;
        border-radius: 6px;
        text-decoration: none;
        color: #374151;
        border: 1px solid #e5e7eb;
        background: #fff;
        display: inline-flex;
        align-items: center;
        gap: 6px;
      }
      .neo-nav a.active { background: #2563eb; color: #fff; border-color: #2563eb; }
      .badge {
        min-width: 18px;
        padding: 0 5px;
        height: 18px;
        border-radius: 999px;
        background: #dc2626;
        color: #fff;
        font-size: 11px;
        line-height: 18px;
        text-align: center;
      }
    `,
  ],
})
export class NeoNavComponent implements OnInit, OnDestroy {
  running = 0;
  private sub?: Subscription;

  constructor(private api: ModelApiService) {}

  ngOnInit(): void {
    this.sub = interval(2000)
      .pipe(startWith(0), switchMap(() => this.api.jobs()))
      .subscribe((jobs) => (this.running = (jobs || []).filter((j: any) => j.status === "RUNNING").length));
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
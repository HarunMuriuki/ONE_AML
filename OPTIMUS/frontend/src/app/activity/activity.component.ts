import { Component, OnDestroy, OnInit } from "@angular/core";
import { Subscription, interval, startWith, switchMap } from "rxjs";
import { ModelApiService } from "../model/services/model-api.service";

/**
 * Live background sync jobs. This is where a Run actually shows its progress:
 * clicking Run/Run all on the Model page returns instantly and the work happens
 * in the backend worker (a single thread, so syncs never deadlock each other).
 * The list is polled from the server, so a page refresh re-attaches to any
 * still-running job - you never lose track of a long sync.
 */
@Component({
  selector: "app-activity",
  template: `
    <div class="wrap">
      <div class="head">
        <h2>Activity</h2>
        <span class="live" [class.on]="running > 0">{{ running > 0 ? running + " running" : "idle" }}</span>
        <button class="btn" (click)="load()">Refresh</button>
      </div>

      <details class="help" open>
        <summary>How syncs work now</summary>
        <ul>
          <li><b>Run</b> and <b>Run all</b> on the Model page start a <b>background job</b> and return immediately - the page never freezes.</li>
          <li>Jobs run on a <b>single backend worker</b>, so two syncs can never run at once (concurrent MERGEs would deadlock).</li>
          <li>This list <b>polls the server</b>, so you can refresh or use other screens and still see live progress and completion.</li>
          <li><b>Cancel</b> stops a job at the next batch boundary; whatever is already pushed stays (MERGE is idempotent).</li>
          <li><b>detach delete</b> (Model page) removes a slot's graph data on the same worker. It is permanent and cannot be cancelled; the mapping is kept.</li>
        </ul>
      </details>

      <div class="jobs">
        <div *ngIf="!jobs.length" class="empty">No sync activity yet.</div>
        <div *ngFor="let j of jobs" class="job" [class.run]="j.status === 'RUNNING'" [class.err]="j.status === 'FAILED'">
          <div class="row">
            <span class="id">{{ j.id }}</span>
            <span class="target">
              {{ j.action === "detach-delete" ? "detach delete " + j.target : j.action + " " + j.slot }}
            </span>
            <span class="status" [attr.data-s]="j.status">{{ j.status }}</span>
          </div>
          <div class="bar" *ngIf="j.status === 'RUNNING'"><div class="fill"></div></div>
          <div class="meta" *ngIf="j.action !== 'detach-delete'">
            <span>{{ j.selected | number }} rows read</span>
            <span class="new">{{ j.created | number }} new</span>
            <span>{{ j.existing | number }} already present</span>
            <span *ngIf="j.count">{{ j.count | number }} total</span>
            <span *ngIf="j.ms">{{ j.ms | number }} ms</span>
          </div>
          <div class="meta" *ngIf="j.action === 'detach-delete'">
            <span class="del">{{ j.deletedNodes | number }} nodes deleted</span>
            <span class="del">{{ j.deletedRelationships | number }} relationships deleted</span>
            <span *ngIf="j.ms">{{ j.ms | number }} ms</span>
          </div>
          <div class="msg" *ngIf="j.error">Error: {{ j.error }}</div>
          <button class="btn tiny" *ngIf="j.status === 'RUNNING' && j.action !== 'detach-delete'" (click)="cancel(j)">
            Cancel
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .wrap { padding: 16px; }
      .head { display: flex; align-items: center; gap: 12px; }
      .head h2 { margin: 0; }
      .live { font-size: 12px; padding: 4px 10px; border-radius: 999px; background: #e5e7eb; color: #374151; }
      .live.on { background: #2563eb; color: #fff; }
      .btn { font-size: 12px; padding: 6px 12px; border-radius: 6px; border: 1px solid #e5e7eb; background: #fff; cursor: pointer; }
      .btn.tiny { margin-top: 6px; }
      .help { margin: 12px 0; background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 14px; font-size: 13px; }
      .help ul { margin: 6px 0; padding-left: 18px; }
      .jobs { display: flex; flex-direction: column; gap: 10px; }
      .empty { color: #6b7280; font-size: 13px; padding: 12px 0; }
      .job { border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 12px; background: #fff; }
      .job.run { border-color: #93c5fd; }
      .job.err { border-color: #fca5a5; background: #fef2f2; }
      .row { display: flex; align-items: center; gap: 10px; }
      .id { font-family: monospace; font-size: 12px; color: #6b7280; }
      .target { font-size: 13px; font-weight: 600; }
      .status { margin-left: auto; font-size: 11px; padding: 3px 8px; border-radius: 999px; background: #e5e7eb; }
      .status[data-s='RUNNING'] { background: #2563eb; color: #fff; }
      .status[data-s='DONE'] { background: #16a34a; color: #fff; }
      .status[data-s='FAILED'] { background: #dc2626; color: #fff; }
      .status[data-s='CANCELLED'] { background: #d97706; color: #fff; }
      .status[data-s='INTERRUPTED'] { background: #6b7280; color: #fff; }
      .bar { height: 4px; background: #e5e7eb; border-radius: 999px; margin-top: 8px; overflow: hidden; }
      .fill { height: 100%; width: 35%; background: #2563eb; animation: slide 1.1s linear infinite; }
      @keyframes slide { from { margin-left: -35%; } to { margin-left: 100%; } }
      .meta { display: flex; gap: 14px; margin-top: 8px; font-size: 12px; color: #6b7280; }
      .meta .new { color: #15803d; font-weight: 600; }
      .meta .del { color: #b91c1c; font-weight: 600; }
      .msg { margin-top: 6px; font-size: 12px; color: #b91c1c; }
    `,
  ],
})
export class ActivityComponent implements OnInit, OnDestroy {
  jobs: any[] = [];
  running = 0;
  private sub?: Subscription;

  constructor(private api: ModelApiService) {}

  ngOnInit(): void {
    this.sub = interval(1000)
      .pipe(startWith(0), switchMap(() => this.api.jobs()))
      .subscribe((jobs) => {
        this.jobs = jobs || [];
        this.running = this.jobs.filter((j) => j.status === "RUNNING").length;
      });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  load(): void {
    this.api.jobs().subscribe((jobs) => {
      this.jobs = jobs || [];
      this.running = this.jobs.filter((j) => j.status === "RUNNING").length;
    });
  }

  cancel(j: any): void {
    this.api.cancelJob(j.id).subscribe(() => this.load());
  }
}
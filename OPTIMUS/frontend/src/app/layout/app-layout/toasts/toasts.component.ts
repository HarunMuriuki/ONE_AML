import { Component } from "@angular/core";
import { ToastService } from "src/app/core/service/toast.service";

/**
 * Modal DIALOG BOX for sync messages (one shared instance in the main layout).
 *
 * Unlike a toast, this is a real centred dialog over a dimmed backdrop:
 *   - running   : closes by itself after a few seconds so work can continue
 *   - finished / all-added / failed / cancelled : wait for OK (or the X)
 */
@Component({
  selector: "app-toasts",
  template: `
    <div class="backdrop" *ngIf="toastSvc.toasts.length" (click)="closeAll()">
      <div class="dialog" *ngFor="let t of toastSvc.toasts" [attr.data-k]="t.kind" (click)="$event.stopPropagation()">
        <div class="bar"></div>
        <div class="body">
          <div class="icon" [attr.data-k]="t.kind">{{ icon(t.kind) }}</div>
          <div class="text">
            <div class="title">{{ t.title }}</div>
            <div class="msg">{{ t.message }}</div>
            <div class="hint" *ngIf="t.kind === 'running'">You can close this and keep working - the sync runs in the background.</div>
          </div>
        </div>
        <div class="foot">
          <button class="btn" (click)="toastSvc.dismiss(t.id)">OK</button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .backdrop {
        position: fixed;
        inset: 0;
        background: rgba(15, 23, 42, 0.45);
        z-index: 9999;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 14px;
        padding: 24px;
      }
      .dialog {
        width: 440px;
        max-width: 100%;
        background: #fff;
        border-radius: 10px;
        box-shadow: 0 18px 48px rgba(15, 23, 42, 0.35);
        overflow: hidden;
        animation: pop 0.16s ease-out;
      }
      @keyframes pop { from { transform: scale(0.96); opacity: 0; } to { transform: none; opacity: 1; } }
      .bar { height: 4px; background: #2563eb; }
      .dialog[data-k='success'] .bar { background: #16a34a; }
      .dialog[data-k='empty'] .bar { background: #0891b2; }
      .dialog[data-k='error'] .bar { background: #dc2626; }
      .dialog[data-k='info'] .bar { background: #64748b; }
      .body { display: flex; gap: 14px; padding: 18px 20px 8px; }
      .icon {
        flex: 0 0 38px;
        width: 38px;
        height: 38px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 20px;
        background: #dbeafe;
        color: #2563eb;
      }
      .icon[data-k='success'] { background: #dcfce7; color: #15803d; }
      .icon[data-k='empty'] { background: #cffafe; color: #0e7490; }
      .icon[data-k='error'] { background: #fee2e2; color: #b91c1c; }
      .icon[data-k='info'] { background: #f1f5f9; color: #475569; }
      .text { min-width: 0; }
      .title { font-size: 15px; font-weight: 700; color: #0f172a; }
      .msg { margin-top: 6px; font-size: 13px; color: #334155; line-height: 1.5; word-break: break-word; }
      .hint { margin-top: 8px; font-size: 12px; color: #64748b; font-style: italic; }
      .foot { display: flex; justify-content: flex-end; padding: 10px 20px 16px; }
      .btn {
        min-width: 84px;
        padding: 8px 18px;
        font-size: 13px;
        font-weight: 600;
        color: #fff;
        background: #2563eb;
        border: 1px solid #2563eb;
        border-radius: 6px;
        cursor: pointer;
      }
      .btn:hover { background: #1d4ed8; }
    `,
  ],
})
export class ToastsComponent {
  constructor(public toastSvc: ToastService) {}

  icon(kind: string): string {
    switch (kind) {
      case "success":
        return "✓";
      case "empty":
        return "=";
      case "error":
        return "!";
      case "running":
        return "⟳";
      default:
        return "i";
    }
  }

  closeAll(): void {
    this.toastSvc.toasts = [];
  }
}
import { Component, HostListener } from "@angular/core";
import { ConfirmService } from "src/app/core/service/confirm.service";

/**
 * Modal CONFIRMATION dialog (one shared instance in the main layout).
 *
 * Opened by ConfirmService.confirm(); see that service for the options. It is a
 * real dialog box with a dimmed backdrop - not window.confirm() - so a delete can
 * list what else it affects and require the user to type the slot name first.
 */
@Component({
  selector: "app-confirm-dialog",
  template: `
    <div class="backdrop" *ngIf="svc.pending" (click)="cancel()">
      <div class="dialog" [class.danger]="opts?.danger" (click)="$event.stopPropagation()">
        <div class="bar"></div>
        <div class="body">
          <div class="icon">{{ opts?.danger ? "!" : "?" }}</div>
          <div class="text">
            <div class="title">{{ opts?.title }}</div>
            <div class="msg">{{ opts?.message }}</div>

            <ul class="details" *ngIf="opts?.details?.length">
              <li *ngFor="let d of opts?.details">{{ d }}</li>
            </ul>

            <div class="typed" *ngIf="opts?.requireTyped">
              <label>
                Type <code>{{ opts?.requireTyped }}</code> to confirm:
              </label>
              <input [(ngModel)]="typed" (keyup.enter)="ok()" autocomplete="off" spellcheck="false" />
            </div>
          </div>
        </div>
        <div class="foot">
          <button class="btn ghost" (click)="cancel()">{{ opts?.cancelText || "Cancel" }}</button>
          <button class="btn" [class.danger]="opts?.danger" [disabled]="!canConfirm()" (click)="ok()">
            {{ opts?.confirmText || "Confirm" }}
          </button>
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
        z-index: 10000;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 24px;
      }
      .dialog {
        width: 470px;
        max-width: 100%;
        background: #fff;
        border-radius: 10px;
        box-shadow: 0 18px 48px rgba(15, 23, 42, 0.35);
        overflow: hidden;
        animation: pop 0.16s ease-out;
      }
      @keyframes pop { from { transform: scale(0.96); opacity: 0; } to { transform: none; opacity: 1; } }
      .bar { height: 4px; background: #2563eb; }
      .dialog.danger .bar { background: #dc2626; }
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
        font-weight: 700;
        background: #dbeafe;
        color: #2563eb;
      }
      .dialog.danger .icon { background: #fee2e2; color: #b91c1c; }
      .text { min-width: 0; flex: 1; }
      .title { font-size: 15px; font-weight: 700; color: #0f172a; }
      .msg { margin-top: 6px; font-size: 13px; color: #334155; line-height: 1.5; word-break: break-word; white-space: pre-line; }
      .details { margin: 10px 0 0; padding-left: 18px; font-size: 12px; color: #475569; line-height: 1.55; }
      .typed { margin-top: 12px; }
      .typed label { display: block; font-size: 12px; color: #475569; margin-bottom: 5px; }
      .typed input {
        width: 100%;
        padding: 7px 9px;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        font-size: 13px;
        box-sizing: border-box;
      }
      code { background: #f3f4f6; padding: 1px 4px; border-radius: 3px; font-size: 12px; }
      .foot { display: flex; justify-content: flex-end; gap: 8px; padding: 10px 20px 16px; }
      .btn {
        min-width: 92px;
        padding: 8px 16px;
        font-size: 13px;
        font-weight: 600;
        color: #fff;
        background: #2563eb;
        border: 1px solid #2563eb;
        border-radius: 6px;
        cursor: pointer;
      }
      .btn:hover:not(:disabled) { background: #1d4ed8; }
      .btn.danger { background: #dc2626; border-color: #dc2626; }
      .btn.danger:hover:not(:disabled) { background: #b91c1c; }
      .btn.ghost { background: #fff; color: #374151; border-color: #d1d5db; }
      .btn.ghost:hover { background: #f9fafb; }
      .btn:disabled { opacity: 0.45; cursor: default; }
    `,
  ],
})
export class ConfirmDialogComponent {
  typed = "";

  constructor(public svc: ConfirmService) {}

  get opts() {
    return this.svc.pending?.opts;
  }

  canConfirm(): boolean {
    const need = this.opts?.requireTyped;
    return !need || this.typed.trim() === need;
  }

  ok(): void {
    if (!this.canConfirm()) return;
    this.typed = "";
    this.svc.settle(true);
  }

  cancel(): void {
    this.typed = "";
    this.svc.settle(false);
  }

  @HostListener("document:keydown.escape")
  onEscape(): void {
    if (this.svc.pending) this.cancel();
  }
}

import { Injectable } from "@angular/core";

/**
 * Options for a confirmation dialog box.
 *
 * Use this instead of window.confirm(): a real modal that can list what else is
 * affected (cascaded relationships, data counts) and can require the user to type
 * the slot name before a destructive action is enabled.
 */
export interface ConfirmOptions {
  title: string;
  message: string;
  /** Extra lines, e.g. cascaded relationships or data that will be removed. */
  details?: string[];
  confirmText?: string;
  cancelText?: string;
  /** Red confirm button - use for destructive actions. */
  danger?: boolean;
  /**
   * When set, the confirm button stays disabled until the user types this exact
   * text. Used for permanent graph-data deletes.
   */
  requireTyped?: string;
}

interface PendingConfirm {
  opts: ConfirmOptions;
  resolve: (ok: boolean) => void;
}

/**
 * One shared confirmation dialog (mounted in the main layout, like the toasts).
 *
 * Call `await confirmSvc.confirm({...})`; it resolves true only when the user
 * clicks the confirm button (and, with requireTyped, typed the exact text).
 * Closing the dialog, clicking the backdrop or pressing Escape resolves false.
 */
@Injectable({ providedIn: "root" })
export class ConfirmService {
  pending: PendingConfirm | null = null;

  confirm(opts: ConfirmOptions): Promise<boolean> {
    // A second request replaces the first: never stack destructive dialogs.
    this.settle(false);
    return new Promise<boolean>((resolve) => {
      this.pending = { opts, resolve };
    });
  }

  /** Called by the dialog component. */
  settle(ok: boolean): void {
    const p = this.pending;
    if (!p) return;
    this.pending = null;
    p.resolve(ok);
  }
}

import { Injectable } from "@angular/core";

export type ToastKind = "info" | "running" | "success" | "empty" | "error";

export interface Toast {
  id: number;
  kind: ToastKind;
  title: string;
  message: string;
  at: number;
}

/**
 * App-wide modal dialog messages for sync actions, so the outcome of a Run is
 * announced in a real dialog box instead of a line of text that scrolls away.
 * Kinds: running / success / empty (all records already added) / error.
 *
 * ttlMs 0 (the default) = stay open until the user clicks OK, which is what you
 * want for results. Only "running" passes a timeout so it clears itself.
 */
@Injectable({ providedIn: "root" })
export class ToastService {
  toasts: Toast[] = [];
  private nextId = 1;

  show(kind: ToastKind, title: string, message: string, ttlMs = 0): void {
    const t: Toast = { id: this.nextId++, kind, title, message, at: Date.now() };
    // Newest on top; never stack more than three.
    this.toasts = [t, ...this.toasts].slice(0, 3);
    if (ttlMs > 0) setTimeout(() => this.dismiss(t.id), ttlMs);
  }

  dismiss(id: number): void {
    this.toasts = this.toasts.filter((t) => t.id !== id);
  }
}
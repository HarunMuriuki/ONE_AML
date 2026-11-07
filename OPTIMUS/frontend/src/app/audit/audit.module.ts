import { NgModule } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { Routes, RouterModule } from "@angular/router";
import { AuditComponent } from "./audit.component";

const routes: Routes = [{ path: "", component: AuditComponent }];

/**
 * Audit feature module (lazy-loaded at /audit).
 * Reads the append-only audit.jsonl trail from the backend.
 */
@NgModule({
  declarations: [AuditComponent],
  imports: [CommonModule, FormsModule, RouterModule.forChild(routes)],
})
export class AuditModule {}

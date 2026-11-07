import { NgModule } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { Routes, RouterModule } from "@angular/router";
import { RulesComponent } from "./rules.component";

const routes: Routes = [{ path: "", component: RulesComponent }];

/**
 * Rules feature module (lazy-loaded at /rules).
 * Shows rules.json - the rules discovered from Oracle's data dictionary,
 * grouped by title, refreshed from the backend on demand.
 */
@NgModule({
  declarations: [RulesComponent],
  imports: [CommonModule, FormsModule, RouterModule.forChild(routes)],
})
export class RulesModule {}

import { NgModule } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { Routes, RouterModule } from "@angular/router";
import { ModelComponent } from "./model.component";

const routes: Routes = [{ path: "", component: ModelComponent }];

/**
 * Model & Pipelines feature module (lazy-loaded at /model).
 * Lists every node/relationship of the static model and lets you edit the
 * mapping (SELECT + key + column map) that the backend persists.
 */
@NgModule({
  declarations: [ModelComponent],
  imports: [CommonModule, FormsModule, RouterModule.forChild(routes)],
})
export class ModelModule {}

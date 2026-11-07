import { NgModule } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { Routes, RouterModule } from "@angular/router";
import { WorkbenchComponent } from "./workbench/workbench.component";
import { SyncControlComponent } from "./sync-control/sync-control.component";
import { GraphViewComponent } from "./graph-view/graph-view.component";
import { CypherRunnerComponent } from "./cypher-runner/cypher-runner.component";

const routes: Routes = [{ path: "", component: WorkbenchComponent }];

@NgModule({
  declarations: [
    WorkbenchComponent,
    SyncControlComponent,
    GraphViewComponent,
    CypherRunnerComponent,
  ],
  imports: [CommonModule, FormsModule, RouterModule.forChild(routes)],
})
export class GraphModule {}

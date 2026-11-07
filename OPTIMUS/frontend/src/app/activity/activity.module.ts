import { NgModule } from "@angular/core";
import { CommonModule } from "@angular/common";
import { Routes, RouterModule } from "@angular/router";
import { ActivityComponent } from "./activity.component";

const routes: Routes = [{ path: "", component: ActivityComponent }];

/** Activity feature module (lazy-loaded at /activity): live background sync jobs. */
@NgModule({
  declarations: [ActivityComponent],
  imports: [CommonModule, RouterModule.forChild(routes)],
})
export class ActivityModule {}
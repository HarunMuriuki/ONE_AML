import { NgModule } from "@angular/core";
import { Routes, RouterModule } from "@angular/router";
import { MainLayoutComponent } from "./layout/app-layout/main-layout/main-layout.component";

const routes: Routes = [
  {
    path: "",
    component: MainLayoutComponent,
    children: [
      { path: "", redirectTo: "graph", pathMatch: "full" },
      {
        path: "graph",
        loadChildren: () => import("./graph/graph.module").then((m) => m.GraphModule),
      },
      {
        path: "model",
        loadChildren: () => import("./model/model.module").then((m) => m.ModelModule),
      },
      {
        path: "rules",
        loadChildren: () => import("./rules/rules.module").then((m) => m.RulesModule),
      },
      {
        path: "config",
        loadChildren: () => import("./config/config.module").then((m) => m.ConfigModule),
      },
      {
        path: "audit",
        loadChildren: () => import("./audit/audit.module").then((m) => m.AuditModule),
      },
      {
        path: "activity",
        loadChildren: () => import("./activity/activity.module").then((m) => m.ActivityModule),
      },
    ],
  },
  { path: "**", redirectTo: "graph" },
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { useHash: false, relativeLinkResolution: "legacy" })],
  exports: [RouterModule],
})
export class AppRoutingModule {}

import { NgModule } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { Routes, RouterModule } from "@angular/router";
import { ConfigComponent } from "./config.component";

const routes: Routes = [{ path: "", component: ConfigComponent }];

/**
 * Settings feature module (lazy-loaded at /config).
 * Edits config.json - the runtime settings (rows per Run) the sync service uses.
 */
@NgModule({
  declarations: [ConfigComponent],
  imports: [CommonModule, FormsModule, RouterModule.forChild(routes)],
})
export class ConfigModule {}

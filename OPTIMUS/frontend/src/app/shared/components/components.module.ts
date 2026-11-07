import { NgModule } from "@angular/core";
import { FileUploadComponent } from "./file-upload/file-upload.component";
import { BreadcrumbComponent } from "./breadcrumb/breadcrumb.component";
import { SharedModule } from "../shared.module";
import { BaseComponent } from './base/base.component';
import { SpinnerComponent } from "./spinner/spinner.component";

@NgModule({
  declarations: [FileUploadComponent, BreadcrumbComponent, BaseComponent, SpinnerComponent],
  imports: [SharedModule],
  exports: [FileUploadComponent, BreadcrumbComponent, SpinnerComponent],
})
export class ComponentsModule {}

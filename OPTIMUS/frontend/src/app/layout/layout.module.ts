import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgbModule } from '@ng-bootstrap/ng-bootstrap';
import { MatTabsModule } from '@angular/material/tabs';
import { SharedModule } from '../shared/shared.module';
import { ComponentsModule } from '../shared/components/components.module';
import { MatIconModule } from '@angular/material/icon';
@NgModule({
  declarations: [],
  imports: [
    CommonModule,
    NgbModule,
    MatTabsModule,
    SharedModule,
    ComponentsModule,
    MatIconModule
  ],
})
export class LayoutModule {}

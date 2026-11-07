import { Injectable } from '@angular/core';
import {
  Router,
  CanActivate,
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
} from '@angular/router';

import { AuthService } from '../service/auth.service';
import { TokenStorageService } from '../service/token-storage.service';

@Injectable({
  providedIn: 'root',
})
export class AuthGuard implements CanActivate {
  constructor(
    private authService: AuthService,
    private router: Router,
    private tokenStorage: TokenStorageService
  ) {}

  canActivate(route: ActivatedRouteSnapshot, state: RouterStateSnapshot) {
    const user = this.tokenStorage.getUser();

    if (!user) {
      this.router.navigate(['/authentication/signin']);
      return false;
    }

     const userRole = user.role;

    const allowedRoles = Array.isArray(route.data?.roles)
      ? route.data.roles
      : route.data?.roles
      ? [route.data.roles]
      : [];

    if (allowedRoles.length && !allowedRoles.includes(userRole)) {
      this.router.navigate(['/authentication/signin']);
      return false;
    }

    return true;
  }
}

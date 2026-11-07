import { Injectable } from '@angular/core';
import {
  HttpRequest,
  HttpHandler,
  HttpEvent,
  HttpInterceptor,
  HttpErrorResponse,
} from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { Router } from '@angular/router';
import { AuthService } from '../service/auth.service';
import { TokenStorageService } from '../service/token-storage.service';

@Injectable()
export class ErrorInterceptor implements HttpInterceptor {
  constructor(
    private authenticationService: AuthService,
    private router: Router,
    private tokenStorage: TokenStorageService
  ) {}

  intercept(
    request: HttpRequest<any>,
    next: HttpHandler
  ): Observable<HttpEvent<any>> {
    return next.handle(request).pipe(
      catchError((err: HttpErrorResponse) => {
        if (err.status === 401) {
          const refreshToken = this.tokenStorage.getRefreshToken();

          if (!refreshToken) {
            console.warn('No refresh token available. Redirecting to login.');
            this.authenticationService.signout();
            this.router.navigate(['/authentication/signin']);
            return throwError(() => err);
          }

          return this.authenticationService.refreshToken().pipe(
            switchMap((res: any) => {
              console.log('Token refresh response:', res);

              if (res?.statusCode === 200 && res?.entity) {
                const newToken = res.entity;
                this.tokenStorage.saveToken(newToken, refreshToken);

                const clonedReq = request.clone({
                  headers: request.headers.set(
                    'Authorization',
                    `Bearer ${newToken}`
                  ),
                });

                return next.handle(clonedReq);
              } else {
                console.warn('Token refresh failed. Logging out.');
                this.authenticationService.signout();
                this.router.navigate(['/authentication/signin']);
                return throwError(() => new Error('Unable to refresh token'));
              }
            }),
            catchError((refreshErr) => {
              console.error('Refresh token error:', refreshErr);
              this.authenticationService.signout();
              this.router.navigate(['/authentication/signin']);
              return throwError(() => refreshErr);
            })
          );
        }

        let errorMessage = 'Unknown Error';

        if (err.error instanceof ErrorEvent) {
          errorMessage = err.error.message;
        } else if (err.error?.error) {
          errorMessage = err.error.error;
        } else if (err.error?.message) {
          errorMessage = err.error.message;
        } else if (err.message) {
          errorMessage = err.message;
        } else if (err.statusText) {
          errorMessage = err.statusText;
        }

        console.error('Intercepted HTTP error:', err);

        return throwError(() => new Error(errorMessage));
      })
    );
  }
}

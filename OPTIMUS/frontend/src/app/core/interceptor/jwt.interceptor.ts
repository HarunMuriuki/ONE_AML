import { Injectable } from "@angular/core";
import { jwtDecode } from 'jwt-decode';

import {
  HttpRequest,
  HttpHandler,
  HttpEvent,
  HttpInterceptor,
} from "@angular/common/http";
import { Observable } from "rxjs";
import { AuthService } from "../service/auth.service";
import { TokenStorageService } from "../service/token-storage.service";

@Injectable()
export class JwtInterceptor implements HttpInterceptor {
  constructor(private tokenStorage: TokenStorageService) {}

  intercept(
    request: HttpRequest<any>,
    next: HttpHandler
  ): Observable<HttpEvent<any>> {
    // add authorization header with jwt token if available
    let token = this.tokenStorage.getToken();
    if (token) {
      //console.log(token)
      const cloneReq = request.clone({
        headers: request.headers.set('Authorization', `Bearer ${token}`)
      })

      return next.handle(cloneReq);
    }

    return next.handle(request.clone());
  }

  isTokenExpired = (token: string): boolean => {
    const decoded: any = jwtDecode(token);
    return decoded.exp * 1000 < Date.now();
  }
}

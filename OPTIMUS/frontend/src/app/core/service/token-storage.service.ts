import { Injectable } from '@angular/core';

const TOKEN_KEY = 'auth-token';
const REFRESH_TOKEN_KEY = 'refresh-token';
const USER_KEY = 'auth-user';

@Injectable({
  providedIn: 'root',
})
export class TokenStorageService {
  constructor() {}

  // ---- TOKEN IN COOKIES ----
  private setCookie(name: string, value: string, days = 1): void {
    const expires = new Date();
    expires.setDate(expires.getDate() + days);
    document.cookie = `${name}=${encodeURIComponent(value)};expires=${expires.toUTCString()};path=/;SameSite=Lax`;
  }

  private getCookie(name: string): string | null {
    const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
    return match ? decodeURIComponent(match[2]) : null;
  }

  private deleteCookie(name: string): void {
    document.cookie = `${name}=;expires=Thu, 01 Jan 1970 00:00:00 UTC;path=/;`;
  }

  // ---- USER IN SESSION STORAGE ----
  public saveUser(user: any): void {
    sessionStorage.removeItem(USER_KEY);
    sessionStorage.setItem(USER_KEY, JSON.stringify(user));
  }

  public getUser(): any {
    const user = sessionStorage.getItem(USER_KEY);
    if (user) {
      return JSON.parse(user);
    }
    return null;
  }

  public saveToken(token: string, refreshToken: string): void {
    this.setCookie(TOKEN_KEY, token);
    this.setCookie(REFRESH_TOKEN_KEY, refreshToken);
  }

  public getToken(): string | null {
    return this.getCookie(TOKEN_KEY);
  }

  public getRefreshToken(): string | null {
    return this.getCookie(REFRESH_TOKEN_KEY);
  }

  signOut(): void {
    sessionStorage.clear();
    this.deleteCookie(TOKEN_KEY);
    this.deleteCookie(REFRESH_TOKEN_KEY);
  }
}

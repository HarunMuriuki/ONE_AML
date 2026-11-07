import { Injectable } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { Observable } from "rxjs";
import { environment } from "src/environments/environment";

/**
 * Client for the rules API (backend :9700). rules.json holds the discovered
 * Oracle rules (keys, joins, indexes...) grouped under titles.
 */
@Injectable({ providedIn: "root" })
export class RulesApiService {
  private api = environment.apiUrl + "/api";

  constructor(private http: HttpClient) {}

  /** The current rules catalog (rules.json). */
  rules(): Observable<any> {
    return this.http.get(this.api + "/rules");
  }

  /** Tables configured for scanning. */
  tables(): Observable<any> {
    return this.http.get(this.api + "/rules/tables");
  }

  /** Re-query Oracle's data dictionary for ALL rules and rewrite rules.json. */
  refresh(tables?: string): Observable<any> {
    const params: any = {};
    if (tables) params.tables = tables;
    return this.http.post(this.api + "/rules/refresh", {}, { params });
  }

  /** Re-run ONE rule (e.g. "aliases") and update only that group. */
  refreshGroup(id: string): Observable<any> {
    return this.http.post(this.api + "/rules/groups/" + id + "/refresh", {});
  }
}

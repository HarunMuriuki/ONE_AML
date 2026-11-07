import { Injectable } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { Observable } from "rxjs";
import { environment } from "src/environments/environment";

@Injectable({ providedIn: "root" })
export class GraphApiService {
  private api = environment.apiUrl + "/api";

  constructor(private http: HttpClient) {}

  entities(): Observable<any> {
    return this.http.get(this.api + "/entities");
  }

  sql(entity: string): Observable<any> {
    return this.http.get(this.api + "/sql", { params: { entity } });
  }

  preview(entity: string, limit: number): Observable<any> {
    return this.http.get(this.api + "/preview", { params: { entity, limit } });
  }

  sync(entity: string, limit: number): Observable<any> {
    return this.http.post(`${this.api}/sync?entity=${entity}&limit=${limit}`, {});
  }

  stats(): Observable<any> {
    return this.http.get(this.api + "/stats");
  }

  cypher(query: string): Observable<any> {
    return this.http.post(this.api + "/cypher", { query });
  }

  status(): Observable<any> {
    return this.http.get(this.api + "/status");
  }

  alerts(limit: number): Observable<any> {
    return this.http.get(this.api + "/alerts", { params: { limit } });
  }

  score(txn: any): Observable<any> {
    return this.http.post(this.api + "/events/transaction", txn);
  }
}

import { Injectable } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { Observable } from "rxjs";
import { environment } from "src/environments/environment";

/**
 * Thin client for the model + pipelines API (backend :9700).
 * Everything here is data: the static model (schema.json) and the
 * editable mapping (pipelines.json). No mapping is hardcoded in the UI.
 */
@Injectable({ providedIn: "root" })
export class ModelApiService {
  private api = environment.apiUrl + "/api";

  constructor(private http: HttpClient) {}

  /** Static model (schema.json) verbatim. */
  model(): Observable<any> {
    return this.http.get(this.api + "/model");
  }

  /** Completeness: every schema node/relationship must have a pipeline slot. */
  coverage(): Observable<any> {
    return this.http.get(this.api + "/model/coverage");
  }

  /** One editable slot per node (shipped + user-defined). */
  nodes(includeDeleted = false): Observable<any[]> {
    return this.http.get<any[]>(this.api + "/pipelines/nodes", { params: { includeDeleted } });
  }

  /** One editable slot per relationship (shipped + user-defined). */
  relationships(includeDeleted = false): Observable<any[]> {
    return this.http.get<any[]>(this.api + "/pipelines/relationships", { params: { includeDeleted } });
  }

  node(id: string): Observable<any> {
    return this.http.get(this.api + `/pipelines/nodes/${id}`);
  }

  relationship(id: string): Observable<any> {
    return this.http.get(this.api + `/pipelines/relationships/${id}`);
  }

  /** Generated MERGE Cypher for a saved node mapping. */
  nodeCypher(id: string): Observable<any> {
    return this.http.get(this.api + `/pipelines/nodes/${id}/cypher`);
  }

  /** Generated MERGE Cypher for a saved relationship mapping. */
  relationshipCypher(id: string): Observable<any> {
    return this.http.get(this.api + `/pipelines/relationships/${id}/cypher`);
  }

  /**
   * Column aliases a SELECT yields, so the property map can be rebuilt from the
   * query instead of drifting away from it.
   */
  columns(select: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/columns`, { select });
  }

  /** Run ONE window of this node slot (next rowsPerRun rows) -> MERGE into Neo4j. */
  runNode(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/nodes/${id}/run`, {});
  }

  /** Run this node slot to completion (one streaming pass, cursor left at last key). */
  runNodeAll(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/nodes/${id}/run/all`, {});
  }

  /** Rewind this node slot's paging cursor to the start. */
  resetNode(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/nodes/${id}/reset`, {});
  }

  /** Run ONE window of this relationship slot (endpoints must already exist). */
  runRelationship(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/relationships/${id}/run`, {});
  }

  /** Run this relationship slot to completion (one streaming pass, cursor left at last key). */
  runRelationshipAll(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/relationships/${id}/run/all`, {});
  }

  /** Rewind this relationship slot's paging cursor to the start. */
  resetRelationship(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/relationships/${id}/reset`, {});
  }

  /** Runtime settings (config.json): how many rows one Run pulls. */
  config(): Observable<any> {
    return this.http.get(this.api + "/config");
  }

  saveConfig(cfg: any): Observable<any> {
    return this.http.put(this.api + "/config", cfg);
  }

  /** Per-slot paging cursors (sync-state.json) - for progress display. */
  syncState(): Observable<any> {
    return this.http.get(this.api + "/sync/state");
  }

  /** Background sync jobs (newest first) - re-attach after a page refresh. */
  jobs(): Observable<any[]> {
    return this.http.get<any[]>(this.api + "/jobs");
  }

  /** One job's live status. */
  job(id: string): Observable<any> {
    return this.http.get(this.api + `/jobs/${id}`);
  }

  /** Ask a running job to stop at the next batch boundary. */
  cancelJob(id: string): Observable<any> {
    return this.http.post(this.api + `/jobs/${id}/cancel`, {});
  }

  /** The uniqueness constraints the model needs (for index-backed MERGE). */
  indexes(): Observable<any> {
    return this.http.get(this.api + "/indexes");
  }

  /** Create the uniqueness constraints. Idempotent. */
  applyIndexes(): Observable<any> {
    return this.http.post(this.api + "/indexes/apply", {});
  }

  /** Recent audit-trail entries (audit.jsonl), newest first. */
  audit(limit = 200): Observable<any[]> {
    return this.http.get<any[]>(this.api + "/audit", { params: { limit } });
  }

  saveNode(id: string, body: any): Observable<any> {
    return this.http.put(this.api + `/pipelines/nodes/${id}`, body);
  }

  saveRelationship(id: string, body: any): Observable<any> {
    return this.http.put(this.api + `/pipelines/relationships/${id}`, body);
  }

  /** Create a user-defined node (persisted in extensions.json, starts as a template). */
  createNode(body: any): Observable<any> {
    return this.http.post(this.api + "/pipelines/nodes", body);
  }

  /** Create a user-defined relationship (endpoints must be existing node labels). */
  createRelationship(body: any): Observable<any> {
    return this.http.post(this.api + "/pipelines/relationships", body);
  }

  /** Soft-delete any node slot (delFlag=Y; graph data is not touched). */
  deleteNode(id: string): Observable<any> {
    return this.http.delete(this.api + `/pipelines/nodes/${id}`);
  }

  /** Soft-delete any relationship slot. */
  deleteRelationship(id: string): Observable<any> {
    return this.http.delete(this.api + `/pipelines/relationships/${id}`);
  }

  /** Restore a soft-deleted node slot (comes back disabled). */
  restoreNode(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/nodes/${id}/restore`, {});
  }

  /** Restore a soft-deleted relationship slot (comes back disabled). */
  restoreRelationship(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/relationships/${id}/restore`, {});
  }

  /** How much graph data this node slot owns (nodes + relationships touching them). */
  nodeData(id: string): Observable<any> {
    return this.http.get(this.api + `/pipelines/nodes/${id}/data`);
  }

  /** How many edges of this type between these endpoints exist. */
  relationshipData(id: string): Observable<any> {
    return this.http.get(this.api + `/pipelines/relationships/${id}/data`);
  }

  /** Start a background job: DETACH DELETE this node label's graph data. */
  detachDeleteNode(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/nodes/${id}/detach-delete`, {});
  }

  /** Start a background job: DELETE this relationship type's edges (both endpoints). */
  detachDeleteRelationship(id: string): Observable<any> {
    return this.http.post(this.api + `/pipelines/relationships/${id}/detach-delete`, {});
  }

  /** Re-read schema.json + pipelines.json from disk (after external edits). */
  reload(): Observable<any> {
    return this.http.post(this.api + "/pipelines/reload", {});
  }
}

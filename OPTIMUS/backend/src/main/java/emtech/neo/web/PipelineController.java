package one.neo.web;

import one.neo.audit.AuditService;
import one.neo.graph.CypherBuilder;
import one.neo.graph.GraphDataService;
import one.neo.graph.GraphIndexService;
import one.neo.graph.PipelineRunner;
import one.neo.jobs.JobService;
import one.neo.model.NodePipeline;
import one.neo.model.PipelineStore;
import one.neo.model.RelPipeline;
import one.neo.model.RunConfig;
import one.neo.model.RunConfigStore;
import one.neo.model.SyncStateStore;
import one.neo.oracle.OracleService;
import one.neo.sql.SqlColumns;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The flexible mapping API: the shipped model + the editable pipelines + user
 * extensions. Every shipped node/relationship has exactly one slot in
 * pipelines.json; extensions.json holds user-defined additions.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Model & pipelines (schema.json + pipelines.json + extensions.json)")
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);

    private final PipelineStore store;
    private final PipelineRunner runner;
    private final RunConfigStore config;
    private final SyncStateStore state;
    private final AuditService audit;
    private final JobService jobs;
    private final GraphIndexService indexes;
    private final GraphDataService graphData;
    private final OracleService oracle;

    public PipelineController(PipelineStore store, PipelineRunner runner, RunConfigStore config,
                              SyncStateStore state, AuditService audit, JobService jobs,
                              GraphIndexService indexes, GraphDataService graphData,
                              OracleService oracle) {
        this.store = store;
        this.runner = runner;
        this.config = config;
        this.state = state;
        this.audit = audit;
        this.jobs = jobs;
        this.indexes = indexes;
        this.graphData = graphData;
        this.oracle = oracle;
    }

    @GetMapping("/model")
    @Operation(summary = "The static model (schema.json verbatim)")
    public ResponseEntity<?> model() {
        return ResponseEntity.ok(store.schema());
    }

    @GetMapping("/model/coverage")
    @Operation(summary = "Proves every schema node/relationship has a pipeline slot")
    public ResponseEntity<?> coverage() {
        return ResponseEntity.ok(store.coverage());
    }

    @GetMapping("/pipelines")
    @Operation(summary = "The whole editable pipeline set (nodes + relationships)")
    public ResponseEntity<?> pipelines() {
        return ResponseEntity.ok(store.pipelines());
    }

    @PostMapping("/pipelines/reload")
    @Operation(summary = "Re-read schema.json + pipelines.json + extensions.json from disk")
    public ResponseEntity<?> reload() {
        store.reload();
        return ResponseEntity.ok(Map.of("ok", true, "nodes", store.pipelines().getNodes().size(),
                "relationships", store.pipelines().getRelationships().size(),
                "customNodes", store.coverage().customNodes(),
                "customRelationships", store.coverage().customRelationships()));
    }

    @GetMapping("/pipelines/nodes")
    @Operation(summary = "Node slots (shipped + user-defined); soft-deleted ones with includeDeleted=true")
    public ResponseEntity<?> nodes(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        return ResponseEntity.ok(store.nodes(includeDeleted));
    }

    @PostMapping("/pipelines/nodes")
    @Operation(summary = "Create a user-defined node from a template (persisted in extensions.json)")
    public ResponseEntity<?> createNode(@RequestBody NodePipeline body) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(store.createNode(body));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    @DeleteMapping("/pipelines/nodes/{id}")
    @Operation(summary = "Soft-delete any node slot (delFlag=Y; graph data is not touched)")
    public ResponseEntity<?> deleteNode(@PathVariable String id) {
        try {
            return ResponseEntity.ok(store.deleteNode(id));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    @PostMapping("/pipelines/nodes/{id}/restore")
    @Operation(summary = "Restore a soft-deleted node slot (comes back disabled)")
    public ResponseEntity<?> restoreNode(@PathVariable String id) {
        try {
            return ResponseEntity.ok(store.restoreNode(id));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    @GetMapping("/pipelines/nodes/{id}/data")
    @Operation(summary = "How much graph data this node slot owns (nodes + relationships touching them)")
    public ResponseEntity<?> nodeData(@PathVariable String id) {
        NodePipeline p = store.nodeSlot(id);
        if (p == null) return notFound("node pipeline " + id);
        try {
            GraphDataService.DataCount c = graphData.countNode(p.getLabel());
            return ResponseEntity.ok(Map.of(
                    "id", id, "label", p.getLabel(),
                    "nodes", c.nodes(), "relationships", c.relationships(),
                    "deleted", p.deleted()));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    @PostMapping("/pipelines/nodes/{id}/detach-delete")
    @Operation(summary = "Start a background job: DETACH DELETE the label's data (mapping is kept)")
    public ResponseEntity<?> detachDeleteNode(@PathVariable String id) {
        if (store.nodeSlot(id) == null) return notFound("node pipeline " + id);
        return ResponseEntity.accepted().body(jobs.submit("node", id, "detach-delete"));
    }

    @GetMapping("/pipelines/nodes/{id}")
    public ResponseEntity<?> node(@PathVariable String id) {
        NodePipeline p = findNode(id);
        return p == null ? notFound("node pipeline " + id) : ResponseEntity.ok(p);
    }

    @GetMapping("/pipelines/nodes/{id}/cypher")
    @Operation(summary = "Generated MERGE Cypher for this node mapping")
    public ResponseEntity<?> nodeCypher(@PathVariable String id) {
        NodePipeline p = findNode(id);
        if (p == null) return notFound("node pipeline " + id);
        try {
            return ResponseEntity.ok(Map.of("id", id, "cypher", CypherBuilder.forNode(p)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/pipelines/columns")
    @Operation(summary = "Column aliases a SELECT yields, so the property map can follow the query")
    public ResponseEntity<?> columns(@RequestBody Map<String, Object> body) {
        String sql = body == null ? null : body.get("select") == null ? null : String.valueOf(body.get("select"));
        try {
            SqlColumns.Columns c = SqlColumns.resolve(sql, () -> oracle.describe(sql));
            return ResponseEntity.ok(Map.of("columns", c.columns(), "source", c.source(),
                    "warning", c.warning() == null ? "" : c.warning()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    @PostMapping("/pipelines/nodes/{id}/run")
    @Operation(summary = "Start a background job: pull the NEXT keyset window -> MERGE. Returns a job id.")
    public ResponseEntity<?> runNode(@PathVariable String id) {
        if (findNode(id) == null) return notFound("node pipeline " + id);
        return ResponseEntity.accepted().body(jobs.submit("node", id, "run"));
    }

    @PostMapping("/pipelines/nodes/{id}/run/all")
    @Operation(summary = "Start a background job: stream the whole node slot in one pass. Returns a job id.")
    public ResponseEntity<?> runNodeAll(@PathVariable String id) {
        if (findNode(id) == null) return notFound("node pipeline " + id);
        return ResponseEntity.accepted().body(jobs.submit("node", id, "run-all"));
    }

    @PostMapping("/pipelines/nodes/{id}/reset")
    @Operation(summary = "Rewind this node slot's paging cursor to the start")
    public ResponseEntity<?> resetNode(@PathVariable String id) {
        return guard(() -> runner.resetNode(id));
    }

    @PostMapping("/pipelines/relationships/{id}/run")
    @Operation(summary = "Start a background job: NEXT relationship window -> MERGE (endpoints must exist)")
    public ResponseEntity<?> runRelationship(@PathVariable String id) {
        if (findRel(id) == null) return notFound("relationship pipeline " + id);
        return ResponseEntity.accepted().body(jobs.submit("rel", id, "run"));
    }

    @PostMapping("/pipelines/relationships/{id}/run/all")
    @Operation(summary = "Start a background job: stream the whole relationship slot in one pass")
    public ResponseEntity<?> runRelationshipAll(@PathVariable String id) {
        if (findRel(id) == null) return notFound("relationship pipeline " + id);
        return ResponseEntity.accepted().body(jobs.submit("rel", id, "run-all"));
    }

    @PostMapping("/pipelines/relationships/{id}/reset")
    @Operation(summary = "Rewind this relationship slot's paging cursor to the start")
    public ResponseEntity<?> resetRelationship(@PathVariable String id) {
        return guard(() -> runner.resetRelationship(id));
    }

    @GetMapping("/config")
    @Operation(summary = "Runtime settings (config.json): rows per Run")
    public ResponseEntity<?> getConfig() {
        return ResponseEntity.ok(config.get());
    }

    @PutMapping("/config")
    @Operation(summary = "Save runtime settings (config.json)")
    public ResponseEntity<?> saveConfig(@RequestBody RunConfig body) {
        return ResponseEntity.ok(config.save(body));
    }

    @GetMapping("/sync/state")
    @Operation(summary = "Per-slot paging cursors (sync-state.json)")
    public ResponseEntity<?> syncState() {
        return ResponseEntity.ok(state.snapshot());
    }

    @GetMapping("/audit")
    @Operation(summary = "Recent audit-trail entries (audit.jsonl), newest first")
    public ResponseEntity<?> audit(@RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(audit.tail(limit));
    }

    @GetMapping("/jobs")
    @Operation(summary = "Background sync jobs (newest first) - re-attach after a page refresh")
    public ResponseEntity<?> jobs() {
        return ResponseEntity.ok(jobs.list());
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "One background sync job's live status")
    public ResponseEntity<?> job(@PathVariable String id) {
        var job = jobs.get(id);
        return job == null ? notFound("job " + id) : ResponseEntity.ok(job);
    }

    @PostMapping("/jobs/{id}/cancel")
    @Operation(summary = "Ask a running job to stop at the next batch boundary")
    public ResponseEntity<?> cancelJob(@PathVariable String id) {
        try {
            return ResponseEntity.ok(jobs.cancel(id));
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        }
    }

    @GetMapping("/indexes")
    @Operation(summary = "The uniqueness constraints the model needs (from pipelines.json keys)")
    public ResponseEntity<?> indexes() {
        return ResponseEntity.ok(Map.of("statements", indexes.statements()));
    }

    @PostMapping("/indexes/apply")
    @Operation(summary = "Create the uniqueness constraints (index-backed MERGE). Idempotent.")
    public ResponseEntity<?> applyIndexes() {
        return ResponseEntity.ok(indexes.apply());
    }

    @PutMapping("/pipelines/nodes/{id}")
    @Operation(summary = "Save an edited node mapping (SELECT + key + column map)")
    public ResponseEntity<?> updateNode(@PathVariable String id, @RequestBody NodePipeline body) {
        try {
            return ResponseEntity.ok(store.updateNode(id, body));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    @GetMapping("/pipelines/relationships")
    @Operation(summary = "Relationship slots (shipped + user-defined); soft-deleted ones with includeDeleted=true")
    public ResponseEntity<?> relationships(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        return ResponseEntity.ok(store.relationships(includeDeleted));
    }

    @PostMapping("/pipelines/relationships")
    @Operation(summary = "Create a user-defined relationship between two existing nodes")
    public ResponseEntity<?> createRelationship(@RequestBody RelPipeline body) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(store.createRelationship(body));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    @DeleteMapping("/pipelines/relationships/{id}")
    @Operation(summary = "Soft-delete any relationship slot (delFlag=Y; graph data is not touched)")
    public ResponseEntity<?> deleteRelationship(@PathVariable String id) {
        try {
            return ResponseEntity.ok(store.deleteRelationship(id));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    @PostMapping("/pipelines/relationships/{id}/restore")
    @Operation(summary = "Restore a soft-deleted relationship slot (comes back disabled)")
    public ResponseEntity<?> restoreRelationship(@PathVariable String id) {
        try {
            return ResponseEntity.ok(store.restoreRelationship(id));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    @GetMapping("/pipelines/relationships/{id}/data")
    @Operation(summary = "How many edges of exactly this type between exactly these endpoints exist")
    public ResponseEntity<?> relationshipData(@PathVariable String id) {
        RelPipeline p = store.relSlot(id);
        if (p == null) return notFound("relationship pipeline " + id);
        if (p.getFrom() == null || p.getTo() == null) {
            return badRequest("relationship " + id + " has no endpoints");
        }
        try {
            long rels = graphData.countRelationships(p.getType(), p.getFrom().getLabel(), p.getTo().getLabel());
            return ResponseEntity.ok(Map.of(
                    "id", id, "type", p.getType(),
                    "from", p.getFrom().getLabel(), "to", p.getTo().getLabel(),
                    "nodes", 0L, "relationships", rels,
                    "deleted", p.deleted()));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    @PostMapping("/pipelines/relationships/{id}/detach-delete")
    @Operation(summary = "Start a background job: DELETE this type's edges between these endpoints (mapping is kept)")
    public ResponseEntity<?> detachDeleteRelationship(@PathVariable String id) {
        if (store.relSlot(id) == null) return notFound("relationship pipeline " + id);
        return ResponseEntity.accepted().body(jobs.submit("rel", id, "detach-delete"));
    }

    @GetMapping("/pipelines/relationships/{id}")
    public ResponseEntity<?> relationship(@PathVariable String id) {
        RelPipeline p = findRel(id);
        return p == null ? notFound("relationship pipeline " + id) : ResponseEntity.ok(p);
    }

    @GetMapping("/pipelines/relationships/{id}/cypher")
    @Operation(summary = "Generated MERGE Cypher for this relationship mapping")
    public ResponseEntity<?> relCypher(@PathVariable String id) {
        RelPipeline p = findRel(id);
        if (p == null) return notFound("relationship pipeline " + id);
        try {
            return ResponseEntity.ok(Map.of("id", id, "cypher", CypherBuilder.forRelationship(p)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/pipelines/relationships/{id}")
    @Operation(summary = "Save an edited relationship mapping")
    public ResponseEntity<?> updateRel(@PathVariable String id, @RequestBody RelPipeline body) {
        try {
            return ResponseEntity.ok(store.updateRelationship(id, body));
        } catch (IllegalArgumentException e) {
            return mappingError(e);
        }
    }

    private NodePipeline findNode(String id) {
        List<NodePipeline> nodes = store.pipelines().getNodes();
        return nodes.stream().filter(n -> id.equals(n.getId())).findFirst().orElse(null);
    }

    private RelPipeline findRel(String id) {
        List<RelPipeline> rels = store.pipelines().getRelationships();
        return rels.stream().filter(r -> id.equals(r.getId())).findFirst().orElse(null);
    }

    private ResponseEntity<Map<String, Object>> notFound(String msg) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", msg));
    }

    private ResponseEntity<?> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** Unknown ids are 404; validation problems are 400. */
    private ResponseEntity<?> mappingError(IllegalArgumentException e) {
        String msg = e.getMessage() == null ? "bad request" : e.getMessage();
        return msg.startsWith("unknown") ? notFound(msg) : badRequest(msg);
    }

    private ResponseEntity<?> guard(java.util.function.Supplier<?> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException e) {
            log.warn("pipeline request rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("pipeline request failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }
}

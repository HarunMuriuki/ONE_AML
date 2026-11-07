package one.neo.web;

import one.neo.config.GraphProperties;
import one.neo.graph.GraphModel;
import one.neo.graph.ScoringService;
import one.neo.graph.SyncService;
import one.neo.oracle.DatabaseConnection;
import one.neo.oracle.OracleSelectors;
import one.neo.oracle.OracleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole NEO API: Oracle selectors, ETL sync, Cypher runner,
 * stats and realtime scoring. No auth (added later).
 */
@RestController
@RequestMapping("/api")
@Tag(name = "NEO (Oracle -> Neo4j AML pipeline)")
public class NeoController {

    private static final Logger log = LoggerFactory.getLogger(NeoController.class);

    private final OracleService oracle;
    private final SyncService sync;
    private final ScoringService scoring;
    private final GraphProperties props;
    private final DatabaseConnection dbConnection;
    private final Driver driver;

    public NeoController(OracleService oracle, SyncService sync, ScoringService scoring,
                         GraphProperties props, DatabaseConnection dbConnection, Driver driver) {
        this.oracle = oracle;
        this.sync = sync;
        this.scoring = scoring;
        this.props = props;
        this.dbConnection = dbConnection;
        this.driver = driver;
    }

    @GetMapping("/entities")
    @Operation(summary = "Selectable entities, their Oracle source and graph model")
    public ResponseEntity<?> entities() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String e : OracleSelectors.entities()) {
            out.add(Map.of("entity", e,
                    "source", OracleSelectors.SOURCE.get(e),
                    "cypher", GraphModel.cypher(e)));
        }
        return ResponseEntity.ok(Map.of("entities", out));
    }

    @GetMapping("/sql")
    @Operation(summary = "Raw Oracle SQL for an entity (paste into your SQL client)")
    public ResponseEntity<?> sql(@RequestParam(defaultValue = "Account") String entity) {
        if (!OracleSelectors.known(entity)) return bad("unknown entity " + entity);
        return ResponseEntity.ok(Map.of("entity", entity,
                "source", OracleSelectors.SOURCE.get(entity), "sql", OracleSelectors.SQL.get(entity)));
    }

    @GetMapping("/preview")
    @Operation(summary = "Run selector with row cap; 503 when Oracle is down (never mock)")
    public ResponseEntity<?> preview(@RequestParam(defaultValue = "Account") String entity,
                                     @RequestParam(defaultValue = "10") int limit) {
        if (!props.getSync().isEnabled()) return off();
        try {
            var rows = oracle.preview(entity, limit);
            return ResponseEntity.ok(Map.of("entity", entity, "count", rows.size(), "rows", rows,
                    "sql", OracleSelectors.SQL.get(entity)));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("preview failed", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/sync")
    @Operation(summary = "Oracle selector -> Neo4j MERGE (idempotent, batched, GraphModel-driven)")
    public ResponseEntity<?> sync(@RequestParam(defaultValue = "Account") String entity,
                                  @RequestParam(defaultValue = "500") int limit) {
        if (!props.getSync().isEnabled()) return off();
        try {
            long started = System.currentTimeMillis();
            var rows = oracle.preview(entity, limit);
            var push = sync.push(entity, rows);
            return ResponseEntity.ok(Map.of("entity", entity, "selected", rows.size(),
                    "pushed", push.rows(), "created", push.created(),
                    "cypher", GraphModel.cypher(entity),
                    "ms", System.currentTimeMillis() - started));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("sync failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/cypher")
    @Operation(summary = "Run any Cypher; returns rows + graph {nodes, edges} for viz")
    public ResponseEntity<?> cypher(@RequestBody Map<String, Object> body) {
        String q = body == null ? null : String.valueOf(body.get("query"));
        if (q == null || q.isBlank()) return bad("missing query");
        try {
            return ResponseEntity.ok(sync.cypher(q));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/stats")
    @Operation(summary = "Neo4j label counts (live)")
    public ResponseEntity<?> stats() {
        try {
            return ResponseEntity.ok(Map.of("stats", sync.stats()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/events/transaction")
    @Operation(summary = "Realtime scoring: post one transaction, get risk 0-100 + rules")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> score(@RequestBody Map<String, Object> txn) {
        if (txn == null || txn.isEmpty()) return bad("empty transaction");
        var scored = scoring.score(new LinkedHashMap<>(txn));
        int risk = ((Number) scored.get("riskScore")).intValue();
        if (risk >= 40) {
            Map<String, Object> alert = new LinkedHashMap<>(scored);
            alert.put("tranId", String.valueOf(txn.getOrDefault("tranId", "?")));
            alert.put("rule", String.join(",", (List<String>) scored.get("rules")));
            sync.writeAlert(alert);
        }
        return ResponseEntity.ok(scored);
    }

    @GetMapping("/alerts")
    @Operation(summary = "Recent alerts (in-memory + persisted copies on :Alert/:Transaction in Neo4j)")
    public ResponseEntity<?> alerts(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(Map.of("alerts", scoring.recentAlerts(limit)));
    }

    @GetMapping("/status")
    @Operation(summary = "Liveness of both links: Oracle + Neo4j")
    public ResponseEntity<?> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean oracleOk = false;
        try (var c = dbConnection.dbConnection()) {
            oracleOk = c != null && c.isValid(5);
        } catch (Exception ignored) { }
        boolean neoOk = false;
        try {
            driver.verifyConnectivity();
            neoOk = true;
        } catch (Exception ignored) { }
        out.put("oracle", oracleOk ? "UP" : "DOWN");
        out.put("neo4j", neoOk ? "UP" : "DOWN");
        out.put("syncEnabled", props.getSync().isEnabled());
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private ResponseEntity<Map<String, Object>> off() {
        return ResponseEntity.status(503).body(Map.of("error", "graph sync disabled"));
    }
}

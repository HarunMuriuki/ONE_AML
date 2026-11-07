package one.neo.graph;

import one.neo.audit.AuditService;
import one.neo.jobs.JobReporter;
import one.neo.model.NodePipeline;
import one.neo.model.PipelineStore;
import one.neo.model.RelPipeline;
import one.neo.model.RunConfigStore;
import one.neo.model.SyncStateStore;
import one.neo.oracle.OracleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs pipeline slots as an incremental sync service.
 *
 * Each "Run" pulls the NEXT slice of rows for a slot using KEYSET (seek) paging
 * ordered by the slot's key columns - O(total) across all clicks, never the old
 * O(n^2) ROWNUM re-scan. "Run all" streams the whole slot in one pass (one Oracle
 * connection, JDBC fetch size, batched Neo4j pushes) and leaves the cursor at the
 * last key, so later "Run" clicks only pick up new rows. "Reset" rewinds a slot.
 * Nodes must be filled before the relationships that reference them.
 *
 * Every action is logged (SLF4J) AND appended to the audit trail (audit.jsonl)
 * with the SQL/Cypher used, so success and failure are both reconstructable.
 */
@Service
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final PipelineStore store;
    private final OracleService oracle;
    private final SyncService sync;
    private final RunConfigStore config;
    private final SyncStateStore state;
    private final AuditService audit;

    public PipelineRunner(PipelineStore store, OracleService oracle, SyncService sync,
                          RunConfigStore config, SyncStateStore state, AuditService audit) {
        this.store = store;
        this.oracle = oracle;
        this.sync = sync;
        this.config = config;
        this.state = state;
        this.audit = audit;
    }

    public Map<String, Object> runNode(String id) {
        return runNode(id, JobReporter.NOOP);
    }

    public Map<String, Object> runNode(String id, JobReporter rep) {
        NodePipeline p = findNode(id);
        return runSlice("node", id, "node " + p.getLabel(),
                p.getSelect(), () -> CypherBuilder.forNode(p), nodeKeyAliases(p), rep);
    }

    public Map<String, Object> runRelationship(String id) {
        return runRelationship(id, JobReporter.NOOP);
    }

    public Map<String, Object> runRelationship(String id, JobReporter rep) {
        RelPipeline p = findRel(id);
        return runSlice("rel", id, "relationship " + p.getType(),
                p.getSelect(), () -> CypherBuilder.forRelationship(p), relKeyAliases(p), rep);
    }

    public Map<String, Object> runNodeAll(String id) {
        return runNodeAll(id, JobReporter.NOOP);
    }

    public Map<String, Object> runNodeAll(String id, JobReporter rep) {
        NodePipeline p = findNode(id);
        return runAll("node", id, "node " + p.getLabel(),
                p.getSelect(), () -> CypherBuilder.forNode(p), nodeKeyAliases(p), rep);
    }

    public Map<String, Object> runRelationshipAll(String id) {
        return runRelationshipAll(id, JobReporter.NOOP);
    }

    public Map<String, Object> runRelationshipAll(String id, JobReporter rep) {
        RelPipeline p = findRel(id);
        return runAll("rel", id, "relationship " + p.getType(),
                p.getSelect(), () -> CypherBuilder.forRelationship(p), relKeyAliases(p), rep);
    }

    public Map<String, Object> resetNode(String id) {
        return reset("node", id);
    }

    public Map<String, Object> resetRelationship(String id) {
        return reset("rel", id);
    }

    private NodePipeline findNode(String id) {
        return store.pipelines().getNodes().stream()
                .filter(n -> id.equals(n.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown node pipeline " + id));
    }

    private RelPipeline findRel(String id) {
        return store.pipelines().getRelationships().stream()
                .filter(r -> id.equals(r.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown relationship pipeline " + id));
    }

    private Map<String, Object> runSlice(String kind, String id, String target, String select,
                                         Supplier<String> cypher, List<String> keyAliases, JobReporter rep) {
        String slot = slot(kind, id);
        long started = System.currentTimeMillis();
        try {
            String stmt = cypher.get();
            String base = strip(select, target);
            int rows = Math.max(1, config.rowsPerRun());

            if (rep.cancelled()) throw new CancelledException("cancelled before " + slot);

            SyncStateStore.Cursor cur = state.cursor(slot);
            List<Object> lastKey = (cur != null && cur.hasKey()) ? cur.getLastKey() : null;

            List<Map<String, Object>> data = oracle.selectKeyset(base, keyAliases, lastKey, rows);
            SyncService.PushResult push = sync.pushCypher(stmt, data);
            long pushed = push.rows();
            long created = push.created();

            boolean done = data.isEmpty() || data.size() < rows;
            List<Object> nextKey = lastKey;
            if (data.isEmpty()) {
                state.markDone(slot); // exhausted: keep lastKey so later Runs only fetch new rows
            } else {
                nextKey = keyValues(data.get(data.size() - 1), keyAliases);
                state.advance(slot, nextKey, data.size());
                if (done) state.markDone(slot);
            }
            rep.tick(data.size(), pushed, created, done);

            long ms = System.currentTimeMillis() - started;
            log.info("run {}: +{} rows ({} pushed, {} NEW, done={}, {} ms)",
                    slot, data.size(), pushed, created, done, ms);

            Map<String, Object> out = fields();
            out.put("kind", kind);
            out.put("slot", slot);
            out.put("action", "run");
            out.put("target", target);
            out.put("selected", data.size());
            out.put("pushed", pushed);
            out.put("created", created);
            out.put("count", state.count(slot));
            out.put("done", done);
            out.put("nextKey", nextKey);
            out.put("ms", ms);
            out.put("ok", true);
            out.put("sql", base);
            out.put("cypher", stmt);
            audit.record(out);
            return out;
        } catch (CancelledException ce) {
            throw ce;
        } catch (RuntimeException ex) {
            throw failure(kind, slot, target, select, started, ex);
        }
    }

    private Map<String, Object> runAll(String kind, String id, String target, String select,
                                       Supplier<String> cypher, List<String> keyAliases, JobReporter rep) {
        String slot = slot(kind, id);
        long started = System.currentTimeMillis();
        try {
            String stmt = cypher.get();
            String base = strip(select, target);
            int rows = Math.max(1, config.rowsPerRun());
            int batch = Math.max(5000, rows);
            int fetch = Math.max(20000, batch);

            long[] selected = {0};
            long[] pushed = {0};
            long[] created = {0};
            long[] batches = {0};
            List<Object> lastKey = new ArrayList<>();
            oracle.stream(base, fetch, batch, data -> {
                if (rep.cancelled()) throw new CancelledException("cancelled during " + slot);
                batches[0]++;
                selected[0] += data.size();
                SyncService.PushResult pr = sync.pushCypher(stmt, data);
                pushed[0] += pr.rows();
                created[0] += pr.created();
                if (!data.isEmpty()) lastKey.clear();
                if (!data.isEmpty()) lastKey.addAll(keyValues(data.get(data.size() - 1), keyAliases));
                if (data.size() < batch) {
                    log.info("run-all {}: last batch {} < {}, stream converged", slot, data.size(), batch);
                }
                rep.tick(selected[0], pushed[0], created[0], false);
            });

            state.advance(slot, lastKey, (int) Math.min(selected[0], Integer.MAX_VALUE));
            state.markDone(slot);
            rep.tick(selected[0], pushed[0], created[0], true);

            long ms = System.currentTimeMillis() - started;
            boolean done = true;
            log.info("run-all {}: {} batch(es), {} rows, {} pushed, {} NEW, done={} ({} ms)",
                    slot, batches[0], selected[0], pushed[0], created[0], done, ms);

            Map<String, Object> out = fields();
            out.put("kind", kind);
            out.put("slot", slot);
            out.put("action", "run-all");
            out.put("target", target);
            out.put("runs", batches[0]);
            out.put("selected", selected[0]);
            out.put("pushed", pushed[0]);
            out.put("created", created[0]);
            out.put("count", state.count(slot));
            out.put("done", done);
            out.put("nextKey", lastKey);
            out.put("ms", ms);
            out.put("ok", true);
            out.put("sql", base);
            out.put("cypher", stmt);
            audit.record(out);
            return out;
        } catch (CancelledException ce) {
            throw ce;
        } catch (RuntimeException ex) {
            throw failure(kind, slot, target, select, started, ex);
        }
    }

    private Map<String, Object> reset(String kind, String id) {
        String slot = slot(kind, id);
        state.reset(slot);
        log.info("reset {}: cursor rewound, count=0", slot);
        Map<String, Object> e = fields();
        e.put("kind", kind);
        e.put("slot", slot);
        e.put("action", "reset");
        e.put("count", 0);
        e.put("done", false);
        e.put("ok", true);
        audit.record(e);
        return e;
    }

    private RuntimeException failure(String kind, String slot, String target, String select,
                                     long started, RuntimeException ex) {
        long ms = System.currentTimeMillis() - started;
        log.warn("run {} FAILED after {} ms: {}", slot, ms, ex.getMessage());
        Map<String, Object> e = fields();
        e.put("kind", kind);
        e.put("slot", slot);
        e.put("action", "run");
        e.put("target", target);
        e.put("ms", ms);
        e.put("ok", false);
        e.put("error", String.valueOf(ex.getMessage()));
        e.put("sql", select == null ? "" : select);
        audit.record(e);
        return ex;
    }

    private static Map<String, Object> fields() {
        return new LinkedHashMap<>();
    }

    private static String slot(String kind, String id) {
        return kind + ":" + id;
    }

    /** The key aliases as they appear in the SELECT's result columns, in seek order. */
    private static List<String> nodeKeyAliases(NodePipeline p) {
        List<String> out = new ArrayList<>();
        for (String prop : p.getKey()) {
            String col = p.getMap().get(prop);
            if (col == null || col.isBlank()) {
                throw new IllegalArgumentException("key property '" + prop + "' of " + p.getLabel() + " has no column mapping");
            }
            out.add(col);
        }
        return out;
    }

    private static List<String> relKeyAliases(RelPipeline p) {
        List<String> out = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : p.getFrom().getKey().entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) missing.add("from." + e.getKey());
            else out.add(e.getValue());
        }
        for (Map.Entry<String, String> e : p.getTo().getKey().entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) missing.add("to." + e.getKey());
            else out.add(e.getValue());
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("relationship " + p.getType() + " has unmapped key columns: " + missing);
        }
        return out;
    }

    private static List<Object> keyValues(Map<String, Object> row, List<String> aliases) {
        List<Object> out = new ArrayList<>(aliases.size());
        for (String a : aliases) out.add(row.get(a));
        return out;
    }

    private static String strip(String select, String target) {
        if (select == null || select.isBlank()) {
            throw new IllegalArgumentException(target + " has no SELECT");
        }
        if (select.toUpperCase().contains("PLACEHOLDER")) {
            throw new IllegalArgumentException(target + " is still a placeholder - set a real SELECT first");
        }
        String base = stripRowLimit(select);
        if (base.indexOf('?') >= 0) {
            throw new IllegalArgumentException(target
                    + " SELECT still contains a bind placeholder - remove 'ROWNUM <= ?' / '?SVAR' so paging can work");
        }
        return base;
    }

    /**
     * Remove a trailing row cap from a slot SELECT so the runner owns paging:
     *   "... WHERE ... AND ROWNUM <= ?"  -> "... WHERE ..."
     *   "... WHERE ROWNUM <= ?"          -> "..."
     */
    static String stripRowLimit(String sql) {
        String s = sql == null ? "" : sql;
        s = s.replaceAll("(?is)\\s+AND\\s+ROWNUM\\s*<=\\s*\\?", "");
        s = s.replaceAll("(?is)\\s+WHERE\\s+ROWNUM\\s*<=\\s*\\?(\\s*)$", "$1");
        return s.trim();
    }
}
package one.neo.graph;

import one.neo.config.GraphProperties;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle rows -> Neo4j MERGE, generated from GraphModel (the ETL contract).
 * Idempotent (re-runnable), batched UNWIND, one write tx per batch.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final Driver driver;
    private final GraphProperties props;

    public SyncService(Driver driver, GraphProperties props) {
        this.driver = driver;
        this.props = props;
    }

    /** What a push actually did: rows sent, and how many nodes/relationships were genuinely CREATED. */
    public record PushResult(long rows, long nodesCreated, long relsCreated, long propsSet) {
        public static PushResult empty() { return new PushResult(0, 0, 0, 0); }

        public long created() { return nodesCreated + relsCreated; }

        public PushResult plus(PushResult o) {
            return new PushResult(rows + o.rows, nodesCreated + o.nodesCreated,
                    relsCreated + o.relsCreated, propsSet + o.propsSet);
        }
    }

    public PushResult push(String entity, List<Map<String, Object>> rows) {
        if (!GraphModel.known(entity)) throw new IllegalArgumentException("unknown entity " + entity);
        return pushCypher(GraphModel.cypher(entity), rows, entity);
    }

    /**
     * Run a generated MERGE statement (from CypherBuilder) over the given rows,
     * batched with UNWIND and one write tx per batch. Idempotent, re-runnable.
     * Counts come from Neo4j's own summary counters, so "created" is the real
     * number of NEW nodes/relationships - a re-run of existing rows creates 0.
     */
    public PushResult pushCypher(String stmt, List<Map<String, Object>> rows) {
        return pushCypher(stmt, rows, "pipeline");
    }

    private PushResult pushCypher(String stmt, List<Map<String, Object>> rows, String label) {
        if (stmt == null || stmt.isBlank()) throw new IllegalArgumentException("empty statement");
        if (rows == null || rows.isEmpty()) return PushResult.empty();
        int batch = Math.max(100, props.getSync().getBatchSize());
        PushResult total = PushResult.empty();
        for (int i = 0; i < rows.size(); i += batch) {
            List<Map<String, Object>> slice = rows.subList(i, Math.min(i + batch, rows.size()));
            final long[][] c = new long[1][3];
            try (Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
                session.executeWrite(tx -> {
                    var counters = tx.run(stmt, Values.parameters("rows", sanitize(slice))).consume().counters();
                    c[0][0] = counters.nodesCreated();
                    c[0][1] = counters.relationshipsCreated();
                    c[0][2] = counters.propertiesSet();
                    return null;
                });
                total = total.plus(new PushResult(slice.size(), c[0][0], c[0][1], c[0][2]));
                log.info("graph push {} batch {}/{} ({} rows, {} new)",
                        label, (i / batch) + 1, (rows.size() + batch - 1) / batch, slice.size(),
                        c[0][0] + c[0][1]);
            }
        }
        return total;
    }

    /** Oracle JDBC types (BigDecimal, sql.Date/Timestamp/Time) the driver can't take. */
    private static List<Map<String, Object>> sanitize(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r.size());
            for (Map.Entry<String, Object> e : r.entrySet()) {
                m.put(e.getKey(), coerce(e.getValue()));
            }
            out.add(m);
        }
        return out;
    }

    private static Object coerce(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd.doubleValue();
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant().toString();
        if (v instanceof java.sql.Date d) return d.toString();
        if (v instanceof java.sql.Time t) return t.toString();
        if (v instanceof Number || v instanceof CharSequence || v instanceof Boolean) return v;
        return String.valueOf(v);
    }

    /** Run an arbitrary Cypher statement. Read queries return rows; writes return empty. */
    public Map<String, Object> cypher(String query) {
        boolean readOnly = looksReadOnly(query);
        try (Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            if (readOnly) {
                return session.executeRead(tx -> {
                    var result = tx.run(query);
                    List<String> columns = new ArrayList<>(result.keys());
                    List<Object> nodes = new ArrayList<>();
                    List<Object> edges = new ArrayList<>();
                    List<List<Object>> rows = new ArrayList<>();
                    Map<Long, Boolean> seenNodes = new java.util.LinkedHashMap<>();
                    Map<Long, Boolean> seenEdges = new java.util.LinkedHashMap<>();
                    while (result.hasNext() && rows.size() < 500) {
                        var rec = result.next();
                        List<Object> row = new ArrayList<>();
                        for (String col : columns) {
                            Object v = rec.get(col).asObject();
                            if (v instanceof org.neo4j.driver.types.Node n) {
                                if (seenNodes.putIfAbsent(n.id(), Boolean.TRUE) == null) {
                                    nodes.add(Map.of("id", n.id(), "labels", n.labels(), "props", n.asMap()));
                                }
                                row.add("#" + n.id());
                            } else if (v instanceof org.neo4j.driver.types.Relationship r) {
                                if (seenEdges.putIfAbsent(r.id(), Boolean.TRUE) == null) {
                                    edges.add(Map.of("id", r.id(), "type", r.type(),
                                            "from", r.startNodeId(), "to", r.endNodeId(), "props", r.asMap()));
                                }
                                row.add("-[" + r.type() + " " + r.id() + "]->");
                            } else {
                                row.add(v);
                            }
                        }
                        rows.add(row);
                    }
                    Map<String, Object> out = new java.util.LinkedHashMap<>();
                    out.put("columns", columns);
                    out.put("rows", rows);
                    out.put("nodes", nodes);
                    out.put("edges", edges);
                    out.put("truncated", rows.size() >= 500);
                    return out;
                });
            } else {
                session.executeWrite(tx -> tx.run(query).consume());
                return Map.of("ok", true, "statement", "executed");
            }
        }
    }

    private static boolean looksReadOnly(String q) {
        String s = q == null ? "" : q.trim().toLowerCase();
        return s.startsWith("match") || s.startsWith("call") || s.startsWith("show")
                || s.startsWith("return") || s.startsWith("use") || s.startsWith("explain")
                || s.startsWith("describe") || s.startsWith("profile");
    }

    /** Save a scored alert back to the graph for investigators. */
    public void writeAlert(Map<String, Object> alert) {
        try (Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            session.executeWrite(tx -> {
                tx.run(
                        "MERGE (t:Transaction {tranId: $tranId}) " +
                        "SET t.riskScore = $risk, t.lastAlert = $rule, t.lastAlertAt = timestamp()",
                        Values.parameters("tranId", String.valueOf(alert.getOrDefault("tranId", "?")),
                                "risk", alert.getOrDefault("riskScore", 0),
                                "rule", String.valueOf(alert.getOrDefault("rule", "?")))).consume();
                return null;
            });
        } catch (Exception e) {
            log.warn("writeAlert failed: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> stats() {
        try (Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            return session.executeRead(tx -> {
                var result = tx.run("MATCH (n) RETURN labels(n)[0] AS label, count(*) AS c ORDER BY c DESC");
                List<Map<String, Object>> out = new ArrayList<>();
                while (result.hasNext()) {
                    var r = result.next();
                    out.add(Map.of("label", r.get("label").asString(), "count", r.get("c").asLong()));
                }
                return out;
            });
        }
    }
}

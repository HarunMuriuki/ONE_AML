package one.neo.graph;

import one.neo.model.NodePipeline;
import one.neo.model.PipelineStore;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Creates the Neo4j uniqueness constraints that back every node slot's MERGE.
 *
 * Without a constraint on the merge key, Neo4j cannot use an index and every
 * MERGE degrades into a full label scan (O(rows x existing nodes)) - the reason a
 * slow sync gets slower as the graph grows. Constraints are derived from
 * pipelines.json (not schema.json) so the index exactly matches the MERGE pattern,
 * including composite keys. Idempotent: "IF NOT EXISTS", safe to re-run.
 */
@Service
public class GraphIndexService {

    private static final Logger log = LoggerFactory.getLogger(GraphIndexService.class);

    private final Driver driver;
    private final PipelineStore store;

    public GraphIndexService(Driver driver, PipelineStore store) {
        this.driver = driver;
        this.store = store;
    }

    /** The CREATE CONSTRAINT statements this model needs, one per keyed node slot. */
    public List<String> statements() {
        List<String> out = new ArrayList<>();
        for (NodePipeline n : store.pipelines().getNodes()) {
            if (n.getLabel() == null || n.getLabel().isBlank()) continue;
            if (n.getKey() == null || n.getKey().isEmpty()) continue;
            boolean mapped = true;
            for (String k : n.getKey()) {
                if (n.getMap().get(k) == null || n.getMap().get(k).isBlank()) mapped = false;
            }
            if (!mapped) continue;
            String name = "neo_" + sanitize(n.getLabel()) + "_"
                    + n.getKey().stream().map(GraphIndexService::sanitize).collect(Collectors.joining("_"));
            String props = n.getKey().stream().map(k -> "n." + k).collect(Collectors.joining(", "));
            out.add("CREATE CONSTRAINT " + name + " IF NOT EXISTS FOR (n:" + n.getLabel()
                    + ") REQUIRE (" + props + ") IS UNIQUE");
        }
        return out;
    }

    /** Constraint names this service owns (everything it creates starts with this). */
    public static final String PREFIX = "neo_";

    /** The constraint names the current pipelines.json declares. */
    public List<String> desiredNames() {
        List<String> out = new ArrayList<>();
        for (NodePipeline n : store.pipelines().getNodes()) {
            if (n.getLabel() == null || n.getLabel().isBlank()) continue;
            if (n.getKey() == null || n.getKey().isEmpty()) continue;
            boolean mapped = true;
            for (String k : n.getKey()) {
                if (n.getMap().get(k) == null || n.getMap().get(k).isBlank()) mapped = false;
            }
            if (!mapped) continue;
            out.add(PREFIX + sanitize(n.getLabel()) + "_"
                    + n.getKey().stream().map(GraphIndexService::sanitize).collect(Collectors.joining("_")));
        }
        return out;
    }

    /** Existing constraints this service owns, as they are really named in Neo4j. */
    private List<String> existingOwnedNames(Session session) {
        List<String> out = new ArrayList<>();
        var result = session.run("SHOW CONSTRAINTS YIELD name RETURN name");
        while (result.hasNext()) {
            String name = result.next().get("name").asString();
            if (name.startsWith(PREFIX)) out.add(name);
        }
        return out;
    }

    /**
     * RECONCILE the constraints with the model: drop constraints this service
     * created that pipelines.json no longer declares, then create the ones it does.
     *
     * Without the drop step, editing a node's key leaves the OLD constraint
     * behind. Two overlapping uniqueness constraints then make MERGE fail with
     * "did not find a matching node ... cannot create a new node due to
     * conflicts with existing unique nodes", because the lookup uses the new
     * key while creation is blocked by the old one.
     *
     * Only names starting with {@link #PREFIX} are ever dropped - constraints
     * created by hand are left alone.
     */
    public Map<String, Object> apply() {
        List<String> created = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> all = statements();
        try (Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            java.util.Set<String> desired = new java.util.LinkedHashSet<>(desiredNames());
            for (String existing : existingOwnedNames(session)) {
                if (desired.contains(existing)) continue;
                String drop = "DROP CONSTRAINT `" + existing + "` IF EXISTS";
                try {
                    session.run(drop).consume();
                    dropped.add(existing);
                    log.info("graph indexes: dropped obsolete constraint {}", existing);
                } catch (Exception e) {
                    errors.add(drop + "  ->  " + e.getMessage());
                }
            }
            for (String stmt : all) {
                try {
                    session.run(stmt).consume();
                    created.add(stmt);
                } catch (Exception e) {
                    errors.add(stmt + "  ->  " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("Neo4j unavailable: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created.size());
        out.put("dropped", dropped);
        out.put("failed", errors.size());
        out.put("statements", created);
        out.put("errors", errors);
        log.info("graph indexes: {} created, {} dropped, {} failed",
                created.size(), dropped.size(), errors.size());
        return out;
    }

    /** Best-effort ensure used before a sync: never blocks a run on index problems. */
    public void ensure() {
        try {
            Map<String, Object> r = apply();
            Object failed = r.get("failed");
            if (failed instanceof Integer f && f > 0) {
                log.warn("graph indexes: {} statement(s) failed (sync continues, but MERGE may be slow): {}",
                        f, r.get("errors"));
            }
            Object dropped = r.get("dropped");
            if (dropped instanceof List<?> d && !d.isEmpty()) {
                log.info("graph indexes: reconciled - dropped obsolete constraints {}", d);
            }
        } catch (Exception e) {
            log.warn("graph index ensure failed (sync continues): {}", e.getMessage());
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "_");
    }
}
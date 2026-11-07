package one.neo.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Deletes GRAPH DATA - the other half of deleting a model slot.
 *
 * Deliberately separate from {@link one.neo.model.PipelineStore}, which only
 * edits the mapping (SELECT/key/map in pipelines.json + extensions.json). Deleting
 * a slot hides a mapping; this service is what actually removes nodes and
 * relationships from Neo4j, and it is always an explicit, confirmed action.
 *
 * Scope is intentionally narrow and predictable:
 *   node         -> every node with that label, and every relationship touching it
 *                   (including edges written by other slots - DETACH DELETE cannot
 *                   leave dangling relationships)
 *   relationship -> only edges of that type between those two labels
 *
 * Both run batched through CALL { ... } IN TRANSACTIONS, so a large label does not
 * build one enormous transaction. Batches are internal to Neo4j, so the caller
 * gets the totals before/after rather than live progress.
 */
@Service
public class GraphDataService {

    private static final Logger log = LoggerFactory.getLogger(GraphDataService.class);

    private static final String DATABASE = "neo4j";

    /** Same shape the model validator allows for labels/relationship types. */
    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private static final int NODE_BATCH = 10_000;
    private static final int REL_BATCH = 50_000;

    private final Driver driver;

    public GraphDataService(Driver driver) {
        this.driver = driver;
    }

    /** What a label/relationship currently holds; also what a detach delete removes. */
    public record DataCount(long nodes, long relationships) {}

    /** Nodes with this label + every relationship touching any of them. */
    public DataCount countNode(String label) {
        String q = "MATCH (n:`" + ident(label, "node label") + "`) "
                + "OPTIONAL MATCH (n)-[r]-() "
                + "RETURN count(DISTINCT n) AS nodes, count(DISTINCT r) AS relationships";
        try (Session session = session()) {
            Record row = session.run(q).single();
            return new DataCount(row.get("nodes").asLong(0), row.get("relationships").asLong(0));
        }
    }

    /** Edges of exactly this type between exactly these two labels. */
    public long countRelationships(String type, String from, String to) {
        String q = "MATCH (a:`" + ident(from, "from endpoint") + "`)"
                + "-[r:`" + ident(type, "relationship type") + "`]->"
                + "(b:`" + ident(to, "to endpoint") + "`) "
                + "RETURN count(r) AS relationships";
        try (Session session = session()) {
            return session.run(q).single().get("relationships").asLong(0);
        }
    }

    /**
     * DETACH DELETE every node with this label, in batches.
     * Returns what was actually removed (before minus after), not just what was
     * targeted, so the report cannot overstate the delete.
     */
    public DataCount detachDeleteNode(String label) {
        String quoted = ident(label, "node label");
        DataCount before = countNode(quoted);
        runBatched("MATCH (n:`" + quoted + "`) DETACH DELETE n", NODE_BATCH);
        DataCount after = countNode(quoted);
        DataCount removed = new DataCount(
                before.nodes() - after.nodes(),
                before.relationships() - after.relationships());
        log.info("detach delete {}: {} nodes, {} relationships removed", label, removed.nodes(), removed.relationships());
        return removed;
    }

    /**
     * DELETE the edges of this type between these two labels, in batches.
     * A same-named type between other labels is untouched.
     */
    public long detachDeleteRelationships(String type, String from, String to) {
        String t = ident(type, "relationship type");
        String f = ident(from, "from endpoint");
        String o = ident(to, "to endpoint");
        long before = countRelationships(t, f, o);
        runBatched("MATCH (a:`" + f + "`)-[r:`" + t + "`]->(b:`" + o + "`) DELETE r", REL_BATCH);
        long after = countRelationships(t, f, o);
        long removed = Math.max(0, before - after);
        log.info("detach delete {} {} -> {}: {} relationships removed", t, f, o, removed);
        return removed;
    }

    private void runBatched(String inner, int batch) {
        String query = "CALL { " + inner + " } IN TRANSACTIONS OF " + batch + " ROWS";
        try (Session session = session()) {
            session.run(query).consume();
        }
    }

    private Session session() {
        return driver.session(SessionConfig.forDatabase(DATABASE));
    }

    /**
     * Identifiers are interpolated into Cypher (Neo4j cannot bind labels/types as
     * parameters), so they are validated rather than escaped. Backticks are not in
     * the allowed set, which also rules out breaking out of the quoting.
     */
    private static String ident(String value, String what) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must be a plain Neo4j identifier, got: " + value);
        }
        return value;
    }
}

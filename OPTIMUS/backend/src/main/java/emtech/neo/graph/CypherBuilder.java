package one.neo.graph;

import one.neo.model.NodePipeline;
import one.neo.model.RelEndpoint;
import one.neo.model.RelPipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates null-guarded, idempotent MERGE Cypher from editable pipelines.
 * Key properties are matched with toString() (and skipped when null); non-key
 * properties are SET raw so numbers/dates keep their type.
 */
public final class CypherBuilder {

    private CypherBuilder() {}

    public static String forNode(NodePipeline n) {
        require(n.getLabel() != null && !n.getLabel().isBlank(), "node label required for pipeline " + n.getId());
        String custom = n.getCypher();
        if (custom != null && !custom.isBlank()) return custom.trim();
        Map<String, String> keyCols = keyColumns(n.getKey(), n.getMap(), "node " + n.getLabel());

        StringBuilder sb = new StringBuilder("UNWIND $rows AS r\n");
        sb.append("WITH r WHERE ").append(notNull(keyCols)).append('\n');
        sb.append("MERGE (n:").append(n.getLabel()).append(' ').append(pattern(keyCols)).append(')');
        for (Map.Entry<String, String> e : n.getMap().entrySet()) {
            if (n.getKey().contains(e.getKey())) continue;
            sb.append("\nSET n.").append(e.getKey()).append(" = r.").append(e.getValue());
        }
        return sb.toString().trim();
    }

    public static String forRelationship(RelPipeline rel) {
        require(rel.getType() != null && !rel.getType().isBlank(), "rel type required for pipeline " + rel.getId());
        String custom = rel.getCypher();
        if (custom != null && !custom.isBlank()) return custom.trim();
        Map<String, String> fromCols = endpointColumns(rel.getFrom(), rel.getId());
        Map<String, String> toCols = endpointColumns(rel.getTo(), rel.getId());

        StringBuilder sb = new StringBuilder("UNWIND $rows AS r\n");
        sb.append("MATCH (a:").append(rel.getFrom().getLabel()).append(' ').append(pattern(fromCols)).append(")\n");
        sb.append("MATCH (b:").append(rel.getTo().getLabel()).append(' ').append(pattern(toCols)).append(")\n");
        boolean hasProps = !rel.getMap().isEmpty();
        sb.append("MERGE (a)-[").append(hasProps ? "rel" : "").append(':').append(rel.getType()).append("]->(b)");
        if (hasProps) {
            for (Map.Entry<String, String> e : rel.getMap().entrySet()) {
                sb.append("\nSET rel.").append(e.getKey()).append(" = r.").append(e.getValue());
            }
        }
        return sb.toString().trim();
    }

    private static Map<String, String> keyColumns(Iterable<String> keys, Map<String, String> map, String what) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            String col = map.get(k);
            require(col != null, what + " has key property '" + k + "' with no column in map");
            out.put(k, col);
        }
        require(!out.isEmpty(), what + " has no key properties defined");
        return out;
    }

    private static Map<String, String> endpointColumns(RelEndpoint e, String relId) {
        require(e != null && e.getLabel() != null, "relationship " + relId + " endpoint missing label");
        require(e.getKey() != null && !e.getKey().isEmpty(),
                "relationship " + relId + " endpoint " + e.getLabel() + " has no key mapping");
        return e.getKey();
    }

    private static String pattern(Map<String, String> keyCols) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : keyCols.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(": toString(r.").append(e.getValue()).append(')');
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String notNull(Map<String, String> keyCols) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String col : keyCols.values()) {
            if (!first) sb.append(" AND ");
            sb.append("r.").append(col).append(" IS NOT NULL");
            first = false;
        }
        return sb.toString();
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new IllegalArgumentException(msg);
    }
}

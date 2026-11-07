package one.neo.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ==================== THE ETL CONTRACT ====================
 * This is THE place you create your own nodes ("notes") and relationships.
 * Per entity:
 *   - nodes: what labels get MERGEd from a row, keyed by a row field,
 *            with property mappings (graphProp -> rowField).
 *   - rels:  MERGE (from)-[:TYPE]->(to). Endpoint with matchExisting=true
 *            uses MATCH instead (row is skipped if the node isn't there yet).
 *
 * SyncService generates one UNWIND $rows / MERGE Cypher from these specs -
 * edit an entry here, resync, done. No Cypher wiring elsewhere.
 *
 * Row fields are whatever the Oracle selector aliases (lowercased) - see
 * OracleSelectors. Extend a selector + add its fields here.
 */
public final class GraphModel {

    private GraphModel() {}

    /** Node: MERGE (n:label {key: toString(r.keyField)}) SET n.prop = r.field ... */
    public record NodeSpec(String label, String keyField, Map<String, String> props) {
        public static NodeSpec of(String label, String keyField, Map<String, String> props) {
            return new NodeSpec(label, keyField, props == null ? Map.of() : props);
        }
    }

    /** Rel endpoint: which node variable (label-keyed) this side refers to. */
    public record Endpoint(String label, String keyField, boolean matchExisting) {}

    /** Rel: MERGE (from)-[:type]->(to) */
    public record RelSpec(String type, Endpoint from, Endpoint to) {}

    public static Endpoint merge(String label, String keyField) { return new Endpoint(label, keyField, false); }
    public static Endpoint match(String label, String keyField) { return new Endpoint(label, keyField, true); }

    public record EntitySpec(String entity, List<NodeSpec> nodes, List<RelSpec> rels) {}

    public static final Map<String, EntitySpec> MODEL = new LinkedHashMap<>();

    static {
        MODEL.put("Customer", new EntitySpec("Customer", List.of(
                NodeSpec.of("Customer", "cifid", Map.of(
                        "firstname", "firstname",
                        "lastname", "lastname",
                        "dob", "dob",
                        "nationality", "nationality",
                        "occupation", "occupation"))
        ), List.of()));

        MODEL.put("Branch", new EntitySpec("Branch", List.of(
                NodeSpec.of("Branch", "solid", Map.of(
                        "name", "name",
                        "bankId", "bankid"))
        ), List.of()));

        MODEL.put("SchemeCode", new EntitySpec("SchemeCode", List.of(
                NodeSpec.of("SchemeCode", "code", Map.of("descr", "descr"))
        ), List.of()));

        MODEL.put("SchemeType", new EntitySpec("SchemeType", List.of(
                NodeSpec.of("SchemeType", "type", Map.of("descr", "descr"))
        ), List.of()));

        MODEL.put("Account", new EntitySpec("Account", List.of(
                NodeSpec.of("Customer", "cifid", Map.of()),
                NodeSpec.of("Account", "acid", Map.of(
                        "accountNum", "accountnum",
                        "name", "name",
                        "currency", "currency",
                        "balance", "balance")),
                NodeSpec.of("Branch", "solid", Map.of()),
                NodeSpec.of("SchemeCode", "schemecode", Map.of()),
                NodeSpec.of("SchemeType", "schemetype", Map.of())
        ), List.of(
                new RelSpec("OWNS", merge("Customer", "cifid"), merge("Account", "acid")),
                new RelSpec("LOCATED_AT", merge("Account", "acid"), merge("Branch", "solid")),
                new RelSpec("USES", merge("Account", "acid"), merge("SchemeCode", "schemecode")),
                new RelSpec("CLASSIFIED_AS", merge("Account", "acid"), merge("SchemeType", "schemetype"))
        )));

        MODEL.put("Transaction", new EntitySpec("Transaction", List.of(
                NodeSpec.of("Transaction", "tranid", Map.of(
                        "tranDate", "trandate",
                        "amount", "amount",
                        "currency", "currency",
                        "tranType", "trantype",
                        "tranSubType", "transubtype"))
        ), List.of(
                new RelSpec("HAS_TRANSACTION", match("Account", "acid"), merge("Transaction", "tranid"))
        )));
    }

    public static boolean known(String entity) { return MODEL.containsKey(entity); }

    /**
     * Generates the batched idempotent Cypher for an entity:
     *   UNWIND $rows AS r
     *   MERGE (n0:Label {key: toString(r.field)}) SET ...   (per node spec)
     *   MATCH  (nK:Label {key: toString(r.field)})          (per matchExisting endpoint)
     *   MERGE  (nA)-[:TYPE]->(nB)                           (per rel)
     */
    public static String cypher(String entity) {
        EntitySpec spec = MODEL.get(entity);
        if (spec == null) throw new IllegalArgumentException("unknown entity " + entity);

        Map<String, String> varByLabel = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("UNWIND $rows AS r\n");
        int[] seq = {0};

        for (NodeSpec n : spec.nodes()) {
            String v = varByLabel.computeIfAbsent(n.label(), k -> "n" + (seq[0]++));
            sb.append("MERGE (").append(v).append(':').append(n.label())
              .append(" {").append(keyProp(n.label())).append(": toString(r.").append(n.keyField()).append(")})");
            if (!n.props().isEmpty()) {
                sb.append("\nSET ");
                boolean first = true;
                for (Map.Entry<String, String> p : n.props().entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(v).append('.').append(p.getKey()).append(" = r.").append(p.getValue());
                    first = false;
                }
            }
            sb.append("\n");
        }

        for (RelSpec rel : spec.rels()) {
            for (Endpoint e : List.of(rel.from(), rel.to())) {
                if (varByLabel.containsKey(e.label())) continue;
                String v = varByLabel.computeIfAbsent(e.label(), k -> "n" + (seq[0]++));
                sb.append(e.matchExisting() ? "MATCH (" : "MERGE (")
                  .append(v).append(':').append(e.label())
                  .append(" {").append(keyProp(e.label())).append(": toString(r.").append(e.keyField()).append(")})\n");
            }
        }

        for (RelSpec rel : spec.rels()) {
            sb.append("MERGE (").append(varByLabel.get(rel.from().label()))
              .append(")-[:").append(rel.type()).append("]->(")
              .append(varByLabel.get(rel.to().label())).append(")\n");
        }

        return sb.toString().trim();
    }

    /** The identity property name per label (single-key graph identity). */
    private static String keyProp(String label) {
        return switch (label) {
            case "Customer" -> "cifId";
            case "Account" -> "acid";
            case "Branch" -> "solId";
            case "SchemeCode" -> "code";
            case "SchemeType" -> "type";
            case "Transaction" -> "tranId";
            default -> "id";
        };
    }
}

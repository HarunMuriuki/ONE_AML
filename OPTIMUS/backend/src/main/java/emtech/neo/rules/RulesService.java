package one.neo.rules;

import one.neo.oracle.OracleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the "rules" catalog (keys, joins, indexes, columns...) by querying
 * Oracle's data dictionary, and persists it to rules.json via RulesStore.
 *
 * The scanned tables come from rules.tables (OWNER.TABLE, comma-separated).
 * Add a table there (or pass ?tables= to refresh) and hit refresh - the page
 * then shows the new table's rules.
 */
@Service
public class RulesService {

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_$#]+$");

    private final OracleService oracle;
    private final RulesStore store;
    private final List<TableRef> tables;
    private final int maxRows;

    public RulesService(OracleService oracle, RulesStore store,
                        @Value("${rules.tables:TBAADM.GAM,TBAADM.SMT,TBAADM.SOL,TBAADM.GSM,TBAADM.HTD,TBAADM.DTD,TBAADM.CTD_DTD_ACLI_VIEW,CRMUSER.ACCOUNTS,CRMUSER.DEMOGRAPHIC}") String tablesCsv,
                        @Value("${rules.max-rows:2000}") int maxRows) {
        this.oracle = oracle;
        this.store = store;
        this.tables = parseTables(tablesCsv);
        this.maxRows = maxRows;
    }

    public Map<String, Object> current() { return store.catalog(); }

    public List<String> configuredTables() {
        return tables.stream().map(TableRef::qualified).toList();
    }

    /** One rule: its id/title plus the SQL builder that reads it from the dictionary. */
    @FunctionalInterface
    interface SqlFn { String apply(Collection<String> owners, Collection<String> names); }

    private record GroupDef(String id, String title, String description, SqlFn sql) {}

    private List<GroupDef> definitions() {
        return List.of(
                new GroupDef("aliases", "Aliases (synonyms)",
                        "Finacle short codes are synonyms of the real (long) table names.", RulesService::aliasSql),
                new GroupDef("primary_keys", "Primary Keys & Unique Keys",
                        "Declared identity of each table (composite keys included).", RulesService::primaryKeySql),
                new GroupDef("joins_declared", "Declared Joins (Foreign Keys)",
                        "Foreign-key relationships declared in the dictionary.", RulesService::foreignKeySql),
                new GroupDef("joins_candidate", "Candidate Joins (shared columns)",
                        "Column names appearing in more than one scanned table -> candidate join keys (Finacle often has no FKs).",
                        RulesService::candidateJoinsSql),
                new GroupDef("indexes", "Indexes",
                        "Indexes and their uniqueness (effective identifiers even without a PK).", RulesService::indexSql),
                new GroupDef("columns", "Columns",
                        "Every column of the scanned tables.", RulesService::columnSql),
                new GroupDef("table_sizes", "Table Sizes (statistics)",
                        "Approximate row counts from optimizer statistics.", RulesService::sizeSql));
    }

    public List<String> ruleIds() {
        return definitions().stream().map(GroupDef::id).toList();
    }

    /** Run every dictionary query, assemble the grouped catalog, save rules.json. */
    public Map<String, Object> refresh(List<TableRef> override) {
        List<TableRef> scope = (override == null || override.isEmpty()) ? tables : override;
        List<String> owners = ownersOf(scope);
        List<String> names = namesOf(scope);

        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("generatedAt", Instant.now().toString());
        cat.put("source", "Oracle - data dictionary (ALL_* views)");
        cat.put("tables", scope.stream().map(TableRef::qualified).toList());

        List<Map<String, Object>> groups = new ArrayList<>();
        for (GroupDef d : definitions()) groups.add(group(d, owners, names));
        cat.put("groups", groups);

        store.save(cat);
        return cat;
    }

    public Map<String, Object> refresh() {
        return refresh(null);
    }

    /**
     * Re-run ONE rule (group) against Oracle and update just that group in
     * rules.json - every other group is left untouched. This is the per-rule
     * "select it and run that" path.
     */
    public Map<String, Object> refreshGroup(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("missing rule id");
        GroupDef def = definitions().stream()
                .filter(d -> d.id().equalsIgnoreCase(id.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown rule: " + id));

        List<TableRef> scope = scopeFromCatalog();
        Map<String, Object> updated = group(def, ownersOf(scope), namesOf(scope));

        Map<String, Object> cat = new LinkedHashMap<>(store.catalog());
        cat.put("generatedAt", Instant.now().toString());
        cat.putIfAbsent("source", "Oracle - data dictionary (ALL_* views)");
        cat.putIfAbsent("tables", scope.stream().map(TableRef::qualified).toList());

        List<Map<String, Object>> groups = new ArrayList<>();
        if (cat.get("groups") instanceof List<?> existing) {
            for (Object o : existing) {
                if (o instanceof Map<?, ?> m) groups.add(new LinkedHashMap<>(cast(m)));
            }
        }
        boolean replaced = false;
        for (int i = 0; i < groups.size(); i++) {
            if (def.id().equals(groups.get(i).get("id"))) { groups.set(i, updated); replaced = true; break; }
        }
        if (!replaced) groups.add(updated);
        cat.put("groups", groups);

        store.save(cat);
        return cat;
    }

    // ---- group + query construction ------------------------------------

    private Map<String, Object> group(GroupDef def, List<String> owners, List<String> names) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", def.id());
        g.put("title", def.title());
        g.put("description", def.description());
        try {
            OracleService.Result r = oracle.query(def.sql().apply(owners, names), maxRows);
            g.put("columns", r.columns());
            g.put("rows", r.rows());
            g.put("ok", true);
        } catch (Exception e) {
            g.put("columns", List.of());
            g.put("rows", List.of());
            g.put("ok", false);
            g.put("error", String.valueOf(e.getMessage()));
        }
        return g;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private List<TableRef> scopeFromCatalog() {
        if (store.catalog().get("tables") instanceof List<?> t && !t.isEmpty()) {
            List<TableRef> parsed = parseTables(t.stream().map(String::valueOf).collect(Collectors.joining(",")));
            if (!parsed.isEmpty()) return parsed;
        }
        return tables;
    }

    private static List<String> ownersOf(List<TableRef> scope) {
        List<String> owners = scope.stream().map(TableRef::owner).filter(Objects::nonNull).distinct().toList();
        owners.forEach(RulesService::validate);
        return owners;
    }

    private static List<String> namesOf(List<TableRef> scope) {
        List<String> names = scope.stream().map(TableRef::name).distinct().toList();
        names.forEach(RulesService::validate);
        return names;
    }

    static String aliasSql(Collection<String> owners, Collection<String> names) {
        List<String> parts = new ArrayList<>();
        if (names != null && !names.isEmpty()) {
            String in = inList(names);
            parts.add("(synonym_name IN (" + in + ") OR table_name IN (" + in + "))");
        }
        if (owners != null && !owners.isEmpty()) {
            String in = inList(owners);
            parts.add("(owner IN (" + in + ") OR table_owner IN (" + in + "))");
        }
        String where = parts.isEmpty() ? "1=1" : String.join(" AND ", parts);
        return "SELECT owner, synonym_name, table_owner, table_name AS real_table, db_link "
                + "FROM all_synonyms WHERE " + where + " ORDER BY owner, synonym_name";
    }

    static String primaryKeySql(Collection<String> owners, Collection<String> names) {
        return "SELECT c.owner, c.table_name, c.constraint_name, c.constraint_type, "
                + "cc.column_name, cc.position "
                + "FROM all_constraints c "
                + "JOIN all_cons_columns cc ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name "
                + "WHERE c.constraint_type IN ('P','U') " + tableFilter("c", owners, names) + " "
                + "ORDER BY c.table_name, c.constraint_name, cc.position";
    }

    static String foreignKeySql(Collection<String> owners, Collection<String> names) {
        return "SELECT c.table_name, c.constraint_name, cc.column_name AS fk_column, "
                + "c.r_owner, r.table_name AS ref_table, rcc.column_name AS ref_column, cc.position "
                + "FROM all_constraints c "
                + "JOIN all_cons_columns cc ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name "
                + "JOIN all_constraints r ON r.owner = c.r_owner AND r.constraint_name = c.r_constraint_name "
                + "JOIN all_cons_columns rcc ON rcc.owner = r.owner AND rcc.constraint_name = r.constraint_name "
                + "AND rcc.position = cc.position "
                + "WHERE c.constraint_type = 'R' " + tableFilter("c", owners, names) + " "
                + "ORDER BY c.table_name, c.constraint_name, cc.position";
    }

    static String candidateJoinsSql(Collection<String> owners, Collection<String> names) {
        return "SELECT column_name, COUNT(*) AS table_count, "
                + "LISTAGG(owner || '.' || table_name, ', ') WITHIN GROUP (ORDER BY owner, table_name) AS tables "
                + "FROM all_tab_columns "
                + "WHERE " + filters(owners, names) + " "
                + "GROUP BY column_name HAVING COUNT(*) > 1 "
                + "ORDER BY table_count DESC, column_name";
    }

    static String indexSql(Collection<String> owners, Collection<String> names) {
        return "SELECT i.table_owner, i.table_name, i.index_name, i.uniqueness, "
                + "ic.column_position, ic.column_name "
                + "FROM all_indexes i "
                + "JOIN all_ind_columns ic ON ic.index_owner = i.owner AND ic.index_name = i.index_name "
                + "WHERE " + filters(owners, names) + " "
                + "ORDER BY i.table_name, i.index_name, ic.column_position";
    }

    static String columnSql(Collection<String> owners, Collection<String> names) {
        return "SELECT owner, table_name, column_id, column_name, data_type, data_length, "
                + "data_precision, data_scale, nullable "
                + "FROM all_tab_columns "
                + "WHERE " + filters(owners, names) + " "
                + "ORDER BY owner, table_name, column_id";
    }

    static String sizeSql(Collection<String> owners, Collection<String> names) {
        return "SELECT owner, table_name, num_rows, last_analyzed "
                + "FROM all_tables "
                + "WHERE " + filters(owners, names) + " "
                + "ORDER BY owner, table_name";
    }

    // ---- helpers -------------------------------------------------------

    private static String tableFilter(String alias, Collection<String> owners, Collection<String> names) {
        StringBuilder sb = new StringBuilder();
        if (owners != null && !owners.isEmpty()) sb.append("AND ").append(alias).append(".owner IN (").append(inList(owners)).append(") ");
        if (names != null && !names.isEmpty()) sb.append("AND ").append(alias).append(".table_name IN (").append(inList(names)).append(") ");
        return sb.toString().trim();
    }

    private static String filters(Collection<String> owners, Collection<String> names) {
        List<String> parts = new ArrayList<>();
        if (owners != null && !owners.isEmpty()) parts.add("owner IN (" + inList(owners) + ")");
        if (names != null && !names.isEmpty()) parts.add("table_name IN (" + inList(names) + ")");
        return parts.isEmpty() ? "1=1" : String.join(" AND ", parts);
    }

    static String inList(Collection<String> ids) {
        return ids.stream().map(RulesService::validate)
                .map(s -> "'" + s.toUpperCase() + "'")
                .collect(Collectors.joining(", "));
    }

    static String validate(String identifier) {
        String id = identifier == null ? "" : identifier.trim();
        if (!IDENT.matcher(id).matches()) {
            throw new IllegalArgumentException("illegal SQL identifier: " + identifier);
        }
        return id;
    }

    /** Parse "OWNER.TABLE, TABLE2, ..." (owner optional). */
    public static List<TableRef> parseTables(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        Set<TableRef> out = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            int dot = s.indexOf('.');
            if (dot < 0) {
                out.add(new TableRef(null, validate(s)));
            } else {
                out.add(new TableRef(validate(s.substring(0, dot)), validate(s.substring(dot + 1))));
            }
        }
        return new ArrayList<>(out);
    }

    public record TableRef(String owner, String name) {
        public String qualified() { return owner == null ? name : owner + "." + name; }
    }
}

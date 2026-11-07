package one.neo.oracle;

import one.neo.config.GraphProperties;
import one.neo.sql.SqlColumns;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an entity selector against Oracle: hard row cap, statement timeout,
 * gam-distinct fallbacks, fail-fast (null connection -> exception, no mocks).
 */
@Service
public class OracleService {

    private final DatabaseConnection dbConnection;
    private final GraphProperties props;

    public OracleService(DatabaseConnection dbConnection, GraphProperties props) {
        this.dbConnection = dbConnection;
        this.props = props;
    }

    public List<Map<String, Object>> preview(String entity, int limit) {
        if (!OracleSelectors.known(entity)) throw new IllegalArgumentException("unknown entity " + entity);
        int n = Math.max(1, Math.min(limit, props.getSync().getMaxRows()));
        try (Connection conn = dbConnection.dbConnection()) {
            if (conn == null) throw new IllegalStateException("Oracle unavailable (check db.* properties / network)");
            return run(conn, OracleSelectors.SQL.get(entity), n, entity);
        } catch (SQLException e) {
            throw new RuntimeException("Oracle selector failed for " + entity + ": " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> run(Connection conn, String sql, int limit, String entity) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(props.getSync().getQueryTimeoutSeconds());
            ps.setMaxRows(limit);
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                return toLowerList(rs);
            }
        } catch (SQLException e) {
            String fb = fallbackFor(entity);
            if (fb == null) throw e;
            try (PreparedStatement ps = conn.prepareStatement(fb)) {
                ps.setQueryTimeout(props.getSync().getQueryTimeoutSeconds());
                ps.setMaxRows(limit);
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    return toLowerList(rs);
                }
            }
        }
    }

    /**
     * Run an arbitrary read-only SELECT (no bind parameters) with a hard row cap.
     * Used by the rules/introspection layer to read Oracle's data dictionary.
     */
    public Result query(String sql, int limit) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("empty query");
        int n = Math.max(1, Math.min(limit, props.getSync().getMaxRows()));
        try (Connection conn = dbConnection.dbConnection()) {
            if (conn == null) throw new IllegalStateException("Oracle unavailable (check db.* properties / network)");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(props.getSync().getQueryTimeoutSeconds());
                ps.setMaxRows(n);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> cols = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String label = md.getColumnLabel(i);
                        cols.add(label == null ? ("c" + i) : label.toLowerCase());
                    }
                    return new Result(cols, toLowerList(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Oracle query failed: " + e.getMessage(), e);
        }
    }

    /** Column names plus rows (row keys lowercased, like every other selector). */
    public record Result(List<String> columns, List<Map<String, Object>> rows) {}

    /**
     * Run a pipeline slot's SELECT. If it contains one '?' that placeholder is
     * bound to the row limit; otherwise the limit is only a hard row cap.
     */
    public List<Map<String, Object>> select(String sql, int limit) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("empty select");
        int n = Math.max(1, Math.min(limit, props.getSync().getMaxRows()));
        try (Connection conn = dbConnection.dbConnection()) {
            if (conn == null) throw new IllegalStateException("Oracle unavailable (check db.* properties / network)");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(props.getSync().getQueryTimeoutSeconds());
                ps.setMaxRows(n);
                if (sql.indexOf('?') >= 0) ps.setInt(1, n);
                try (ResultSet rs = ps.executeQuery()) {
                    return toLowerList(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Oracle select failed: " + e.getMessage(), e);
        }
    }

    /**
     * Column aliases a SELECT yields, read from result-set metadata with ZERO
     * data rows fetched. Used by the mapping editor so the property list tracks
     * the query. The SQL is wrapped and capped so a wide or expensive query
     * cannot stall the UI; bind placeholders ('?') are bound to the cap.
     */
    public List<String> describe(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("empty select");
        String wrapped = SqlColumns.wrapForDescribe(sql);
        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement(wrapped)) {
                ps.setQueryTimeout(props.getSync().getQueryTimeoutSeconds());
                // A pipeline SELECT carries at most one optional '?' (the row
                // limit). Bind every placeholder so a stray second one cannot
                // turn a describe into "not all variables bound".
                if (wrapped.indexOf('?') >= 0) {
                    try {
                        int n = ps.getParameterMetaData().getParameterCount();
                        for (int i = 1; i <= n; i++) ps.setInt(i, 1);
                    } catch (SQLException metadataUnavailable) {
                        ps.setInt(1, 1);
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> cols = new ArrayList<>(md.getColumnCount());
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String label = md.getColumnLabel(i);
                        cols.add(label == null ? ("c" + i) : label.toLowerCase());
                    }
                    return cols;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Oracle describe failed: " + e.getMessage(), e);
        }
    }

    /** One Oracle connection for the caller to reuse across an entire run (no per-window handshake). */
    public Connection open() {        Connection conn = dbConnection.dbConnection();
        if (conn == null) throw new IllegalStateException("Oracle unavailable (check db.* properties / network)");
        return conn;
    }

    /**
     * Stream a whole slot in one pass (no ROWNUM re-scan). {@code base} has any
     * ROWNUM/limit stripped; rows are read lazily with a JDBC fetch size and handed
     * to the sink in {@code batchSize} chunks. Returns the total rows consumed.
     */
    public int stream(String base, int fetchSize, int batchSize, java.util.function.Consumer<List<Map<String, Object>>> sink) {
        if (base == null || base.isBlank()) throw new IllegalArgumentException("empty select");
        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement(base)) {
                ps.setQueryTimeout(props.getSync().getQueryTimeoutSeconds());
                ps.setFetchSize(Math.max(100, Math.min(fetchSize, 65535)));
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int total = 0;
                    List<Map<String, Object>> batch = new ArrayList<>(batchSize);
                    while (rs.next()) {
                        batch.add(toLowerRow(rs, md));
                        if (batch.size() >= batchSize) {
                            sink.accept(batch);
                            total += batch.size();
                            batch = new ArrayList<>(batchSize);
                        }
                    }
                    if (!batch.isEmpty()) {
                        sink.accept(batch);
                        total += batch.size();
                    }
                    return total;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Oracle stream failed: " + e.getMessage(), e);
        }
    }

    /**
     * Keyset (seek) paging for one slot: fetch {@code rows} starting AFTER
     * {@code lastKey}, ordered by the slot's key columns. {@code base} has any
     * ROWNUM/limit stripped; the key columns are its selected aliases, so the
     * predicate is applied on an outer view. O(total) across all pages, not O(n^2).
     */
    public List<Map<String, Object>> selectKeyset(String base, List<String> keyAliases, List<Object> lastKey, int rows) {
        if (base == null || base.isBlank()) throw new IllegalArgumentException("empty select");
        if (keyAliases == null || keyAliases.isEmpty()) throw new IllegalArgumentException("keyset needs key columns");
        String keys = String.join(", ", keyAliases);
        StringBuilder inner = new StringBuilder("SELECT * FROM (").append(base).append(") q");
        if (lastKey != null && lastKey.size() == keyAliases.size()) {
            inner.append(" WHERE (").append(keys).append(") > (")
                    .append(java.util.stream.IntStream.range(0, keyAliases.size()).mapToObj(i -> "?").collect(java.util.stream.Collectors.joining(", ")))
                    .append(")");
        }
        inner.append(" ORDER BY ").append(keys);
        String sql = "SELECT * FROM (" + inner + ") WHERE ROWNUM <= ?";
        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(props.getSync().getQueryTimeoutSeconds());
                int idx = 1;
                if (lastKey != null) {
                    for (Object v : lastKey) ps.setObject(idx++, v);
                }
                ps.setInt(idx, rows);
                try (ResultSet rs = ps.executeQuery()) {
                    return toLowerList(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Oracle keyset select failed: " + e.getMessage(), e);
        }
    }

    private String fallbackFor(String entity) {
        return switch (entity) {
            case "Branch" -> OracleSelectors.BRANCH_FALLBACK;
            case "SchemeCode" -> OracleSelectors.SCHEME_CODE_FALLBACK;
            case "SchemeType" -> OracleSelectors.SCHEME_TYPE_FALLBACK;
            default -> null;
        };
    }

    static List<Map<String, Object>> toLowerList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        while (rs.next()) rows.add(toLowerRow(rs, md));
        return rows;
    }

    static Map<String, Object> toLowerRow(ResultSet rs, ResultSetMetaData md) throws SQLException {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String k = md.getColumnLabel(i);
            r.put(k == null ? ("c" + i) : k.toLowerCase(), rs.getObject(i));
        }
        return r;
    }
}

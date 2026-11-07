package one.neo.sql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the COLUMN ALIASES a pipeline SELECT actually produces, without
 * returning any data rows.
 *
 * Preferred path is a live describe against Oracle (metadata only, zero rows
 * fetched), so what the UI shows is exactly what the sync will receive. If the
 * database is unreachable or the SQL is still a placeholder, we fall back to a
 * text parse of the select list - imperfect, but enough to pre-fill the property
 * map while the SQL is being written.
 */
public final class SqlColumns {

    /** Column aliases, lowercased, in SELECT order. */
    public record Columns(List<String> columns, String source, String warning) {}

    private static final Pattern ALIAS = Pattern.compile("(?i)\\s+AS\\s+([A-Za-z_][A-Za-z0-9_$#]*)\\s*$");
    private static final Pattern TRAILING_IDENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_$#]*)\\s*$");

    private SqlColumns() {}

    /**
     * The columns {@code select} yields. Never throws: a database/text failure
     * is reported in {@code warning} with an empty column list, so the caller
     * can keep whatever mapping it already had instead of blanking it.
     */
    public static Columns resolve(String select, java.util.function.Supplier<List<String>> liveDescribe) {
        if (select == null || select.isBlank()) {
            return new Columns(List.of(), "empty", "no query yet");
        }
        if (looksPlaceholder(select)) {
            List<String> parsed = parseAliases(select);
            return new Columns(parsed, "parsed",
                    parsed.isEmpty() ? "query is still a placeholder - replace it with real SQL first" : null);
        }
        try {
            List<String> live = liveDescribe.get();
            if (live != null && !live.isEmpty()) return new Columns(live, "oracle", null);
        } catch (RuntimeException e) {
            List<String> parsed = parseAliases(select);
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Columns(parsed, "parsed",
                    "Oracle describe failed (" + why + ") - columns below are parsed from the SQL text, not confirmed");
        }
        List<String> parsed = parseAliases(select);
        return new Columns(parsed, "parsed", null);
    }

    /** True while the SQL still contains un-replaced angle-bracket placeholders. */
    static boolean looksPlaceholder(String sql) {
        return sql.indexOf('<') >= 0 && sql.indexOf('>') > sql.indexOf('<');
    }

    /**
     * Wrap a SELECT so its metadata can be read with at most one row.
     *
     * The closing paren goes on its OWN line: a trailing {@code -- comment}
     * would otherwise swallow it and Oracle would reject the query (the comment
     * ends at the newline). A trailing semicolon is stripped for the same
     * reason - it is not valid inside a subquery.
     */
    public static String wrapForDescribe(String sql) {
        String cleaned = sql == null ? "" : sql.trim();
        while (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return "SELECT * FROM (\n" + cleaned + "\n) WHERE ROWNUM <= 1";
    }

    /**
     * Best-effort alias extraction from the top-level select list. Handles
     * comments, nested parentheses and quoted identifiers well enough to drive
     * a property map; it is a FALLBACK, never the source of truth when Oracle
     * is available.
     */
    static List<String> parseAliases(String sql) {
        String cleaned = stripComments(sql).trim();
        int start = selectListStart(cleaned);
        if (start < 0) return List.of();
        int from = topLevelFrom(cleaned, start);
        if (from <= start) return List.of();

        String list = cleaned.substring(start, from);
        Set<String> out = new LinkedHashSet<>();
        for (String item : splitTopLevel(list)) {
            String alias = aliasOf(item.trim());
            if (alias != null && !alias.isEmpty()) out.add(alias.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(out);
    }

    private static String aliasOf(String item) {
        if (item.isEmpty()) return null;
        Matcher as = ALIAS.matcher(item);
        if (as.find()) return unquote(as.group(1));

        // No AS: a bare identifier (or t.col) is its own alias; anything else
        // (a function/expression) only has a trailing identifier worth trying.
        Matcher bare = TRAILING_IDENT.matcher(item);
        if (bare.find() && item.indexOf('(') < 0 && item.indexOf(' ') < 0) {
            String ident = unquote(bare.group(1));
            int dot = ident.lastIndexOf('.');
            return dot >= 0 ? ident.substring(dot + 1) : ident;
        }
        // expression with no AS -> Oracle derives an ugly name; skip it rather
        // than invent one the sync would never receive.
        return item.indexOf('(') >= 0 ? null : unquote(bare.find() ? bare.group(1) : null);
    }

    private static String unquote(String ident) {
        if (ident == null) return null;
        if (ident.length() > 1 && (ident.charAt(0) == '"' && ident.charAt(ident.length() - 1) == '"')) {
            return ident.substring(1, ident.length() - 1);
        }
        return ident;
    }

    /** Index just past SELECT (skipping a leading WITH clause is NOT supported). */
    private static int selectListStart(String sql) {
        Matcher m = Pattern.compile("(?i)^\\s*SELECT\\s+(?:DISTINCT\\s+|ALL\\s+|\\+\\s*)?").matcher(sql);
        return m.find() ? m.end() : -1;
    }

    /** Index of the top-level FROM keyword at paren depth 0. */
    private static int topLevelFrom(String sql, int fromIndex) {
        int depth = 0;
        boolean inStr = false, inIdent = false;
        for (int i = fromIndex; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inStr) {
                if (c == '\'') inStr = false;
                continue;
            }
            if (inIdent) {
                if (c == '"') inIdent = false;
                continue;
            }
            if (c == '\'') { inStr = true; continue; }
            if (c == '"') { inIdent = true; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (depth == 0 && (c == 'F' || c == 'f')) {
                if (sql.regionMatches(true, i, "FROM", 0, 4)) {
                    char after = i + 4 < sql.length() ? sql.charAt(i + 4) : ' ';
                    if (!Character.isLetterOrDigit(after) && after != '_') return i;
                }
            }
        }
        return -1;
    }

    /** Split on commas that sit at paren depth 0. */
    private static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        int depth = 0, last = 0;
        boolean inStr = false, inIdent = false;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (inStr) { if (c == '\'') inStr = false; continue; }
            if (inIdent) { if (c == '"') inIdent = false; continue; }
            if (c == '\'') { inStr = true; continue; }
            if (c == '"') { inIdent = true; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) {
                parts.add(list.substring(last, i));
                last = i + 1;
            }
        }
        if (last < list.length()) parts.add(list.substring(last));
        return parts;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\n]*", " ");
    }
}

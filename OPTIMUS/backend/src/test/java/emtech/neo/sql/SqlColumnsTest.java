package one.neo.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlColumnsTest {

    @Test
    void plainSelect() {
        assertEquals(List.of("tranid", "trandate"),
                SqlColumns.parseAliases("SELECT tranid, trandate FROM t"));
    }

    @Test
    void commentBeforeSelect() {
        assertEquals(List.of("tranid", "trandate"),
                SqlColumns.parseAliases("-- pull the header\nSELECT tranid, trandate FROM t"));
    }

    @Test
    void lineCommentAfterColumn() {
        assertEquals(List.of("tranid", "trandate"),
                SqlColumns.parseAliases(
                        "SELECT tranid, -- the key\n        trandate\nFROM t"));
    }

    @Test
    void commentContainingFromAndComma() {
        assertEquals(List.of("tranid", "trandate"),
                SqlColumns.parseAliases(
                        "SELECT tranid, -- joined, from the header\n trandate FROM t"));
    }

    @Test
    void blockCommentInList() {
        assertEquals(List.of("tranid", "trandate"),
                SqlColumns.parseAliases(
                        "SELECT /* header key */ tranid, trandate FROM t"));
    }

    @Test
    void trailingLineComment() {
        assertEquals(List.of("tranid", "trandate"),
                SqlColumns.parseAliases("SELECT tranid, trandate FROM t -- done"));
    }

    @Test
    void multilineBlockComment() {
        assertEquals(List.of("a", "b"),
                SqlColumns.parseAliases(
                        "SELECT\n  /* first\n     line */\n  a,\n  b -- last\nFROM t"));
    }

    // ------------------------------------------------- the describe wrapper

    @Test
    void wrapClosesOnItsOwnLineAfterATrailingLineComment() {
        String wrapped = SqlColumns.wrapForDescribe("SELECT tranid FROM t -- done");
        // The comment must end before the closing paren, or Oracle sees no ')'
        String afterComment = wrapped.substring(wrapped.indexOf("-- done") + "-- done".length());
        assertTrue(afterComment.startsWith("\n)"), afterComment);
    }

    @Test
    void wrapStripsATrailingSemicolon() {
        assertEquals("SELECT * FROM (\nSELECT tranid FROM t\n) WHERE ROWNUM <= 1",
                SqlColumns.wrapForDescribe("SELECT tranid FROM t;"));
    }

    @Test
    void wrapKeepsAnOrdinaryQueryIntact() {
        assertEquals("SELECT * FROM (\nSELECT tranid FROM t\n) WHERE ROWNUM <= 1",
                SqlColumns.wrapForDescribe("  SELECT tranid FROM t  "));
    }
}

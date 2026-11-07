package one.neo.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RulesServiceTest {

    @Test
    void parsesOwnerQualifiedAndBareTables() {
        List<RulesService.TableRef> refs = RulesService.parseTables("TBAADM.GAM, crmuser.accounts , GSM");
        assertEquals(3, refs.size());
        assertEquals("TBAADM.GAM", refs.get(0).qualified());
        assertEquals("TBAADM", refs.get(0).owner());
        assertEquals("accounts", refs.get(1).name());
        assertNull(refs.get(2).owner());
        assertEquals("GSM", refs.get(2).qualified());
    }

    @Test
    void rejectsIllegalIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> RulesService.parseTables("TBAADM.GAM; DROP TABLE x"));
        assertThrows(IllegalArgumentException.class, () -> RulesService.validate("a b"));
        assertThrows(IllegalArgumentException.class, () -> RulesService.validate("GAM'"));
    }

    @Test
    void inListQuotesAndUppercases() {
        assertEquals("'GAM', 'ACCOUNTS'", RulesService.inList(List.of("gam", "accounts")));
    }

    @Test
    void primaryKeySqlScopesToTables() {
        String sql = RulesService.primaryKeySql(List.of("TBAADM"), List.of("GAM"));
        assertTrue(sql.contains("constraint_type IN ('P','U')"));
        assertTrue(sql.contains("c.owner IN ('TBAADM')"));
        assertTrue(sql.contains("c.table_name IN ('GAM')"));
    }

    @Test
    void candidateJoinsUsesListaggAndHaving() {
        String sql = RulesService.candidateJoinsSql(List.of("TBAADM", "CRMUSER"), List.of("GAM", "ACCOUNTS"));
        assertTrue(sql.contains("LISTAGG"));
        assertTrue(sql.contains("HAVING COUNT(*) > 1"));
        assertTrue(sql.contains("owner IN ('TBAADM', 'CRMUSER')"));
    }

    @Test
    void aliasSqlMatchesSynonymOrBaseName() {
        String sql = RulesService.aliasSql(List.of("TBAADM"), List.of("GAM"));
        assertTrue(sql.contains("FROM all_synonyms"));
        assertTrue(sql.contains("synonym_name IN ('GAM')"));
        assertTrue(sql.contains("table_name IN ('GAM')"));
        assertTrue(sql.contains("owner IN ('TBAADM')"));
        assertTrue(sql.contains("table_owner IN ('TBAADM')"));
    }

    @Test
    void filtersFallBackWhenNothingConfigured() {
        String sql = RulesService.columnSql(List.of(), List.of());
        assertTrue(sql.contains("WHERE 1=1"));
    }
}

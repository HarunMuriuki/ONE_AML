package one.neo.rules;

import one.neo.oracle.OracleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RulesServiceGroupTest {

    private static final Path MODEL = Paths.get("model");

    private RulesService service(Path temp, OracleService oracle) throws IOException {
        Files.copy(MODEL.resolve("rules.json"), temp.resolve("rules.json"));
        RulesStore store = new RulesStore(temp);
        return new RulesService(oracle, store, "TBAADM.GAM", 100);
    }

    @Test
    void runsOnlyTheSelectedRule(@TempDir Path temp) throws IOException {
        OracleService oracle = mock(OracleService.class);
        when(oracle.query(anyString(), anyInt()))
                .thenReturn(new OracleService.Result(List.of("synonym_name", "real_table"),
                        List.of(Map.of("synonym_name", "GAM", "real_table", "GENERAL_ACCT_MASTER_TBL"))));

        RulesService svc = service(temp, oracle);
        Map<String, Object> cat = svc.refreshGroup("aliases");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) cat.get("groups");
        assertEquals(7, groups.size(), "all rule groups still present");

        Map<String, Object> aliases = groups.stream()
                .filter(g -> "aliases".equals(g.get("id"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, aliases.get("ok"));
        assertEquals(1, ((List<?>) aliases.get("rows")).size());

        Map<String, Object> columns = groups.stream()
                .filter(g -> "columns".equals(g.get("id"))).findFirst().orElseThrow();
        assertNull(columns.get("ok"), "other groups left untouched");

        verify(oracle, times(1)).query(anyString(), anyInt());
    }

    @Test
    void rejectsUnknownRule(@TempDir Path temp) throws IOException {
        RulesService svc = service(temp, mock(OracleService.class));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc.refreshGroup("nope"));
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    void listsRuleIds() throws IOException {
        RulesService svc = new RulesService(mock(OracleService.class),
                new RulesStore(Files.createTempDirectory("rules")), "", 100);
        assertTrue(svc.ruleIds().contains("aliases"));
        assertTrue(svc.ruleIds().contains("primary_keys"));
        assertEquals(7, svc.ruleIds().size());
    }
}

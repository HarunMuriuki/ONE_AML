package one.neo.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RulesStoreTest {

    private static final Path MODEL = Paths.get("model");

    @Test
    void loadsSeededRules(@TempDir Path temp) throws IOException {
        Files.copy(MODEL.resolve("rules.json"), temp.resolve("rules.json"));
        RulesStore store = new RulesStore(temp);
        Object groups = store.catalog().get("groups");
        assertTrue(groups instanceof List);
        assertEquals(7, ((List<?>) groups).size());
    }

    @Test
    void savePersistsAndReloads(@TempDir Path temp) throws IOException {
        Files.copy(MODEL.resolve("rules.json"), temp.resolve("rules.json"));
        RulesStore store = new RulesStore(temp);

        Map<String, Object> catalog = Map.of(
                "generatedAt", "2026-01-01T00:00:00Z",
                "source", "test",
                "tables", List.of("TBAADM.GAM"),
                "groups", List.of(Map.of("id", "primary_keys", "title", "Primary Keys & Unique Keys")));
        store.save(catalog);

        RulesStore reloaded = new RulesStore(temp);
        assertEquals("test", reloaded.catalog().get("source"));
        assertEquals(1, ((List<?>) reloaded.catalog().get("groups")).size());
    }

    @Test
    void missingFileStartsEmpty(@TempDir Path temp) {
        RulesStore store = new RulesStore(temp);
        assertNotNull(store.catalog());
        assertEquals(0, ((List<?>) store.catalog().get("groups")).size());
    }
}

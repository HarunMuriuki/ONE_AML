package one.neo.rules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads/saves rules.json - the discovered Oracle rules (keys, joins, indexes...).
 * The file is produced by RulesService.refresh() and read back for the /rules page.
 * Same folder as schema.json / pipelines.json (neo.model.dir).
 */
@Service
public class RulesStore {

    private static final Logger log = LoggerFactory.getLogger(RulesStore.class);

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Map<String, Object> catalog;

    @Autowired
    public RulesStore(@Value("${neo.model.dir:model}") String dir) {
        this(Paths.get(dir));
    }

    public RulesStore(Path dir) {
        this.dir = dir;
        reload();
    }

    public synchronized void reload() {
        Path file = dir.resolve("rules.json");
        try {
            catalog = mapper.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.warn("rules.json not readable in {} ({}); starting empty", dir.toAbsolutePath(), e.getMessage());
            catalog = new LinkedHashMap<>();
            catalog.put("generatedAt", null);
            catalog.put("source", "Oracle (not refreshed yet)");
            catalog.put("tables", java.util.List.of());
            catalog.put("groups", java.util.List.of());
        }
    }

    public Map<String, Object> catalog() { return catalog; }

    public synchronized void save(Map<String, Object> c) {
        this.catalog = c;
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("rules.json").toFile(), c);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write rules.json in " + dir.toAbsolutePath(), e);
        }
    }

    public Path dir() { return dir; }
}

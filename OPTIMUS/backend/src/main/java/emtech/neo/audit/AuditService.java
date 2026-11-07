package one.neo.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only audit trail (audit.jsonl). One JSON line per sync action, success
 * OR failure, never rewritten - the record an AML reviewer needs to show what
 * was pulled, when, how much, and what went wrong.
 *
 * Each line: ts, kind, slot, action, offset, selected, pushed, done, ms, ok,
 * error (on failure), sql + cypher (for reproducibility).
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_READ = 1000;

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    @Autowired
    public AuditService(@Value("${neo.model.dir:model}") String dir) {
        this(Paths.get(dir));
    }

    public AuditService(Path dir) {
        this.file = dir.resolve("audit.jsonl");
    }

    /** Append one entry (timestamp is added automatically). Never throws. */
    public void record(Map<String, Object> entry) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", Instant.now().toString());
        if (entry != null) e.putAll(entry);
        try {
            String line = mapper.writeValueAsString(e);
            synchronized (lock) {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ex) {
            log.warn("audit write failed: {}", ex.getMessage());
        }
    }

    /** Most recent entries first. Corrupt lines are skipped, not fatal. */
    public List<Map<String, Object>> tail(int limit) {
        int n = Math.max(1, Math.min(limit, MAX_READ));
        if (!Files.exists(file)) return List.of();
        synchronized (lock) {
            List<Map<String, Object>> out = new ArrayList<>();
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && out.size() < n; i--) {
                    String l = lines.get(i).trim();
                    if (l.isEmpty()) continue;
                    try {
                        out.add(mapper.readValue(l, new TypeReference<Map<String, Object>>() {}));
                    } catch (IOException ignore) {
                        // skip malformed line
                    }
                }
            } catch (IOException ex) {
                log.warn("audit read failed: {}", ex.getMessage());
            }
            return out;
        }
    }

    public Path file() {
        return file;
    }
}

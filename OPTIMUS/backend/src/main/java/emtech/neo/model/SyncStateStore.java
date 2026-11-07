package one.neo.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-slot keyset cursor (sync-state.json). Remembers the last key value each slot
 * was synced to, so successive "Run" clicks pull the NEXT slice (seek paging) and
 * "Run all" can resume from where it stopped. Kept as a file so it survives restarts
 * and can be reset.
 */
@Service
public class SyncStateStore {

    private static final Logger log = LoggerFactory.getLogger(SyncStateStore.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();

    @Autowired
    public SyncStateStore(@Value("${neo.model.dir:model}") String dir) {
        this(Paths.get(dir));
    }

    public SyncStateStore(Path dir) {
        this.file = dir.resolve("sync-state.json");
        load();
    }

    public Cursor cursor(String slot) {
        return cursors.get(slot);
    }

    public int count(String slot) {
        Cursor c = cursors.get(slot);
        return c == null ? 0 : c.getCount();
    }

    public boolean isDone(String slot) {
        Cursor c = cursors.get(slot);
        return c != null && c.isDone();
    }

    /** Advance to a new {@code lastKey} and add {@code added} rows; keeps {@code done=false} so the next Run continues. */
    public synchronized void advance(String slot, List<Object> lastKey, int added) {
        Cursor c = cursors.computeIfAbsent(slot, k -> new Cursor());
        if (lastKey != null && !lastKey.isEmpty()) c.setLastKey(lastKey);
        c.setCount(c.getCount() + Math.max(0, added));
        c.setDone(false);
        persist();
    }

    public synchronized void markDone(String slot) {
        Cursor c = cursors.computeIfAbsent(slot, k -> new Cursor());
        c.setDone(true);
        persist();
    }

    public synchronized void reset(String slot) {
        cursors.remove(slot);
        persist();
    }

    public synchronized Map<String, Cursor> snapshot() {
        return new LinkedHashMap<>(cursors);
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                Map<String, Cursor> m = mapper.readValue(
                        file.toFile(), new TypeReference<Map<String, Cursor>>() {});
                if (m != null) cursors.putAll(m);
            }
        } catch (IOException e) {
            log.warn("cannot read sync-state.json, starting fresh: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), cursors);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write sync-state.json in " + file.toAbsolutePath(), e);
        }
    }

    /** One slot's progress: last key value seen, rows synced so far, finished flag. */
    public static class Cursor {
        private List<Object> lastKey;
        private int count;
        private boolean done;

        public Cursor() {}

        public Cursor(List<Object> lastKey, int count, boolean done) {
            this.lastKey = lastKey;
            this.count = count;
            this.done = done;
        }

        public List<Object> getLastKey() { return lastKey; }
        public void setLastKey(List<Object> lastKey) { this.lastKey = lastKey; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public boolean isDone() { return done; }
        public void setDone(boolean done) { this.done = done; }

        public boolean hasKey() { return lastKey != null && !lastKey.isEmpty(); }
    }
}
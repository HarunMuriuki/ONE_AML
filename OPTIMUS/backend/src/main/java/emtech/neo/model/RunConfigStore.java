package one.neo.model;

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

/**
 * Loads/saves config.json (runtime settings, e.g. rows per Run).
 * Lives beside schema.json / pipelines.json so nothing is hardcoded in Java.
 */
@Service
public class RunConfigStore {

    private static final Logger log = LoggerFactory.getLogger(RunConfigStore.class);
    private static final int MIN = 1;
    private static final int MAX = 1_000_000;

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile RunConfig config = new RunConfig();

    @Autowired
    public RunConfigStore(@Value("${neo.model.dir:model}") String dir) {
        this(Paths.get(dir));
    }

    public RunConfigStore(Path dir) {
        this.file = dir.resolve("config.json");
        load();
    }

    public int rowsPerRun() {
        return config.getRowsPerRun();
    }

    public RunConfig get() {
        return config;
    }

    public synchronized RunConfig save(RunConfig in) {
        RunConfig next = in == null ? new RunConfig() : in;
        if (next.getRowsPerRun() < MIN) next.setRowsPerRun(MIN);
        if (next.getRowsPerRun() > MAX) next.setRowsPerRun(MAX);
        this.config = next;
        persist();
        return config;
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                RunConfig loaded = mapper.readValue(file.toFile(), RunConfig.class);
                if (loaded != null) config = loaded;
            }
        } catch (IOException e) {
            log.warn("cannot read config.json, using defaults: {}", e.getMessage());
        }
        if (config.getRowsPerRun() < MIN) config.setRowsPerRun(MIN);
        if (config.getRowsPerRun() > MAX) config.setRowsPerRun(MAX);
    }

    private void persist() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), config);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write config.json in " + file.toAbsolutePath(), e);
        }
    }
}

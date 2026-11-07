package one.neo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the Oracle -> Neo4j ETL and realtime scoring.
 * Bindable from env (GRAPH_SYNC_BATCH_SIZE, AML_LARGE_AMOUNT_KES, ...).
 */
@Component
@ConfigurationProperties(prefix = "graph")
public class GraphProperties {

    private final Sync sync = new Sync();
    private final Aml aml = new Aml();

    public Sync getSync() { return sync; }
    public Aml getAml() { return aml; }

    public static class Sync {
        private boolean enabled = true;
        private int batchSize = 1000;
        private int maxRows = 5000;
        private int queryTimeoutSeconds = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
        public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
    }

    public static class Aml {
        private long largeAmountKes = 1_500_000L;
        private long structuringLowKes = 1_100_000L;
        private int velocityCount = 5;
        private int velocityWindowMinutes = 60;

        public long getLargeAmountKes() { return largeAmountKes; }
        public void setLargeAmountKes(long largeAmountKes) { this.largeAmountKes = largeAmountKes; }
        public long getStructuringLowKes() { return structuringLowKes; }
        public void setStructuringLowKes(long structuringLowKes) { this.structuringLowKes = structuringLowKes; }
        public int getVelocityCount() { return velocityCount; }
        public void setVelocityCount(int velocityCount) { this.velocityCount = velocityCount; }
        public int getVelocityWindowMinutes() { return velocityWindowMinutes; }
        public void setVelocityWindowMinutes(int velocityWindowMinutes) { this.velocityWindowMinutes = velocityWindowMinutes; }
    }
}

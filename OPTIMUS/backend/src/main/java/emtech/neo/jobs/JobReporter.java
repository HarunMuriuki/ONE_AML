package one.neo.jobs;

/**
 * Progress/control hook a sync job exposes to the runner:
 * {@link #tick} reports cumulative counts for the current run, {@link #cancelled}
 * lets the runner stop between batches.
 */
public interface JobReporter {

    void tick(long selected, long pushed, long created, boolean done);

    boolean cancelled();

    JobReporter NOOP = new JobReporter() {
        @Override public void tick(long selected, long pushed, long created, boolean done) {}
        @Override public boolean cancelled() { return false; }
    };
}
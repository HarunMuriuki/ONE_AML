package one.neo.jobs;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.concurrent.Future;

/**
 * One async sync action (a Run or Run-all) with live progress. Persisted to
 * jobs.json so the UI can re-attach to it after a page refresh; the {@link Future}
 * is runtime-only.
 */
public class SyncJob {

    public enum Status { RUNNING, DONE, FAILED, CANCELLED, INTERRUPTED }

    private String id;
    private String kind;    // node | rel
    private String action;  // run | run-all | detach-delete
    private String slot;    // node:<id> | rel:<id>
    private String target;  // display label
    private Status status = Status.RUNNING;
    private long selected;
    private long pushed;
    private long created;
    private long count;
    /** detach-delete only: what was actually removed from Neo4j. */
    private long deletedNodes;
    private long deletedRelationships;
    private boolean done;
    private long startedAt;
    private Long endedAt;
    private long ms;
    private String message;
    private String error;

    @JsonIgnore
    private transient Future<?> future;
    @JsonIgnore
    private volatile boolean cancelRequested;

    public SyncJob() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getSlot() { return slot; }
    public void setSlot(String slot) { this.slot = slot; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public long getSelected() { return selected; }
    public void setSelected(long selected) { this.selected = selected; }

    public long getPushed() { return pushed; }
    public void setPushed(long pushed) { this.pushed = pushed; }

    /** Rows that became genuinely NEW nodes/relationships (0 on a pure re-sync). */
    public long getCreated() { return created; }
    public void setCreated(long created) { this.created = created; }

    /** Rows that matched something already in the graph (MERGE no-op). */
    public long getExisting() { return Math.max(0, selected - created); }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public long getDeletedNodes() { return deletedNodes; }
    public void setDeletedNodes(long deletedNodes) { this.deletedNodes = deletedNodes; }

    public long getDeletedRelationships() { return deletedRelationships; }
    public void setDeletedRelationships(long deletedRelationships) { this.deletedRelationships = deletedRelationships; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }

    public Long getEndedAt() { return endedAt; }
    public void setEndedAt(Long endedAt) { this.endedAt = endedAt; }

    public long getMs() { return ms; }
    public void setMs(long ms) { this.ms = ms; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Future<?> getFuture() { return future; }
    public void setFuture(Future<?> future) { this.future = future; }

    public boolean isCancelRequested() { return cancelRequested; }
    public void requestCancel() { this.cancelRequested = true; }

    public boolean isTerminal() {
        return status != Status.RUNNING;
    }
}
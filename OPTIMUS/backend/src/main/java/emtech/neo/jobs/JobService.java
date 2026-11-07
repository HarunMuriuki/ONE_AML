package one.neo.jobs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import one.neo.graph.CancelledException;
import one.neo.graph.GraphDataService;
import one.neo.graph.GraphIndexService;
import one.neo.graph.PipelineRunner;
import one.neo.model.NodePipeline;
import one.neo.model.PipelineStore;
import one.neo.model.RelPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs sync actions in the background so the UI never blocks and a page refresh
 * can re-attach. A SINGLE worker thread serializes syncs - concurrent Neo4j
 * MERGE transactions deadlock on shared nodes, so we never run two at once.
 * Job state is persisted to jobs.json for history; jobs still RUNNING at startup
 * (from a killed process) are marked INTERRUPTED.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final int MAX_HISTORY = 200;

    private final PipelineRunner runner;
    private final GraphIndexService indexes;
    private final GraphDataService graphData;
    private final PipelineStore store;
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SyncJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "neo-sync-worker");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public JobService(PipelineRunner runner, GraphIndexService indexes,
                      GraphDataService graphData, PipelineStore store,
                      @Value("${neo.model.dir:model}") String dir) {
        this(runner, indexes, graphData, store, Paths.get(dir));
    }

    public JobService(PipelineRunner runner, GraphIndexService indexes,
                      GraphDataService graphData, PipelineStore store, Path dir) {
        this.runner = runner;
        this.indexes = indexes;
        this.graphData = graphData;
        this.store = store;
        this.file = dir.resolve("jobs.json");
        load();
    }

    public SyncJob submit(String kind, String id, String action) {
        SyncJob job = new SyncJob();
        job.setId(UUID.randomUUID().toString().substring(0, 8));
        job.setKind(kind);
        job.setAction(action);
        job.setSlot(kind + ":" + id);
        job.setStartedAt(System.currentTimeMillis());
        job.setStatus(SyncJob.Status.RUNNING);
        jobs.put(job.getId(), job);
        job.setFuture(worker.submit(() -> execute(job, id, action)));
        persist();
        return job;
    }

    private void execute(SyncJob job, String id, String action) {
        String kind = job.getKind();
        try {
            if ("detach-delete".equals(action)) {
                detachDelete(job, id);
                return;
            }
            indexes.ensure(); // index-backed MERGE (biggest speed win); never blocks a run
            JobReporter rep = new JobReporter() {
                @Override public void tick(long selected, long pushed, long created, boolean done) {
                    job.setSelected(selected);
                    job.setPushed(pushed);
                    job.setCreated(created);
                    job.setDone(done);
                }
                @Override public boolean cancelled() {
                    return job.isCancelRequested();
                }
            };
            boolean all = "run-all".equals(action);
            Map<String, Object> res = "node".equals(kind)
                    ? (all ? runner.runNodeAll(id, rep) : runner.runNode(id, rep))
                    : (all ? runner.runRelationshipAll(id, rep) : runner.runRelationship(id, rep));
            job.setTarget(String.valueOf(res.getOrDefault("target", job.getSlot())));
            job.setCount(asLong(res.get("count")));
            job.setSelected(asLong(res.get("selected")));
            job.setPushed(asLong(res.get("pushed")));
            job.setCreated(asLong(res.get("created")));
            job.setStatus(SyncJob.Status.DONE);
            job.setDone(true);
            job.setMessage(job.getTarget() + ": " + job.getSelected() + " rows, " + job.getCreated()
                    + " new, " + job.getExisting() + " already present");
        } catch (CancelledException ce) {
            job.setStatus(SyncJob.Status.CANCELLED);
            job.setMessage("cancelled");
            log.info("job {} cancelled", job.getId());
        } catch (Exception e) {
            job.setStatus(SyncJob.Status.FAILED);
            job.setError(e.getMessage());
            job.setMessage("failed: " + e.getMessage());
            log.warn("job {} failed: {}", job.getId(), e.getMessage());
        } finally {
            job.setEndedAt(System.currentTimeMillis());
            job.setMs(job.getEndedAt() - job.getStartedAt());
            persist();
        }
    }

    /**
     * Remove a slot's DATA from Neo4j. The slot itself is untouched - the mapping
     * stays, so a later Run rebuilds it from Oracle. Runs on the same single worker
     * as the syncs, so a delete can never interleave with a MERGE.
     */
    private void detachDelete(SyncJob job, String id) {
        if ("node".equals(job.getKind())) {
            NodePipeline p = store.nodeSlot(id);
            if (p == null) throw new IllegalArgumentException("unknown node pipeline " + id);
            job.setTarget(p.getLabel());
            GraphDataService.DataCount removed = graphData.detachDeleteNode(p.getLabel());
            job.setDeletedNodes(removed.nodes());
            job.setDeletedRelationships(removed.relationships());
            job.setCount(removed.nodes() + removed.relationships());
            job.setMessage("detached: " + removed.nodes() + " node(s) and "
                    + removed.relationships() + " relationship(s) of " + p.getLabel());
        } else {
            RelPipeline p = store.relSlot(id);
            if (p == null) throw new IllegalArgumentException("unknown relationship pipeline " + id);
            if (p.getFrom() == null || p.getTo() == null) {
                throw new IllegalArgumentException("relationship " + id + " has no endpoints");
            }
            job.setTarget(p.getType() + " " + p.getFrom().getLabel() + " -> " + p.getTo().getLabel());
            long removed = graphData.detachDeleteRelationships(
                    p.getType(), p.getFrom().getLabel(), p.getTo().getLabel());
            job.setDeletedRelationships(removed);
            job.setCount(removed);
            job.setMessage("detached " + removed + " " + p.getType() + " relationship(s) "
                    + p.getFrom().getLabel() + " -> " + p.getTo().getLabel());
        }
        job.setStatus(SyncJob.Status.DONE);
        job.setDone(true);
    }

    public List<SyncJob> list() {
        List<SyncJob> out = new ArrayList<>(jobs.values());
        out.sort(Comparator.comparingLong(SyncJob::getStartedAt).reversed());
        return out;
    }

    public SyncJob get(String id) {
        return jobs.get(id);
    }

    /** Request cancellation; the runner stops at the next batch boundary. */
    public SyncJob cancel(String id) {
        SyncJob job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("unknown job " + id);
        if ("detach-delete".equals(job.getAction()) && !job.isTerminal()) {
            // Neo4j batches the delete internally, so there is no boundary to stop at.
            job.setMessage("detach delete runs to completion and cannot be cancelled");
            return job;
        }
        if (!job.isTerminal()) {
            job.requestCancel();
            job.setMessage("cancelling...");
        }
        return job;
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    private synchronized void load() {
        try {
            if (Files.exists(file)) {
                List<SyncJob> list = mapper.readValue(file.toFile(), new TypeReference<List<SyncJob>>() {});
                if (list != null) {
                    for (SyncJob j : list) {
                        if (j.getStatus() == SyncJob.Status.RUNNING) {
                            j.setStatus(SyncJob.Status.INTERRUPTED);
                            if (j.getEndedAt() == null) {
                                j.setEndedAt(System.currentTimeMillis());
                                j.setMs(j.getEndedAt() - j.getStartedAt());
                            }
                        }
                        jobs.put(j.getId(), j);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("cannot read jobs.json, starting fresh: {}", e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            List<SyncJob> out = list();
            if (out.size() > MAX_HISTORY) out = out.subList(0, MAX_HISTORY);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
        } catch (IOException e) {
            log.warn("cannot write jobs.json: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }
}
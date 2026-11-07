package one.neo.graph;

import one.neo.audit.AuditService;
import one.neo.model.NodePipeline;
import one.neo.model.PipelineSet;
import one.neo.model.PipelineStore;
import one.neo.model.RelEndpoint;
import one.neo.model.RelPipeline;
import one.neo.model.RunConfigStore;
import one.neo.model.SyncStateStore;
import one.neo.oracle.OracleService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunnerTest {

    private PipelineStore storeWith(NodePipeline node, RelPipeline rel) {
        PipelineSet set = new PipelineSet();
        if (node != null) set.setNodes(List.of(node));
        if (rel != null) set.setRelationships(List.of(rel));
        PipelineStore store = mock(PipelineStore.class);
        when(store.pipelines()).thenReturn(set);
        return store;
    }

    private RunConfigStore config(int rowsPerRun) {
        RunConfigStore config = mock(RunConfigStore.class);
        when(config.rowsPerRun()).thenReturn(rowsPerRun);
        return config;
    }

    private SyncStateStore state() {
        SyncStateStore state = mock(SyncStateStore.class);
        when(state.cursor(anyString())).thenReturn(null);
        when(state.count(anyString())).thenReturn(0);
        return state;
    }

    private NodePipeline accountNode() {
        NodePipeline n = new NodePipeline();
        n.setId("account");
        n.setLabel("Account");
        n.setKey(List.of("accountNumber"));
        n.setMap(Map.of("accountNumber", "foracid"));
        n.setSelect("SELECT foracid FROM tbaadm.gam WHERE ROWNUM <= ?");
        return n;
    }

    @Test
    void runNodeSelectsNextWindowThenMerges() {
        OracleService oracle = mock(OracleService.class);
        SyncService sync = mock(SyncService.class);
        when(oracle.selectKeyset(anyString(), anyList(), any(), anyInt()))
                .thenReturn(List.of(Map.of("foracid", "A1")));
        when(sync.pushCypher(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new SyncService.PushResult(1, 1, 0, 3));

        PipelineRunner runner = new PipelineRunner(storeWith(accountNode(), null), oracle, sync, config(500), state(), mock(AuditService.class));
        Map<String, Object> res = runner.runNode("account");

        assertEquals(1, res.get("selected"));
        assertEquals(1L, res.get("pushed"));
        assertEquals(1L, res.get("created"));
        assertEquals(Boolean.TRUE, res.get("done"));
        assertTrue(String.valueOf(res.get("cypher")).contains("MERGE (n:Account"));
        verify(sync).pushCypher(anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void runAllStreamsWholeSlotInOnePass() {
        OracleService oracle = mock(OracleService.class);
        SyncService sync = mock(SyncService.class);
        when(oracle.stream(anyString(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<List<Map<String, Object>>> sink = inv.getArgument(3);
            sink.accept(List.of(Map.of("foracid", "A1"), Map.of("foracid", "A2")));
            sink.accept(List.of(Map.of("foracid", "A3")));
            return 3;
        });
        when(sync.pushCypher(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new SyncService.PushResult(2, 2, 0, 6), new SyncService.PushResult(1, 1, 0, 3));

        PipelineRunner runner = new PipelineRunner(storeWith(accountNode(), null), oracle, sync, config(2), state(), mock(AuditService.class));
        Map<String, Object> res = runner.runNodeAll("account");

        assertEquals(2L, res.get("runs"));
        assertEquals(3L, res.get("selected"));
        assertEquals(3L, res.get("pushed"));
        assertEquals(3L, res.get("created"));
        assertEquals(Boolean.TRUE, res.get("done"));
    }

    @Test
    void reSyncingExistingRowsCreatesNothing() {
        OracleService oracle = mock(OracleService.class);
        SyncService sync = mock(SyncService.class);
        when(oracle.selectKeyset(anyString(), anyList(), any(), anyInt()))
                .thenReturn(List.of(Map.of("foracid", "A1"), Map.of("foracid", "A2")));
        // MERGE matched nodes that already exist: rows went out, nothing was created.
        when(sync.pushCypher(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new SyncService.PushResult(2, 0, 0, 28));

        PipelineRunner runner = new PipelineRunner(storeWith(accountNode(), null), oracle, sync,
                config(500), state(), mock(AuditService.class));
        Map<String, Object> res = runner.runNode("account");

        assertEquals(2, res.get("selected"));
        assertEquals(2L, res.get("pushed"));
        assertEquals(0L, res.get("created"), "re-syncing existing nodes must report 0 created");
    }

    @Test
    void stripRowLimitRemovesTrailingCap() {
        assertEquals("SELECT a FROM t WHERE a = 1",
                PipelineRunner.stripRowLimit("SELECT a FROM t WHERE a = 1 AND ROWNUM <= ?"));
        assertEquals("SELECT a FROM t",
                PipelineRunner.stripRowLimit("SELECT a FROM t WHERE ROWNUM <= ?"));
    }

    @Test
    void refusesPlaceholderSelect() {
        NodePipeline n = accountNode();
        n.setSelect("-- PLACEHOLDER: replace me");
        PipelineRunner runner = new PipelineRunner(storeWith(n, null), mock(OracleService.class),
                mock(SyncService.class), config(500), state(), mock(AuditService.class));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> runner.runNode("account"));
        assertTrue(e.getMessage().toLowerCase().contains("placeholder"));
    }

    @Test
    void rejectsUnknownSlot() {
        PipelineRunner runner = new PipelineRunner(storeWith(null, null), mock(OracleService.class),
                mock(SyncService.class), config(500), state(), mock(AuditService.class));
        assertThrows(IllegalArgumentException.class, () -> runner.runNode("nope"));
    }

    @Test
    void relationshipWithoutEndpointKeyIsRefusedBeforeHittingOracle() {
        RelPipeline r = new RelPipeline();
        r.setId("performs");
        r.setType("PERFORMS");
        r.setFrom(new RelEndpoint());
        r.setTo(new RelEndpoint());
        r.setSelect("SELECT 1 FROM dual");
        OracleService oracle = mock(OracleService.class);

        PipelineRunner runner = new PipelineRunner(storeWith(null, r), oracle, mock(SyncService.class),
                config(500), state(), mock(AuditService.class));
        assertThrows(IllegalArgumentException.class, () -> runner.runRelationship("performs"));
        verify(oracle, never()).selectKeyset(anyString(), anyList(), any(), anyInt());
    }
}

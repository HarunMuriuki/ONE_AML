package one.neo.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineStoreTest {

    private static final Path MODEL = Paths.get("model");

    /** schema.json now declares TransactionEntry (double-entry legs) on top of the 19 base nodes. */
    private static final int SCHEMA_NODES = 20;
    private static final int SCHEMA_RELS = 26;

    @Test
    void loadsSeededModelFromDisk() {
        PipelineStore store = new PipelineStore(MODEL);
        assertEquals(SCHEMA_NODES, store.pipelines().getNodes().size(), "one slot per schema node");
        assertEquals(SCHEMA_RELS, store.pipelines().getRelationships().size(), "one slot per schema relationship");
    }

    @Test
    void coverageIsComplete() {
        Coverage c = new PipelineStore(MODEL).coverage();
        assertEquals(SCHEMA_NODES, c.schemaNodes());
        assertEquals(SCHEMA_RELS, c.schemaRelationships());
        assertTrue(c.missingNodes().isEmpty(), () -> "missing nodes: " + c.missingNodes());
        assertTrue(c.missingRelationships().isEmpty(), () -> "missing rels: " + c.missingRelationships());
        assertTrue(c.complete());
    }

    @Test
    void updateNodePersistsToDisk(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);

        NodePipeline edit = new NodePipeline();
        edit.setLabel("Account");
        edit.setEnabled(true);
        edit.setSelect("SELECT foracid AS accountnum FROM tbaadm.gam WHERE ROWNUM <= ?");
        edit.getKey().add("accountNumber");
        edit.getMap().put("accountNumber", "accountnum");
        store.updateNode("account", edit);

        PipelineSet reloaded = new PipelineStore(temp).pipelines();
        NodePipeline account = reloaded.getNodes().stream()
                .filter(n -> "account".equals(n.getId())).findFirst().orElseThrow();
        assertTrue(account.isEnabled());
        assertTrue(account.getSelect().contains("foracid"));
        assertEquals("accountnum", account.getMap().get("accountNumber"));
    }

    @Test
    void updateUnknownIdFails() {
        PipelineStore store = new PipelineStore(MODEL);
        assertThrows(IllegalArgumentException.class, () -> store.updateNode("nope", new NodePipeline()));
        assertThrows(IllegalArgumentException.class, () -> store.updateRelationship("nope", new RelPipeline()));
    }

    // ---------------------------------------------------------- user-defined slots

    @Test
    void createsCustomNodeInExtensionsFile(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);

        NodePipeline in = new NodePipeline();
        in.setLabel("ComplianceReview");
        in.setDescription("Manual compliance review raised on an account");
        NodePipeline created = store.createNode(in);

        assertEquals("compliancereview", created.getId());
        assertTrue(created.isCustom());
        assertTrue(created.isEnabled());
        assertEquals("N", created.getDelFlag());
        assertTrue(created.getSelect().contains("TEMPLATE"), "a new node starts as a template");
        assertTrue(Files.exists(temp.resolve("extensions.json")));

        // Survives a reload and does not disturb schema coverage.
        PipelineStore reloaded = new PipelineStore(temp);
        assertEquals(SCHEMA_NODES + 1, reloaded.pipelines().getNodes().size());
        Coverage c = reloaded.coverage();
        assertTrue(c.complete());
        assertTrue(c.extraNodes().isEmpty(),
                () -> "custom nodes are allowed additions, not coverage errors: " + c.extraNodes());
        assertEquals(1, c.customNodes());
    }

    @Test
    void updatesCustomNodeWithoutRenamingItsLabel(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);
        NodePipeline in = new NodePipeline();
        in.setLabel("ComplianceReview");
        NodePipeline created = store.createNode(in);

        NodePipeline edit = new NodePipeline();
        edit.setLabel("SomethingElse");
        edit.setDescription("changed description");
        store.updateNode(created.getId(), edit);

        NodePipeline saved = new PipelineStore(temp).pipelines().getNodes().stream()
                .filter(n -> created.getId().equals(n.getId())).findFirst().orElseThrow();
        assertEquals("ComplianceReview", saved.getLabel(), "the graph label is immutable once created");
        assertEquals("changed description", saved.getDescription());
    }

    @Test
    void softDeleteHidesTheNodeButKeepsItForAudit(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);
        NodePipeline created = store.createNode(node("ComplianceReview"));

        PipelineStore.SoftDelete deleted = store.deleteNode(created.getId());
        assertEquals("Y", deleted.delFlag());
        assertTrue(store.nodes(false).stream().noneMatch(n -> created.getId().equals(n.getId())));

        PipelineStore reloaded = new PipelineStore(temp);
        NodePipeline onDisk = reloaded.nodes(true).stream()
                .filter(n -> created.getId().equals(n.getId())).findFirst().orElseThrow();
        assertEquals("Y", onDisk.getDelFlag());
        assertFalse(onDisk.isEnabled());

        reloaded.restoreNode(created.getId());
        NodePipeline restored = reloaded.nodes(false).stream()
                .filter(n -> created.getId().equals(n.getId())).findFirst().orElseThrow();
        assertEquals("N", restored.getDelFlag());
        assertFalse(restored.isEnabled(), "a restored mapping comes back disabled for review");
    }

    @Test
    void deletingACustomNodeCascadesToItsRelationships(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);
        store.createNode(node("ComplianceReview"));

        RelPipeline rel = new RelPipeline();
        rel.setType("REVIEWS");
        rel.setFrom(endpoint("Account"));
        rel.setTo(endpoint("ComplianceReview"));
        RelPipeline created = store.createRelationship(rel);

        PipelineStore.SoftDelete deleted = store.deleteNode("compliancereview");
        assertEquals(List.of(created.getId()), deleted.cascaded());
        assertTrue(store.relationships(false).stream().noneMatch(r -> created.getId().equals(r.getId())));
    }

    @Test
    void shippedNodeCanBeDeletedCascadingItsRelationships(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);

        long dependants = store.relationships(false).stream()
                .filter(r -> "Account".equals(r.getFrom() == null ? null : r.getFrom().getLabel())
                        || "Account".equals(r.getTo() == null ? null : r.getTo().getLabel()))
                .count();
        assertTrue(dependants > 0, "Account should have relationships to cascade");

        PipelineStore.SoftDelete deleted = store.deleteNode("account");
        assertEquals("node:account", deleted.slot());
        assertEquals(dependants, deleted.cascaded().size());

        // The shipped slot leaves the effective set, including its relationships...
        assertTrue(store.nodes(false).stream().noneMatch(n -> "account".equals(n.getId())));
        assertTrue(store.relationships(false).stream().noneMatch(r -> "Account".equals(
                r.getFrom() == null ? null : r.getFrom().getLabel())));
        // ...but it is still on disk for audit and still covers its schema item.
        assertTrue(store.nodes(true).stream().anyMatch(n -> "account".equals(n.getId()) && n.deleted()));
        Coverage c = store.coverage();
        assertTrue(c.complete(), "a deleted shipped slot is intentionally off, not missing");
        assertEquals(1, c.deletedNodes());
        assertEquals(dependants, c.deletedRelationships());

        // The label stays reserved so a custom slot cannot take over its old data.
        assertThrows(IllegalArgumentException.class, () -> store.createNode(node("Account")));
    }

    @Test
    void restoringAShippedNodeComesBackDisabledAndRelationshipsStayDeleted(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);
        List<String> cascaded = store.deleteNode("account").cascaded();

        PipelineStore reloaded = new PipelineStore(temp);
        reloaded.restoreNode("account");

        NodePipeline account = reloaded.nodes(false).stream()
                .filter(n -> "account".equals(n.getId())).findFirst().orElseThrow();
        assertEquals("N", account.getDelFlag());
        assertFalse(account.isEnabled(), "a restored mapping comes back disabled for review");
        // Restore is per-slot: each cascaded relationship must be restored on its own.
        for (String id : cascaded) {
            RelPipeline rel = reloaded.relSlot(id);
            assertNotNull(rel);
            assertEquals("Y", rel.getDelFlag());
            reloaded.restoreRelationship(id);
            assertFalse(reloaded.relSlot(id).isEnabled());
        }
        assertTrue(reloaded.coverage().complete());
        assertEquals(0, reloaded.coverage().deletedNodes());
        assertEquals(0, reloaded.coverage().deletedRelationships());
    }

    @Test
    void shippedRelationshipCanBeDeletedAndRestored(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);

        PipelineStore.SoftDelete deleted = store.deleteRelationship("performs");
        assertEquals("Y", deleted.delFlag());
        assertTrue(store.relationships(false).stream().noneMatch(r -> "performs".equals(r.getId())));
        assertTrue(store.nodes(false).stream().anyMatch(n -> "account".equals(n.getId())),
                "deleting an edge must not hide its endpoints");

        Coverage c = store.coverage();
        assertTrue(c.complete());
        assertEquals(1, c.deletedRelationships());

        PipelineStore reloaded = new PipelineStore(temp);
        reloaded.restoreRelationship("performs");
        RelPipeline rel = reloaded.relSlot("performs");
        assertEquals("N", rel.getDelFlag());
        assertFalse(rel.isEnabled());
        assertEquals(0, reloaded.coverage().deletedRelationships());
    }

    @Test
    void aDeletedSlotCannotBeEditedUntilRestored(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);
        store.deleteNode("account");
        store.deleteRelationship("performs");

        NodePipeline edit = new NodePipeline();
        edit.setLabel("Account");
        IllegalArgumentException node = assertThrows(IllegalArgumentException.class,
                () -> store.updateNode("account", edit));
        assertTrue(node.getMessage().contains("restore"), node.getMessage());

        RelPipeline relEdit = new RelPipeline();
        IllegalArgumentException rel = assertThrows(IllegalArgumentException.class,
                () -> store.updateRelationship("performs", relEdit));
        assertTrue(rel.getMessage().contains("restore"), rel.getMessage());

        store.restoreNode("account");
        store.updateNode("account", edit);
        assertEquals("Account", store.nodeSlot("account").getLabel());
    }

    @Test
    void customRelationshipMustUseExistingNodes(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);

        RelPipeline rel = new RelPipeline();
        rel.setType("REVIEWS");
        rel.setFrom(endpoint("Account"));
        rel.setTo(endpoint("DoesNotExist"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.createRelationship(rel));
        assertTrue(e.getMessage().contains("DoesNotExist"), e.getMessage());
    }

    @Test
    void duplicateRelationshipIsRejected(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);
        store.createNode(node("ComplianceReview"));

        RelPipeline rel = new RelPipeline();
        rel.setType("REVIEWS");
        rel.setFrom(endpoint("Account"));
        rel.setTo(endpoint("ComplianceReview"));
        store.createRelationship(rel);

        RelPipeline duplicate = new RelPipeline();
        duplicate.setType("REVIEWS");
        duplicate.setFrom(endpoint("Account"));
        duplicate.setTo(endpoint("ComplianceReview"));
        assertThrows(IllegalArgumentException.class, () -> store.createRelationship(duplicate));
    }

    @Test
    void customNodeLabelMustBeAValidNeo4jIdentifier(@TempDir Path temp) throws IOException {
        copy(MODEL, temp);
        PipelineStore store = new PipelineStore(temp);
        assertThrows(IllegalArgumentException.class, () -> store.createNode(node("bad label")));
        assertThrows(IllegalArgumentException.class, () -> store.createNode(node("1stNode")));
        // A label that already exists (shipped or custom) cannot be reused.
        assertThrows(IllegalArgumentException.class, () -> store.createNode(node("Account")));
    }

    private static NodePipeline node(String label) {
        NodePipeline n = new NodePipeline();
        n.setLabel(label);
        return n;
    }

    private static RelEndpoint endpoint(String label) {
        RelEndpoint e = new RelEndpoint();
        e.setLabel(label);
        return e;
    }

    private static void copy(Path from, Path to) throws IOException {
        for (String f : new String[]{"schema.json", "pipelines.json", "extensions.json"}) {
            if (Files.exists(from.resolve(f))) Files.copy(from.resolve(f), to.resolve(f));
        }
    }
}

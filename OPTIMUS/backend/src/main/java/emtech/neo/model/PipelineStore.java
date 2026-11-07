package one.neo.model;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads the three files that define the flexible pipeline:
 *
 *   schema.json     - shipped static model (shape, keys, constraints)
 *   pipelines.json  - editable SELECT + mapping for every SHIPPED slot
 *   extensions.json - USER-DEFINED nodes/relationships (never wiped by a schema refresh)
 *
 * The effective pipeline set is schema slots + active extension slots. A slot is
 * active unless it carries delFlag = "Y" (soft delete, kept for audit).
 */
@Service
public class PipelineStore {

    private static final Logger log = LoggerFactory.getLogger(PipelineStore.class);

    /** Neo4j labels and relationship types we are willing to create from the UI. */
    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private static final String NODE_TEMPLATE =
            "-- TEMPLATE: replace with real SQL, then Save to rebuild the property map\n"
                    + "SELECT <column> AS <property> FROM <owner>.<table>";

    private static final String REL_TEMPLATE =
            "-- TEMPLATE: replace with the rows that carry both endpoint keys\n"
                    + "SELECT <fromKey> AS <fromProperty>, <toKey> AS <toProperty> FROM <owner>.<table>";

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile JsonNode schema;
    /** pipelines.json: one slot per shipped schema node/relationship. */
    private volatile PipelineSet base = new PipelineSet();
    /** extensions.json: user-defined slots (including soft-deleted ones). */
    private volatile ExtensionSet ext = new ExtensionSet();
    /** base + active extension slots: what the runner/UI actually use. */
    private volatile PipelineSet pipelines = new PipelineSet();

    @Autowired
    public PipelineStore(@Value("${neo.model.dir:model}") String dir) {
        this(Paths.get(dir));
    }

    public PipelineStore(Path dir) {
        this.dir = dir;
        reload();
    }

    public synchronized void reload() {
        this.schema = readTree("schema.json");
        this.base = readPipelines();
        this.ext = readExtensions();
        rebuild();
        Coverage c = coverage();
        log.info("model loaded: {} shipped + {} custom nodes ({} deleted), {} shipped + {} custom relationships ({} deleted) from {}",
                base.getNodes().size(), c.customNodes(), c.deletedNodes(),
                base.getRelationships().size(), c.customRelationships(), c.deletedRelationships(),
                dir.toAbsolutePath());
    }

    public JsonNode schema() { return schema; }

    /** Effective pipeline set: shipped slots + active user-defined slots. */
    public PipelineSet pipelines() { return pipelines; }

    public Path dir() { return dir; }

    /** Node slots for the UI; deleted ones only when explicitly requested. */
    public List<NodePipeline> nodes(boolean includeDeleted) {
        if (!includeDeleted) return List.copyOf(pipelines.getNodes());
        List<NodePipeline> out = new ArrayList<>(base.getNodes());
        out.addAll(ext.getNodes());
        return out;
    }

    public List<RelPipeline> relationships(boolean includeDeleted) {
        if (!includeDeleted) return List.copyOf(pipelines.getRelationships());
        List<RelPipeline> out = new ArrayList<>(base.getRelationships());
        out.addAll(ext.getRelationships());
        return out;
    }

    /**
     * A node slot by id, soft-deleted included. Detach delete needs the label even
     * after the mapping was deleted, so it must not go through the effective set.
     */
    public NodePipeline nodeSlot(String id) {
        int i = indexOf(base.getNodes(), id);
        if (i >= 0) return base.getNodes().get(i);
        i = indexOf(ext.getNodes(), id);
        return i < 0 ? null : ext.getNodes().get(i);
    }

    public RelPipeline relSlot(String id) {
        int i = indexOfRel(base.getRelationships(), id);
        if (i >= 0) return base.getRelationships().get(i);
        i = indexOfRel(ext.getRelationships(), id);
        return i < 0 ? null : ext.getRelationships().get(i);
    }

    /** True when the slot lives in pipelines.json (shipped), not extensions.json. */
    public boolean isShippedNode(String id) { return indexOf(base.getNodes(), id) >= 0; }

    public boolean isShippedRel(String id) { return indexOfRel(base.getRelationships(), id) >= 0; }

    // ---------------------------------------------------------------- update

    public synchronized NodePipeline updateNode(String id, NodePipeline in) {
        if (in == null) throw new IllegalArgumentException("node body required");
        int bi = indexOf(base.getNodes(), id);
        if (bi >= 0) {
            NodePipeline existing = base.getNodes().get(bi);
            if (existing.deleted()) {
                throw new IllegalArgumentException("node " + id + " is soft-deleted - restore it before editing");
            }
            in.setId(id);
            // The shipped label is the graph identity: never rename it from the UI.
            in.setLabel(existing.getLabel());
            in.setCustom(false);
            in.setDelFlag("N");
            if (in.getLayer() == null || in.getLayer().isBlank()) in.setLayer(existing.getLayer());
            base.getNodes().set(bi, in);
            saveBase();
            rebuild();
            return in;
        }
        int ei = indexOf(ext.getNodes(), id);
        if (ei >= 0) {
            NodePipeline existing = ext.getNodes().get(ei);
            if (existing.deleted()) {
                throw new IllegalArgumentException("node " + id + " is soft-deleted - restore it before editing");
            }
            in.setId(id);
            in.setLabel(existing.getLabel());
            in.setCustom(true);
            in.setDelFlag("N");
            if (in.getLayer() == null || in.getLayer().isBlank()) in.setLayer(existing.getLayer());
            ext.getNodes().set(ei, in);
            saveExt();
            rebuild();
            return in;
        }
        throw new IllegalArgumentException("unknown node pipeline " + id);
    }

    public synchronized RelPipeline updateRelationship(String id, RelPipeline in) {
        if (in == null) throw new IllegalArgumentException("relationship body required");
        int bi = indexOfRel(base.getRelationships(), id);
        if (bi >= 0) {
            RelPipeline existing = base.getRelationships().get(bi);
            if (existing.deleted()) {
                throw new IllegalArgumentException("relationship " + id + " is soft-deleted - restore it before editing");
            }
            in.setId(id);
            in.setType(existing.getType());
            // Shipped endpoints are declared by schema.json; keep the labels and
            // accept the submitted key maps.
            keepEndpointLabels(in, existing);
            in.setCustom(false);
            in.setDelFlag("N");
            if (in.getLayer() == null || in.getLayer().isBlank()) in.setLayer(existing.getLayer());
            base.getRelationships().set(bi, in);
            saveBase();
            rebuild();
            return in;
        }
        int ei = indexOfRel(ext.getRelationships(), id);
        if (ei >= 0) {
            RelPipeline existing = ext.getRelationships().get(ei);
            if (existing.deleted()) {
                throw new IllegalArgumentException("relationship " + id + " is soft-deleted - restore it before editing");
            }
            in.setId(id);
            in.setType(existing.getType());
            if (in.getFrom() == null) in.setFrom(existing.getFrom());
            if (in.getTo() == null) in.setTo(existing.getTo());
            validateEndpoints(id, in.getType(), in.getFrom(), in.getTo());
            in.setCustom(true);
            in.setDelFlag("N");
            if (in.getLayer() == null || in.getLayer().isBlank()) in.setLayer(existing.getLayer());
            ext.getRelationships().set(ei, in);
            saveExt();
            rebuild();
            return in;
        }
        throw new IllegalArgumentException("unknown relationship pipeline " + id);
    }

    // ---------------------------------------------------------------- create

    public synchronized NodePipeline createNode(NodePipeline in) {
        if (in == null) throw new IllegalArgumentException("node body required");
        String label = trimmed(in.getLabel());
        requireIdent(label, "node label");
        ensureLabelFree(label, null);
        String id = uniqueNodeId(idFor(in.getId(), label));
        in.setId(id);
        in.setLabel(label);
        in.setCustom(true);
        in.setDelFlag("N");
        in.setEnabled(true);
        if (in.getLayer() == null || in.getLayer().isBlank()) in.setLayer("usecase");
        if (in.getSelect() == null || in.getSelect().isBlank()) in.setSelect(NODE_TEMPLATE);
        ext.getNodes().add(in);
        saveExt();
        rebuild();
        log.info("custom node created: {} ({})", id, label);
        return in;
    }

    public synchronized RelPipeline createRelationship(RelPipeline in) {
        if (in == null) throw new IllegalArgumentException("relationship body required");
        String type = trimmed(in.getType());
        requireIdent(type, "relationship type");
        if (in.getFrom() == null || in.getTo() == null) {
            throw new IllegalArgumentException("a relationship needs both a from and a to endpoint");
        }
        in.getFrom().setLabel(trimmed(in.getFrom().getLabel()));
        in.getTo().setLabel(trimmed(in.getTo().getLabel()));
        validateEndpoints(null, type, in.getFrom(), in.getTo());
        String id = uniqueRelId(idFor(in.getId(), type + "-" + in.getFrom().getLabel() + "-" + in.getTo().getLabel()));
        in.setId(id);
        in.setType(type);
        in.setCustom(true);
        in.setDelFlag("N");
        in.setEnabled(true);
        if (in.getLayer() == null || in.getLayer().isBlank()) in.setLayer("compliance");
        if (in.getSelect() == null || in.getSelect().isBlank()) in.setSelect(REL_TEMPLATE);
        ext.getRelationships().add(in);
        saveExt();
        rebuild();
        log.info("custom relationship created: {} ({} {} -> {})",
                id, type, in.getFrom().getLabel(), in.getTo().getLabel());
        return in;
    }

    // ---------------------------------------------------------------- delete / restore

    /** What a soft delete did: the slot, its delFlag, and any cascaded relationships. */
    public record SoftDelete(String slot, String id, String delFlag, List<String> cascaded) {}

    /**
     * Soft-delete ANY slot - shipped or user-defined - by setting delFlag = "Y".
     *
     * The slot stays in pipelines.json / extensions.json for audit, but leaves the
     * effective set: no sync, no neo_* constraint, no list entry. Graph data is NOT
     * touched - that is the separate "detach delete" action.
     *
     * Relationships that point at the node are soft-deleted too, otherwise the model
     * would contain an edge to a node that no longer exists.
     */
    public synchronized SoftDelete deleteNode(String id) {
        NodePipeline node = nodeSlot(id);
        if (node == null) throw new IllegalArgumentException("unknown node pipeline " + id);
        boolean shipped = isShippedNode(id);
        node.setDelFlag("Y");
        node.setEnabled(false);

        List<String> cascaded = new ArrayList<>();
        boolean baseTouched = shipped;
        boolean extTouched = !shipped;
        for (RelPipeline r : base.getRelationships()) {
            if (r.deleted() || !touchesNode(r, node.getLabel())) continue;
            r.setDelFlag("Y");
            r.setEnabled(false);
            cascaded.add(r.getId());
            baseTouched = true;
        }
        for (RelPipeline r : ext.getRelationships()) {
            if (r.deleted() || !touchesNode(r, node.getLabel())) continue;
            r.setDelFlag("Y");
            r.setEnabled(false);
            cascaded.add(r.getId());
            extTouched = true;
        }
        if (baseTouched) saveBase();
        if (extTouched) saveExt();
        rebuild();
        log.info("node soft-deleted: {} (shipped={}, cascaded relationships: {})", id, shipped, cascaded);
        return new SoftDelete("node:" + id, id, "Y", cascaded);
    }

    public synchronized SoftDelete deleteRelationship(String id) {
        RelPipeline rel = relSlot(id);
        if (rel == null) throw new IllegalArgumentException("unknown relationship pipeline " + id);
        boolean shipped = isShippedRel(id);
        rel.setDelFlag("Y");
        rel.setEnabled(false);
        if (shipped) saveBase();
        else saveExt();
        rebuild();
        log.info("relationship soft-deleted: {} (shipped={})", id, shipped);
        return new SoftDelete("rel:" + id, id, "Y", List.of());
    }

    public synchronized NodePipeline restoreNode(String id) {
        NodePipeline node = nodeSlot(id);
        if (node == null) throw new IllegalArgumentException("unknown node " + id);
        if (!node.deleted()) return node;
        node.setDelFlag("N");
        // Stay disabled: a restored mapping should be reviewed before it syncs.
        node.setEnabled(false);
        if (isShippedNode(id)) saveBase();
        else saveExt();
        rebuild();
        log.info("node restored (disabled): {}", id);
        return node;
    }

    public synchronized RelPipeline restoreRelationship(String id) {
        RelPipeline rel = relSlot(id);
        if (rel == null) throw new IllegalArgumentException("unknown relationship " + id);
        if (!rel.deleted()) return rel;
        rel.setDelFlag("N");
        rel.setEnabled(false);
        if (isShippedRel(id)) saveBase();
        else saveExt();
        rebuild();
        log.info("relationship restored (disabled): {}", id);
        return rel;
    }

    // ---------------------------------------------------------------- coverage

    public Coverage coverage() {
        Set<String> schemaNodes = new LinkedHashSet<>();
        for (JsonNode n : schema.path("nodes")) schemaNodes.add(n.path("label").asText());

        Set<String> schemaRels = new LinkedHashSet<>();
        for (JsonNode r : schema.path("relationships")) {
            schemaRels.add(r.path("type").asText() + "|" + r.path("from").asText() + "|" + r.path("to").asText());
        }

        Set<String> pipeNodes = new LinkedHashSet<>();
        int deletedNodes = 0;
        for (NodePipeline n : base.getNodes()) {
            // A soft-deleted shipped slot still "covers" its schema item: the slot
            // exists, it is deliberately switched off. Counting it as missing would
            // leave the completeness check permanently red after an intentional delete.
            if (n.deleted()) deletedNodes++;
            if (n.getLabel() != null) pipeNodes.add(n.getLabel());
        }
        int customNodes = 0;
        for (NodePipeline n : ext.getNodes()) if (!n.deleted()) customNodes++;

        Set<String> pipeRels = new LinkedHashSet<>();
        int deletedRels = 0;
        for (RelPipeline r : base.getRelationships()) {
            if (r.deleted()) deletedRels++;
            String from = r.getFrom() == null ? "?" : r.getFrom().getLabel();
            String to = r.getTo() == null ? "?" : r.getTo().getLabel();
            pipeRels.add(r.getType() + "|" + from + "|" + to);
        }
        int customRels = 0;
        for (RelPipeline r : ext.getRelationships()) if (!r.deleted()) customRels++;

        return new Coverage(
                schemaNodes.size(), schemaRels.size(),
                notIn(schemaNodes, pipeNodes), notIn(schemaRels, pipeRels),
                notIn(pipeNodes, schemaNodes), notIn(pipeRels, schemaRels),
                customNodes, customRels, deletedNodes, deletedRels);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Build the effective set = shipped slots + active extension slots, skipping
     * (and logging) any extension entry that would clash with another slot.
     */
    private void rebuild() {
        PipelineSet merged = new PipelineSet();
        Set<String> labels = new HashSet<>();
        for (NodePipeline n : base.getNodes()) {
            n.setCustom(false);
            if (n.getDelFlag() == null || n.getDelFlag().isBlank()) n.setDelFlag("N");
            // The label stays reserved even when the slot is deleted: a custom slot
            // must never take over a shipped label and MERGE into its old data.
            if (n.getLabel() != null) labels.add(n.getLabel().toLowerCase(Locale.ROOT));
            if (n.deleted()) continue;
            merged.getNodes().add(n);
        }
        for (NodePipeline n : ext.getNodes()) {
            n.setCustom(true);
            if (n.getDelFlag() == null || n.getDelFlag().isBlank()) n.setDelFlag("N");
            if (n.deleted()) continue;
            String label = trimmed(n.getLabel());
            if (label.isEmpty()) {
                log.warn("extensions.json: custom node '{}' has no label - skipped", n.getId());
                continue;
            }
            if (!labels.add(label.toLowerCase(Locale.ROOT))) {
                log.warn("extensions.json: custom node '{}' label '{}' clashes with another slot - skipped",
                        n.getId(), label);
                continue;
            }
            merged.getNodes().add(n);
        }

        Set<String> relKeys = new HashSet<>();
        for (RelPipeline r : base.getRelationships()) {
            r.setCustom(false);
            if (r.getDelFlag() == null || r.getDelFlag().isBlank()) r.setDelFlag("N");
            relKeys.add(relKey(r));
            if (r.deleted()) continue;
            merged.getRelationships().add(r);
        }
        for (RelPipeline r : ext.getRelationships()) {
            r.setCustom(true);
            if (r.getDelFlag() == null || r.getDelFlag().isBlank()) r.setDelFlag("N");
            if (r.deleted()) continue;
            if (!relKeys.add(relKey(r))) {
                log.warn("extensions.json: custom relationship '{}' duplicates {} {} -> {} - skipped",
                        r.getId(), r.getType(),
                        r.getFrom() == null ? "?" : r.getFrom().getLabel(),
                        r.getTo() == null ? "?" : r.getTo().getLabel());
                continue;
            }
            merged.getRelationships().add(r);
        }
        this.pipelines = merged;
    }

    private static String relKey(RelPipeline r) {
        String from = r.getFrom() == null ? "?" : trimmed(r.getFrom().getLabel());
        String to = r.getTo() == null ? "?" : trimmed(r.getTo().getLabel());
        return relKey(r.getType(), from, to);
    }

    private static String relKey(String type, String from, String to) {
        return trimmed(type) + "|" + from + "|" + to;
    }

    /** Validate that a relationship's endpoints exist among active nodes and do not duplicate an edge. */
    private void validateEndpoints(String ignoreRelId, String type, RelEndpoint from, RelEndpoint to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("a relationship needs both a from and a to endpoint");
        }
        Set<String> labels = new HashSet<>();
        for (NodePipeline n : pipelines.getNodes()) labels.add(trimmed(n.getLabel()).toLowerCase(Locale.ROOT));
        if (!labels.contains(trimmed(from.getLabel()).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unknown from endpoint node '" + from.getLabel() + "'");
        }
        if (!labels.contains(trimmed(to.getLabel()).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unknown to endpoint node '" + to.getLabel() + "'");
        }
        for (RelPipeline r : pipelines.getRelationships()) {
            if (ignoreRelId != null && ignoreRelId.equals(r.getId())) continue;
            if (relKey(r).equalsIgnoreCase(relKey(type, from.getLabel(), to.getLabel()))) {
                throw new IllegalArgumentException("relationship " + type + " from " + from.getLabel()
                        + " to " + to.getLabel() + " already exists");
            }
        }
    }

    private void keepEndpointLabels(RelPipeline in, RelPipeline existing) {
        if (in.getFrom() == null) in.setFrom(existing.getFrom());
        else if (existing.getFrom() != null) in.getFrom().setLabel(existing.getFrom().getLabel());
        if (in.getTo() == null) in.setTo(existing.getTo());
        else if (existing.getTo() != null) in.getTo().setLabel(existing.getTo().getLabel());
    }

    private void ensureLabelFree(String label, String ignoreId) {
        for (NodePipeline n : allNodeSlots()) {
            if (ignoreId != null && ignoreId.equals(n.getId())) continue;
            if (label.equalsIgnoreCase(trimmed(n.getLabel()))) {
                throw new IllegalArgumentException("node label '" + label + "' already exists"
                        + (n.deleted() ? " (as a soft-deleted node - restore it instead)" : ""));
            }
        }
    }

    private String uniqueNodeId(String wanted) {
        Set<String> used = new HashSet<>();
        for (NodePipeline n : allNodeSlots()) used.add(n.getId());
        return unique(wanted, used);
    }

    private String uniqueRelId(String wanted) {
        Set<String> used = new HashSet<>();
        for (RelPipeline r : base.getRelationships()) used.add(r.getId());
        for (RelPipeline r : ext.getRelationships()) used.add(r.getId());
        return unique(wanted, used);
    }

    private static String unique(String wanted, Set<String> used) {
        if (!used.contains(wanted)) return wanted;
        for (int i = 2; ; i++) {
            String candidate = wanted + "-" + i;
            if (!used.contains(candidate)) return candidate;
        }
    }

    private static String idFor(String requested, String fallback) {
        String raw = requested == null || requested.isBlank() ? fallback : requested;
        String slug = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        return slug.isEmpty() ? "custom" : slug;
    }

    private static void requireIdent(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(what + " is required");
        if (!IDENT.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " '" + value
                    + "' must start with a letter and contain only letters, digits and underscore");
        }
    }

    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
    }

    private List<NodePipeline> allNodeSlots() {
        List<NodePipeline> out = new ArrayList<>(base.getNodes());
        out.addAll(ext.getNodes());
        return out;
    }

    private static boolean touchesNode(RelPipeline r, String label) {
        if (label == null) return false;
        return (r.getFrom() != null && label.equals(r.getFrom().getLabel()))
                || (r.getTo() != null && label.equals(r.getTo().getLabel()));
    }

    private static int indexOf(List<NodePipeline> list, String id) {
        for (int i = 0; i < list.size(); i++) if (id.equals(list.get(i).getId())) return i;
        return -1;
    }

    private static int indexOfRel(List<RelPipeline> list, String id) {
        for (int i = 0; i < list.size(); i++) if (id.equals(list.get(i).getId())) return i;
        return -1;
    }

    private void saveBase() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("pipelines.json").toFile(), base);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write pipelines.json in " + dir.toAbsolutePath(), e);
        }
    }

    private void saveExt() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("extensions.json").toFile(), ext);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write extensions.json in " + dir.toAbsolutePath(), e);
        }
    }

    private PipelineSet readPipelines() {
        try {
            return mapper.readValue(dir.resolve("pipelines.json").toFile(), PipelineSet.class);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read pipelines.json in " + dir.toAbsolutePath(), e);
        }
    }

    /** extensions.json is optional: absent means "no user-defined slots yet". */
    private ExtensionSet readExtensions() {
        Path file = dir.resolve("extensions.json");
        if (!Files.exists(file)) return new ExtensionSet();
        try {
            return mapper.readValue(file.toFile(), ExtensionSet.class);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read extensions.json in " + dir.toAbsolutePath(), e);
        }
    }

    private JsonNode readTree(String name) {
        try {
            return mapper.readTree(dir.resolve(name).toFile());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + name + " in " + dir.toAbsolutePath(), e);
        }
    }

    private static List<String> notIn(Set<String> wanted, Set<String> have) {
        List<String> out = new ArrayList<>();
        for (String s : wanted) if (!have.contains(s)) out.add(s);
        return out;
    }
}

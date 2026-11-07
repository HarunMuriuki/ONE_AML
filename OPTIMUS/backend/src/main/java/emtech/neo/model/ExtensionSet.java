package one.neo.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Root of extensions.json: the USER-DEFINED part of the model.
 *
 * Kept apart from pipelines.json on purpose. pipelines.json mirrors schema.json
 * (one slot for every shipped node/relationship); extensions.json holds the extra
 * nodes/relationships a compliance need requires. Refreshing the shipped model
 * therefore never wipes user additions.
 *
 * Deleted entries stay in the file with delFlag = "Y" (soft delete, auditable);
 * they are filtered out when the effective pipeline set is built.
 */
public class ExtensionSet {

    private String version = "1.0.0";
    private String notes;
    private List<NodePipeline> nodes = new ArrayList<>();
    private List<RelPipeline> relationships = new ArrayList<>();

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public List<NodePipeline> getNodes() { return nodes; }
    public void setNodes(List<NodePipeline> nodes) { this.nodes = nodes == null ? new ArrayList<>() : nodes; }

    public List<RelPipeline> getRelationships() { return relationships; }
    public void setRelationships(List<RelPipeline> relationships) {
        this.relationships = relationships == null ? new ArrayList<>() : relationships;
    }
}

package one.neo.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Root of pipelines.json: the fully-editable data layer.
 * One slot per node and per relationship of the static model.
 */
public class PipelineSet {

    private String schema = "schema.json";
    private String version = "1.0.0";
    private String notes;
    private Defaults defaults = new Defaults();
    private List<NodePipeline> nodes = new ArrayList<>();
    private List<RelPipeline> relationships = new ArrayList<>();

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Defaults getDefaults() { return defaults; }
    public void setDefaults(Defaults defaults) { this.defaults = defaults; }

    public List<NodePipeline> getNodes() { return nodes; }
    public void setNodes(List<NodePipeline> nodes) { this.nodes = nodes == null ? new ArrayList<>() : nodes; }

    public List<RelPipeline> getRelationships() { return relationships; }
    public void setRelationships(List<RelPipeline> relationships) {
        this.relationships = relationships == null ? new ArrayList<>() : relationships;
    }

    public static class Defaults {
        private int previewLimit = 50;
        private int maxRows = 5000;
        private int batchSize = 1000;

        public int getPreviewLimit() { return previewLimit; }
        public void setPreviewLimit(int previewLimit) { this.previewLimit = previewLimit; }

        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }
}

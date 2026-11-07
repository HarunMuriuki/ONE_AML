package one.neo.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Editable mapping for one relationship of the static model (schema.json).
 * select -> rows carrying both endpoint keys; from/to key maps pick them out;
 * map -> relationship property -> row column.
 */
public class RelPipeline {

    private String id;
    private String type;
    private String layer = "core";
    /** True when this slot comes from extensions.json (user-defined), not schema.json. */
    private boolean custom;
    /** Human description shown in the editor and the list. */
    private String description;
    private boolean enabled;
    private RelEndpoint from;
    private RelEndpoint to;
    private Map<String, String> map = new LinkedHashMap<>();
    private String select;
    private String notes;
    /**
     * Optional hand-written MERGE Cypher. Blank/absent = auto-generated from
     * endpoints+map by CypherBuilder. Set = used verbatim (the editor's Cypher box).
     */
    private String cypher;
    /** Soft-delete marker for user-defined slots: "Y" = deleted (kept for audit). */
    private String delFlag = "N";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }

    public boolean isCustom() { return custom; }
    public void setCustom(boolean custom) { this.custom = custom; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }

    /** Not a bean getter, so it is not serialized as an extra JSON field. */
    public boolean deleted() { return "Y".equalsIgnoreCase(delFlag); }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public RelEndpoint getFrom() { return from; }
    public void setFrom(RelEndpoint from) { this.from = from; }

    public RelEndpoint getTo() { return to; }
    public void setTo(RelEndpoint to) { this.to = to; }

    public Map<String, String> getMap() { return map; }
    public void setMap(Map<String, String> map) { this.map = map == null ? new LinkedHashMap<>() : map; }

    public String getSelect() { return select; }
    public void setSelect(String select) { this.select = select; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    /** Hand-written MERGE Cypher override, or null/blank to auto-generate. */
    public String getCypher() { return cypher; }
    public void setCypher(String cypher) { this.cypher = cypher; }
}

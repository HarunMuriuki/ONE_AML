package one.neo.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editable mapping for one node of the static model (schema.json).
 * select -> Oracle rows; key -> graph identity props; map -> graphProp -> rowColumn.
 */
public class NodePipeline {

    private String id;
    private String label;
    private String layer = "core";
    /** True when this slot comes from extensions.json (user-defined), not schema.json. */
    private boolean custom;
    /** Human description shown in the editor and the list (e.g. "Customer accounts"). */
    private String description;
    private boolean enabled;
    private List<String> key = new ArrayList<>();
    private Map<String, String> map = new LinkedHashMap<>();
    private String select;
    private String notes;
    /**
     * Optional hand-written MERGE Cypher. Blank/absent = auto-generated from
     * key+map by CypherBuilder. Set = used verbatim (the editor's Cypher box).
     */
    private String cypher;
    /**
     * Soft-delete marker for user-defined slots: "Y" = deleted. The entry stays
     * in extensions.json for audit, is hidden from the UI and is never synced.
     * Shipped schema slots are never deleted - disable them instead.
     */
    private String delFlag = "N";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

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

    public List<String> getKey() { return key; }
    public void setKey(List<String> key) { this.key = key == null ? new ArrayList<>() : key; }

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

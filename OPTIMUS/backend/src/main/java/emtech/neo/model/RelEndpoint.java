package one.neo.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One side of a relationship: the node label plus, per graph key property,
 * the row column that carries it. Supports composite keys (e.g. Address).
 */
public class RelEndpoint {

    private String label;
    private Map<String, String> key = new LinkedHashMap<>();

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Map<String, String> getKey() { return key; }
    public void setKey(Map<String, String> key) { this.key = key == null ? new LinkedHashMap<>() : key; }
}

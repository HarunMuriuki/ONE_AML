package one.neo.model;

import java.util.List;

/**
 * Schema vs pipelines completeness: proves no shipped node or relationship is
 * missing a slot.
 *
 * User-defined slots from extensions.json are NOT counted as missing/extra - they
 * are allowed additions. They are reported separately as customNodes /
 * customRelationships so the UI can show "20 / 26 + N custom".
 *
 * deletedNodes / deletedRelationships count shipped slots that were deliberately
 * soft-deleted (delFlag = "Y"). They still satisfy coverage - the slot exists, it
 * is just switched off - but the UI shows the number so the state is visible.
 */
public record Coverage(
        int schemaNodes,
        int schemaRelationships,
        List<String> missingNodes,
        List<String> missingRelationships,
        List<String> extraNodes,
        List<String> extraRelationships,
        int customNodes,
        int customRelationships,
        int deletedNodes,
        int deletedRelationships) {

    public boolean complete() {
        return missingNodes.isEmpty() && missingRelationships.isEmpty();
    }
}

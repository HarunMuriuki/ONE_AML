package one.neo.graph;

import one.neo.model.NodePipeline;
import one.neo.model.RelEndpoint;
import one.neo.model.RelPipeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CypherBuilderTest {

    @Test
    void singleKeyNodeHasGuardMergeAndSet() {
        NodePipeline n = new NodePipeline();
        n.setId("account");
        n.setLabel("Account");
        n.getKey().add("accountNumber");
        n.getMap().put("accountNumber", "accountnum");
        n.getMap().put("accountType", "schemetype");

        String c = CypherBuilder.forNode(n);
        assertTrue(c.contains("UNWIND $rows AS r"));
        assertTrue(c.contains("WITH r WHERE r.accountnum IS NOT NULL"));
        assertTrue(c.contains("MERGE (n:Account {accountNumber: toString(r.accountnum)})"));
        assertTrue(c.contains("SET n.accountType = r.schemetype"));
        assertFalse(c.contains("SET n.accountNumber"), "key props are not re-set");
    }

    @Test
    void compositeKeyNodeGuardUsesAllKeyColumns() {
        NodePipeline n = new NodePipeline();
        n.setLabel("Address");
        n.getKey().add("addressLine1");
        n.getKey().add("postCode");
        n.getMap().put("addressLine1", "line1");
        n.getMap().put("postCode", "postcode");

        String c = CypherBuilder.forNode(n);
        assertTrue(c.contains("r.line1 IS NOT NULL AND r.postcode IS NOT NULL"));
        assertTrue(c.contains("addressLine1: toString(r.line1), postCode: toString(r.postcode)"));
    }

    @Test
    void relationshipMatchesBothEndpoints() {
        RelPipeline r = new RelPipeline();
        r.setId("performs");
        r.setType("PERFORMS");
        r.setFrom(endpoint("Account", "accountNumber", "accountnum"));
        r.setTo(endpoint("Transaction", "transactionId", "tranid"));

        String c = CypherBuilder.forRelationship(r);
        assertTrue(c.contains("MATCH (a:Account {accountNumber: toString(r.accountnum)})"));
        assertTrue(c.contains("MATCH (b:Transaction {transactionId: toString(r.tranid)})"));
        assertTrue(c.contains("MERGE (a)-[:PERFORMS]->(b)"));
    }

    @Test
    void relationshipWithPropsUsesRelVariable() {
        RelPipeline r = new RelPipeline();
        r.setType("HAS_ACCOUNT");
        r.setFrom(endpoint("Customer", "customerId", "cifid"));
        r.setTo(endpoint("Account", "accountNumber", "accountnum"));
        r.getMap().put("since", "since");

        String c = CypherBuilder.forRelationship(r);
        assertTrue(c.contains("MERGE (a)-[rel:HAS_ACCOUNT]->(b)"));
        assertTrue(c.contains("SET rel.since = r.since"));
    }

    @Test
    void endpointWithoutKeyThrows() {
        RelPipeline r = new RelPipeline();
        r.setId("ip_located_in_location");
        r.setType("LOCATED_IN");
        r.setFrom(endpoint("IP", "ipAddress", "ipaddress"));
        RelEndpoint loc = new RelEndpoint();
        loc.setLabel("Location");
        r.setTo(loc);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CypherBuilder.forRelationship(r));
        assertTrue(e.getMessage().contains("no key mapping"));
    }

    @Test
    void nodeKeyMissingColumnThrows() {
        NodePipeline n = new NodePipeline();
        n.setLabel("Account");
        n.getKey().add("accountNumber");
        assertThrows(IllegalArgumentException.class, () -> CypherBuilder.forNode(n));
    }

    private static RelEndpoint endpoint(String label, String keyProp, String column) {
        RelEndpoint e = new RelEndpoint();
        e.setLabel(label);
        e.getKey().put(keyProp, column);
        return e;
    }
}

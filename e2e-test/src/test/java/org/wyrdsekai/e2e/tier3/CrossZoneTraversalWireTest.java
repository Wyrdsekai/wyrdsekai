package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.QuotaPolicy;
import org.wyrdsekai.common.model.TransitInventory;
import org.wyrdsekai.common.model.TransitReputation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E wire-protocol tests for cross-zone traversal.
 *
 * <p>Verifies that the session.open payload format is correct end-to-end:
 * inventory + reputation + transit token all serialize/deserialize cleanly.
 * These tests don't require running infrastructure — they verify the contract
 * between source zone (RemoteZoneSession) and destination zone (VirtualSessionHandler).</p>
 */
@Tag("e2e")
class CrossZoneTraversalWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void session_open_payload_with_full_context() throws Exception {
        // Simulate what RemoteZoneSession would send
        var inventory = new TransitInventory("alpha", List.of(
            TransitInventory.TransitItem.simple(
                "sword-1", "iron sword", "sharp", true, List.of("sword", "blade")),
            new TransitInventory.TransitItem(
                "card-1", "library card", "magical", true, List.of("card"),
                "function invoke(p) { return {response: world.library.search(p.query)}; }",
                "library_card",
                Map.of())));

        var reputation = new TransitReputation(
            "did:key:alice", 45, 1, 3, 0, 0.72, "alpha");

        var payload = mapper.createObjectNode();
        payload.put("sessionId", "test-session-123");
        payload.put("transitToken", "tok-abc");
        payload.put("playerId", "did:key:alice");
        payload.put("playerName", "Alice");
        payload.put("localZoneId", "alpha");
        payload.set("inventory", mapper.valueToTree(inventory));
        payload.set("reputation", mapper.valueToTree(reputation));

        // Serialize → deserialize (simulating wire transit)
        var json = mapper.writeValueAsString(payload);
        var restored = mapper.readTree(json);

        // Verify session metadata
        assertEquals("test-session-123", restored.get("sessionId").asText());
        assertEquals("Alice", restored.get("playerName").asText());

        // Verify inventory
        var invRestored = mapper.treeToValue(restored.get("inventory"), TransitInventory.class);
        assertEquals("alpha", invRestored.sourceZone());
        assertEquals(2, invRestored.items().size());
        assertNotNull(invRestored.items().get(1).scriptSource());
        assertTrue(invRestored.items().get(1).scriptSource().contains("world.library"));

        // Verify reputation
        var repRestored = mapper.treeToValue(restored.get("reputation"), TransitReputation.class);
        assertEquals(0.72, repRestored.compositeScore());
        assertEquals("verified", repRestored.permissionTier());
    }

    @Test
    void inventory_delta_wire_format() throws Exception {
        // What VirtualSessionHandler sends back on session.close
        var delta = new TransitInventory.TransitDelta(
            List.of("sword-1", "potion-2"),  // dropped in remote zone
            List.of(TransitInventory.TransitItem.simple(
                "souvenir-1", "souvenir gem", "from zone beta", true, List.of("gem"))));

        var json = mapper.writeValueAsBytes(delta);
        var restored = mapper.readValue(json, TransitInventory.TransitDelta.class);

        assertEquals(2, restored.removedItemIds().size());
        assertEquals(1, restored.addedItems().size());
        assertFalse(restored.isEmpty());
    }

    @Test
    void notification_wire_format_with_priority() throws Exception {
        var payload = mapper.createObjectNode();
        payload.put("priority", "critical");
        payload.put("fromAgent", "warden");
        payload.put("message", "Security alert in your home zone");
        payload.put("timestamp", System.currentTimeMillis());

        var json = mapper.writeValueAsString(payload);
        var restored = mapper.readTree(json);

        assertEquals("critical", restored.get("priority").asText());
        assertEquals("warden", restored.get("fromAgent").asText());
    }

    @Test
    void cross_zone_tell_wire_format() throws Exception {
        // What CrossZoneTellService publishes to federation.{zone}.tell
        var payload = mapper.createObjectNode();
        payload.put("fromEntityId", "did:key:alice");
        payload.put("fromEntityName", "Alice");
        payload.put("fromZone", "alpha");
        payload.put("targetName", "wyrd");
        payload.put("text", "hello from beta");
        payload.put("timestamp", System.currentTimeMillis());

        var json = mapper.writeValueAsString(payload);
        var restored = mapper.readTree(json);

        assertEquals("Alice", restored.get("fromEntityName").asText());
        assertEquals("wyrd", restored.get("targetName").asText());
        assertEquals("hello from beta", restored.get("text").asText());
    }

    @Test
    void bilateral_agreement_with_quotas_wire_format() throws Exception {
        // Bilateral agreement with quotas (v1 economy) serializes correctly
        var localQuota = QuotaPolicy.partner();
        var remoteQuota = QuotaPolicy.tourist();

        var json = mapper.writeValueAsString(Map.of(
            "localQuota", localQuota,
            "remoteQuota", remoteQuota));
        var tree = mapper.readTree(json);

        assertEquals(500_000L,
            tree.path("localQuota").path("inferenceTokensPerDay").asLong());
        assertEquals(50_000L,
            tree.path("remoteQuota").path("inferenceTokensPerDay").asLong());
        assertTrue(tree.path("localQuota").path("allowInventory").asBoolean());
        assertFalse(tree.path("remoteQuota").path("allowInventory").asBoolean());
    }

    @Test
    void empty_inventory_still_serializes_correctly() throws Exception {
        var inventory = TransitInventory.empty("alpha");
        var json = mapper.writeValueAsString(inventory);
        var restored = mapper.readValue(json, TransitInventory.class);

        assertEquals("alpha", restored.sourceZone());
        assertTrue(restored.items().isEmpty());
    }

    @Test
    void all_quota_presets_serialize() throws Exception {
        for (var level : List.of("family", "partner", "tourist")) {
            var quota = QuotaPolicy.forTrustLevel(level);
            var json = mapper.writeValueAsString(quota);
            var restored = mapper.readValue(json, QuotaPolicy.class);
            assertEquals(quota.inferenceTokensPerDay(), restored.inferenceTokensPerDay(),
                "inference mismatch for " + level);
            assertEquals(quota.allowInventory(), restored.allowInventory(),
                "inventory mismatch for " + level);
        }
    }
}

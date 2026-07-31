package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.QuotaPolicy;
import org.wyrdsekai.common.model.TransitInventory;
import org.wyrdsekai.common.model.TransitReputation;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.economy.AttestationService;
import org.wyrdsekai.core.economy.MeteringService;
import org.wyrdsekai.core.economy.ReferenceRates;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-chain integration test for cross-zone traversal features.
 * Tests interactions between EntityRegistry, NotificationService, AttestationService,
 * MeteringService, CrossZoneTellService, and QuotaPolicy.
 */
class CrossZoneTraversalIntegrationTest {

    @BeforeEach
    void setup() {
        EntityRegistry.init();
        NotificationService.init();
        AttestationService.init();
        MeteringService.init();
        MeteringService.get().clear();
        CrossZoneTellService.init("alpha");
    }

    @Test
    void full_travel_lifecycle_tracks_state() {
        // Alice registers and travels to beta
        var registry = EntityRegistry.get();
        registry.enter("alice", "Alice", "player", "nexus");
        registry.setHomeZone("alice", "alpha");

        // Initial state: PRESENT
        assertEquals(EntityRegistry.PresenceState.PRESENT,
            registry.presenceOf("alice"));

        // Travel
        registry.setTraveling("alice", "beta");
        assertEquals(EntityRegistry.PresenceState.TRAVELING,
            registry.presenceOf("alice"));
        assertEquals("beta", registry.travelDestinationOf("alice").orElseThrow());

        // Return
        registry.setReturned("alice");
        assertEquals(EntityRegistry.PresenceState.PRESENT,
            registry.presenceOf("alice"));
        assertTrue(registry.travelDestinationOf("alice").isEmpty());
    }

    @Test
    void traveling_notifications_buffer_and_flush() {
        var delivered = new ArrayList<S2CMessage.Notification>();
        NotificationService.get().setDeliveryCallback((did, n) -> delivered.add(n));
        // Forwarder always fails — forces buffering
        NotificationService.get().setRemoteForwarder((did, zone, n) -> false);

        var registry = EntityRegistry.get();
        registry.enter("alice", "Alice", "player", "nexus");
        registry.setTraveling("alice", "beta");

        // 3 notifications while traveling
        NotificationService.get().notify("alice", "task1 done", "normal", "wyrd");
        NotificationService.get().notify("alice", "task2 done", "normal", "wyrd");
        NotificationService.get().notify("alice", "alert", "critical", "warden");

        assertEquals(3, NotificationService.get().bufferedCountFor("alice"));
        assertEquals(0, delivered.size());  // nothing delivered locally yet

        // Return home — buffered notifications flush
        registry.setReturned("alice");
        var flushed = NotificationService.get().flushBuffered("alice");

        assertEquals(3, flushed.size());
        assertEquals(3, delivered.size());
    }

    @Test
    void cross_zone_tell_routing_integrates() {
        var registry = EntityRegistry.get();
        var tellService = CrossZoneTellService.get();
        var routedTo = new ArrayList<String>();

        tellService.setRelayPublisher((subject, data) -> routedTo.add(subject));

        // Alice's companion Wyrd is in her home zone (alpha)
        registry.enter("alice", "Alice", "player", "nexus");
        registry.setHomeZone("alice", "alpha");

        // Case: "tell my wyrd" — routes to home
        var result1 = tellService.tell("alice", "Alice", "alpha", "my wyrd", "hello");
        // Home zone is alpha (local) — tries local first, wyrd not registered → fails
        // So no relay route for "my wyrd" when home == local

        // Case: explicit zone prefix "tell gamma.wyrd"
        var result2 = tellService.tell("alice", "Alice", "alpha", "gamma.wyrd", "hi");
        assertTrue(result2.delivered());
        assertEquals(1, routedTo.size());
        assertEquals("federation.gamma.tell", routedTo.get(0));
    }

    @Test
    void reputation_and_quota_integrate() {
        // New agent with no reputation → tourist tier
        var repEmpty = AttestationService.get().serializeForTransit(
            "did:key:new", "alpha");
        assertEquals("tourist", repEmpty.permissionTier());

        // Tourist quota for that agent is most restrictive
        var quota = QuotaPolicy.forTrustLevel(repEmpty.permissionTier());
        assertEquals(50_000L, quota.inferenceTokensPerDay());
        assertFalse(quota.allowInventory());
    }

    @Test
    void metering_reflects_cross_zone_inference() {
        // Simulate multiple cross-zone inference calls
        MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL, 5.0, "wyrd");
        MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL, 10.0, "wyrd");
        MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_LARGE, 2.0, "wyrd");

        var small = MeteringService.get().usageToday("beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL);
        var large = MeteringService.get().usageToday("beta",
            ReferenceRates.SERVICE_INFERENCE_LARGE);

        assertEquals(15, small.totalUnits());  // 5K + 10K
        assertEquals(15.0, small.totalCU());
        assertEquals(2, large.totalUnits());
        assertEquals(20.0, large.totalCU());  // 2K × 10 CU/1K
        assertEquals(17000, MeteringService.get().inferenceTokensToday("beta"));
    }

    @Test
    void family_bilateral_tracks_usage_but_zero_cu() {
        // Family bilateral = 0 multiplier → informational but no CU cost
        var cu = MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_GPU, 10.0, "wyrd", 0.0);  // family multiplier
        assertEquals(0.0, cu);

        var usage = MeteringService.get().usageToday("beta",
            ReferenceRates.SERVICE_GPU);
        assertEquals(10, usage.totalUnits());  // usage still tracked
        assertEquals(0.0, usage.totalCU());    // but CU is zero
    }

    @Test
    void transit_reputation_travels_with_entity() {
        // Alice earns reputation at home
        AttestationService.get().stewardEndorse(
            "did:key:steward", "did:key:alice", "reliable", 0.9);
        AttestationService.get().recordTaskOutcome("did:key:alice", true);

        // When she travels, reputation serializes
        var rep = AttestationService.get().serializeForTransit(
            "did:key:alice", "alpha");
        assertTrue(rep.stewardCount() >= 1);
        assertTrue(rep.compositeScore() > 0);
        assertEquals("alpha", rep.homeZone());

        // Remote zone can make decisions based on tier
        var tier = rep.permissionTier();
        assertNotEquals("tourist", tier);
    }

    @Test
    void transit_inventory_preserves_scripted_items() {
        // Player has a scripted item (library_card with a script source)
        var items = List.of(
            new TransitInventory.TransitItem(
                "library_card-1", "library card", "A magical card", true,
                List.of("card", "library card"),
                "function invoke(p) { return {response: 'searched'}; }",
                "library_card", Map.of()));

        var inventory = new TransitInventory("alpha", items);
        assertEquals(1, inventory.items().size());
        var item = inventory.items().get(0);
        assertNotNull(item.scriptSource());
        assertTrue(item.scriptSource().contains("invoke"));
        assertEquals("library_card", item.scriptId());
    }

    @Test
    void transit_delta_computes_correctly() {
        // Player starts with 2 items, drops 1, takes 1 in remote zone
        var originalIds = Set.of("sword-1", "key-1");
        var virtualInventory = new HashMap<String, TransitInventory.TransitItem>();
        // Only key remains (sword dropped remotely)
        virtualInventory.put("key-1",
            TransitInventory.TransitItem.simple("key-1", "key", "", true, List.of()));
        // Plus new item picked up in remote zone
        var taken = TransitInventory.TransitItem.simple(
            "gem-remote-1", "gem", "shiny", true, List.of("gem"));
        virtualInventory.put("gem-remote-1", taken);

        // Compute delta manually (mimicking VirtualSession.computeDelta)
        var removed = originalIds.stream()
            .filter(id -> !virtualInventory.containsKey(id))
            .toList();
        var added = List.of(taken);

        var delta = new TransitInventory.TransitDelta(removed, added);
        assertEquals(1, delta.removedItemIds().size());
        assertEquals("sword-1", delta.removedItemIds().get(0));
        assertEquals(1, delta.addedItems().size());
        assertEquals("gem-remote-1", delta.addedItems().get(0).id());
    }
}

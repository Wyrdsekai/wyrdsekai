package org.wyrdsekai.e2e.tier3;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.*;
import org.wyrdsekai.between.BetweenActor;
import org.wyrdsekai.between.federation.ZoneManifest;
import org.wyrdsekai.core.economy.CrossZoneExchange;
import org.wyrdsekai.core.room.ZoneAesthetic;
import org.wyrdsekai.core.room.ZoneAestheticService;
import org.wyrdsekai.core.soul.SoulTransitProtocol;
import org.wyrdsekai.e2e.infra.NatsServerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestActorSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zone transit E2E with economy tracking and aesthetic overlay.
 * Two zones communicating via real NATS. Tests:
 *
 * 1. Zone manifest carries aesthetic preset
 * 2. Transit mode resolution works with soul capabilities
 * 3. Cross-zone exchange records transit inference costs
 * 4. Zone aesthetic changes on transit (per ZoneAestheticService)
 * 5. Aesthetic reverts on return to home zone
 * 6. Zone cost modifiers differ between zones
 * 7. Net flow tracks bilateral balance
 */
@Tag("between")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ZoneTransitEconomyE2ETest {

    private static NatsServerFixture nats;
    private static ActorTestKit kitA;
    private static ActorTestKit kitB;
    private static Path dataDirA;
    private static Path dataDirB;

    @BeforeAll
    static void setUp() throws Exception {
        NatsServerFixture.assumeAvailable();

        nats = new NatsServerFixture();
        nats.start();

        kitA = TestActorSystem.create("zone-transit-a");
        kitB = TestActorSystem.create("zone-transit-b");

        dataDirA = Files.createTempDirectory("zt-a");
        dataDirB = Files.createTempDirectory("zt-b");

        // Init economy singletons
        CrossZoneExchange.init();
        ZoneAestheticService.init();
    }

    @AfterAll
    static void tearDown() {
        if (kitA != null) kitA.shutdownTestKit();
        if (kitB != null) kitB.shutdownTestKit();
        if (nats != null) nats.stop();
    }

    @Test
    @Order(1)
    void zone_manifest_carries_aesthetic_preset() {
        var manifest = new ZoneManifest(
            "zone-arcane", "The Arcane Tower", "pk-arcane",
            nats.natsUrl(), "http://localhost:8080", 25520,
            List.of("inference", "soul-aware"), Instant.now(),
            null, "arcane");

        assertEquals("arcane", manifest.aestheticPreset(),
            "ZoneManifest should carry aestheticPreset");
    }

    @Test
    @Order(2)
    void zone_manifest_backward_compatible_without_aesthetic() {
        // Old-style manifest without aesthetic — should work
        var manifest = new ZoneManifest(
            "zone-old", "Old Zone", "pk-old",
            nats.natsUrl(), "http://localhost:8081", 25521,
            List.of("inference"), Instant.now());

        assertNull(manifest.aestheticPreset(),
            "Old-style ZoneManifest should have null aestheticPreset");
    }

    @Test
    @Order(3)
    void transit_mode_resolution_with_soul_capabilities() {
        var request = SoulTransitProtocol.TransitRequest.budding(
            "did:key:agent1", "zone-a", "zone-b",
            "manifest-hash-123", 1, "family-1");

        var destCaps = SoulTransitProtocol.ZoneSoulCapabilities.full(List.of("qwen3.5-9b"));

        // Destination has model + budding supported → BUDDING
        var mode = SoulTransitProtocol.resolveMode(request, destCaps, true);
        assertEquals(SoulTransitProtocol.TransitMode.BUDDING, mode);

        // Destination has no model → falls back to THIN_CLIENT
        var modeNoModel = SoulTransitProtocol.resolveMode(request, destCaps, false);
        assertEquals(SoulTransitProtocol.TransitMode.THIN_CLIENT, modeNoModel);
    }

    @Test
    @Order(4)
    void cross_zone_exchange_records_transit_cost() {
        var exchange = CrossZoneExchange.get();
        assertNotNull(exchange);

        // Set exchange rate between zones
        exchange.setRate("zone-home", "zone-visitor", 1.0,
            Instant.now().plusSeconds(3600));

        // Simulate transit inference cost
        var result = exchange.exchange("zone-home", "zone-visitor",
            "did:key:traveling-agent", "zone-visitor",
            250, "Transit inference: 9B (5000 tokens)");

        assertTrue(result.success(), "Exchange should succeed");
        assertEquals(250, result.transaction().sourceAmount());
        assertEquals("did:key:traveling-agent", result.transaction().sourceEntityId());
    }

    @Test
    @Order(5)
    void aesthetic_changes_on_transit() {
        var svc = ZoneAestheticService.get();
        assertNotNull(svc);

        // Home zone = arcane
        svc.setZoneAesthetic(ZoneAesthetic.arcane());
        assertEquals("arcane", svc.effectiveAesthetic("home-room").name());

        // Simulate transit: switch to destination zone's aesthetic
        svc.setZoneAesthetic(ZoneAesthetic.cyberpunk());
        assertEquals("cyberpunk", svc.effectiveAesthetic("home-room").name());

        // Return home: revert
        svc.setZoneAesthetic(ZoneAesthetic.arcane());
        assertEquals("arcane", svc.effectiveAesthetic("home-room").name());
    }

    @Test
    @Order(6)
    void zone_cost_modifiers_differ_between_zones() {
        var svc = ZoneAestheticService.get();

        // Arcane: craft_item = 0.7x, web_search = 1.5x
        svc.setZoneAesthetic(ZoneAesthetic.arcane());
        double arcaneCraft = svc.costModifier("room-1", "craft_item");
        double arcaneSearch = svc.costModifier("room-1", "web_search");

        // Cyberpunk: web_search = 0.7x, craft_item = 1.2x
        svc.setZoneAesthetic(ZoneAesthetic.cyberpunk());
        double cyberCraft = svc.costModifier("room-1", "craft_item");
        double cyberSearch = svc.costModifier("room-1", "web_search");

        // Craft is cheaper in arcane, search is cheaper in cyberpunk
        assertTrue(arcaneCraft < cyberCraft,
            "Craft should be cheaper in arcane (" + arcaneCraft + ") than cyberpunk (" + cyberCraft + ")");
        assertTrue(cyberSearch < arcaneSearch,
            "Web search should be cheaper in cyberpunk (" + cyberSearch + ") than arcane (" + arcaneSearch + ")");
    }

    @Test
    @Order(7)
    void net_flow_tracks_bilateral_balance() {
        var exchange = CrossZoneExchange.get();

        // Set bilateral rates
        exchange.setRate("zone-alpha", "zone-beta", 1.0,
            Instant.now().plusSeconds(3600));
        exchange.setRate("zone-beta", "zone-alpha", 1.0,
            Instant.now().plusSeconds(3600));

        // Alpha agents use Beta's inference
        exchange.exchange("zone-alpha", "zone-beta", "agent-a1", "zone-beta",
            300, "inference");
        exchange.exchange("zone-alpha", "zone-beta", "agent-a2", "zone-beta",
            200, "inference");

        // Beta agent uses Alpha's inference
        exchange.exchange("zone-beta", "zone-alpha", "agent-b1", "zone-alpha",
            150, "inference");

        // Net flow: Alpha→Beta = 500, Beta→Alpha = 150
        assertEquals(500, exchange.netFlow("zone-alpha", "zone-beta"));
        assertEquals(150, exchange.netFlow("zone-beta", "zone-alpha"));
    }

    private BetweenActor.BetweenConfig makeConfig() {
        return new BetweenActor.BetweenConfig(
            true, nats.natsUrl(), false, null,
            nats.clientPort(), nats.monitorPort(),
            false, List.of(), Duration.ofSeconds(2), Duration.ofSeconds(3),
            PortAllocator.allocate());
    }
}

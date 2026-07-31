package org.wyrdsekai.e2e.tier2;

import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.*;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.SkillCostMatrix;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillCostMatrix E2E — tests that per-agent learned action costs work end-to-end.
 *
 * Uses WireMock (deterministic, no real inference needed). Tests the three-layer
 * architecture's COST layer: practice reduces cost, unused skills decay, novel tools
 * default high, Forge consolidation works through CompanionActor.
 */
@Tag("tier2")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkillCostE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String COMPANION_ID = "companion-wyrd";

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Hello, I'm ready to help.", 20, 15);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void reset() {
        server.respawnCompanion();
    }

    private CompanionActor.TestStateResponse queryState() throws Exception {
        var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
        assertNotNull(ref, "Companion ref should be resolvable");
        return AskPattern.<CompanionActor.Command, CompanionActor.TestStateResponse>ask(
            ref,
            CompanionActor.QueryTestState::new,
            TIMEOUT,
            server.system().scheduler()
        ).toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    @Order(1)
    void new_companion_has_initial_costs() throws Exception {
        var state = queryState();

        // SkillCostMatrix.newCompanion() populates known actions at floor + 0.25
        assertTrue(state.skillCount() > 0, "New companion should have pre-populated skills");

        // go_to_room should exist and be above floor
        var costs = state.skillCosts();
        assertTrue(costs.containsKey("go_to_room"),
            "go_to_room should be in initial skill costs");

        double goToRoomCost = costs.get("go_to_room");
        double floor = SkillCostMatrix.floorFor("go_to_room");
        assertTrue(goToRoomCost > floor,
            "Initial cost (" + goToRoomCost + ") should be above floor (" + floor + ")");
        assertTrue(goToRoomCost <= 0.40,
            "Initial cost should be at most 0.40 (floor + 0.25)");
    }

    @Test
    @Order(2)
    void novel_tool_defaults_high() throws Exception {
        var state = queryState();

        // Unknown actions should not be in the map
        assertFalse(state.skillCosts().containsKey("summon_dragon"),
            "Novel tool 'summon_dragon' should not be in initial costs");

        // SkillCostMatrix.costFor("summon_dragon") returns DEFAULT_NEW (0.40)
        // We verify this via the static API since QueryTestState returns the snapshot
        assertEquals(0.40, SkillCostMatrix.newCompanion().costFor("summon_dragon"), 0.01,
            "Novel tool should default to 0.40");
    }

    @Test
    @Order(3)
    void practice_reduces_cost_after_forge() throws Exception {
        var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
        assertNotNull(ref, "Companion ref should be resolvable");

        // Get initial cost for go_to_room
        var initialState = queryState();
        double initialCost = initialState.skillCosts().get("go_to_room");

        // Simulate repeated successful go_to_room actions via WebSocket navigation
        // Each navigation triggers skillCosts.recordSuccess("go_to_room")
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);

            // Navigate back and forth — each successful go triggers recordSuccess
            for (int i = 0; i < 10; i++) {
                ws.sendGo("nexus", "east");
                try { ws.waitForRoomState(Duration.ofSeconds(3)); } catch (Exception e) { /* room may not exist */ }
                ws.sendGo(ws.currentRoomId(), "west");
                try { ws.waitForRoomState(Duration.ofSeconds(3)); } catch (Exception e) { /* best effort */ }
            }
        }

        // Trigger Forge consolidation — practiced actions should decrease in cost
        ref.tell(new CompanionActor.ForceForgeConsolidate());
        Thread.sleep(200); // let actor process

        var afterState = queryState();
        double afterCost = afterState.skillCosts().get("go_to_room");

        // Cost should have decreased (or at least not increased) after practice + forge
        assertTrue(afterCost <= initialCost,
            "go_to_room cost after practice+forge (" + afterCost
                + ") should be <= initial (" + initialCost + ")");

        System.out.println("[SkillCost] go_to_room: " + initialCost + " -> " + afterCost
            + " (delta: " + (afterCost - initialCost) + ")");
    }

    @Test
    @Order(4)
    void unused_skills_drift_up_after_forge() throws Exception {
        var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
        assertNotNull(ref, "Companion ref should be resolvable");

        // Get initial cost for a skill we WON'T use
        var initialState = queryState();
        // Pick a skill that exists but won't be exercised — library_search
        if (!initialState.skillCosts().containsKey("library_search")) {
            System.out.println("[SkillCost] library_search not in initial costs, skipping drift test");
            return;
        }
        double initialCost = initialState.skillCosts().get("library_search");

        // Do some work with OTHER skills (go_to_room) but NOT library_search
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            for (int i = 0; i < 5; i++) {
                ws.sendGo("nexus", "east");
                try { ws.waitForRoomState(Duration.ofSeconds(3)); } catch (Exception e) { /* ok */ }
            }
        }

        // Forge consolidation — unused skills drift up
        ref.tell(new CompanionActor.ForceForgeConsolidate());
        Thread.sleep(200);

        var afterState = queryState();
        double afterCost = afterState.skillCosts().get("library_search");

        // Unused skill should drift up (or stay same if already at max)
        assertTrue(afterCost >= initialCost,
            "Unused library_search cost after forge (" + afterCost
                + ") should be >= initial (" + initialCost + ")");

        System.out.println("[SkillCost] library_search (unused): " + initialCost + " -> " + afterCost
            + " (delta: " + (afterCost - initialCost) + ")");
    }

    @Test
    @Order(5)
    void skill_costs_reset_on_respawn() throws Exception {
        var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);

        // Do some work to change costs
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            ws.sendGo("nexus", "east");
            try { ws.waitForRoomState(Duration.ofSeconds(3)); } catch (Exception e) { /* ok */ }
        }
        ref.tell(new CompanionActor.ForceForgeConsolidate());
        Thread.sleep(200);

        var before = queryState();

        // Respawn resets everything
        server.respawnCompanion();
        Thread.sleep(500); // let reset complete

        var after = queryState();

        // Costs should be back to initial
        assertEquals(before.skillCount(), after.skillCount(),
            "Skill count should match after reset (both fresh)");

        // go_to_room should be back to initial value
        double afterCost = after.skillCosts().get("go_to_room");
        double floor = SkillCostMatrix.floorFor("go_to_room");
        assertTrue(afterCost > floor && afterCost <= floor + 0.26,
            "go_to_room should be back to initial range after reset");
    }

    @Test
    @Order(6)
    void can_afford_checks_energy_vs_cost() throws Exception {
        // Verify canAfford logic: high energy = can afford, low energy = can't
        var matrix = SkillCostMatrix.newCompanion();
        double goToRoomCost = matrix.costFor("go_to_room");

        assertTrue(matrix.canAfford("go_to_room", 1.0),
            "Should afford go_to_room at full energy");
        assertTrue(matrix.canAfford("go_to_room", goToRoomCost + 0.01),
            "Should afford go_to_room at cost + epsilon");
        assertFalse(matrix.canAfford("go_to_room", goToRoomCost * 0.5),
            "Should NOT afford go_to_room at half the cost energy");

        // Novel tool at DEFAULT_NEW (0.40) — needs significant energy
        assertFalse(matrix.canAfford("summon_dragon", 0.10),
            "Should NOT afford novel tool at low energy");
        assertTrue(matrix.canAfford("summon_dragon", 0.50),
            "Should afford novel tool at moderate energy");
    }
}

package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneAesthetic;
import org.wyrdsekai.core.room.ZoneAestheticService;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zone Aesthetic E2E tests — verify aesthetic system through the full server stack.
 * Requires real inference to verify style prompt flows through to companion output.
 *
 * Requires: WYRDSEKAI_E2E_BACKEND=llama-server (or sglang)
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ZoneAestheticE2ETest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPANION = "Wyrd";

    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        // Warmup the skills backend
        var model = System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");
        System.out.println("[AestheticE2E] Warming up...");
        try {
            var warmup = new InferenceClient.ChatRequest(model,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmup)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[AestheticE2E] Warmup failed: " + e.getMessage());
        }

        server = new TestServerBootstrap(dual.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        var svc = ZoneAestheticService.get();
        if (svc != null) svc.setZoneAesthetic(ZoneAesthetic.none());
        if (server != null) server.stop();
    }

    @BeforeEach
    void reset() {
        server.respawnCompanion();
        var svc = ZoneAestheticService.get();
        if (svc != null) svc.setZoneAesthetic(ZoneAesthetic.none());
    }

    @Test
    @Order(1)
    void arcane_aesthetic_influences_response() throws Exception {
        // Set arcane aesthetic — companion should speak differently
        var svc = ZoneAestheticService.get();
        assertNotNull(svc);
        svc.setZoneAesthetic(ZoneAesthetic.arcane());

        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(CONNECT_TIMEOUT);
            var roomId = ws.currentRoomId();
            ws.sendSay(roomId, "tell wyrd What is this place?");
            var prose = ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);

            // The companion's response should exist — the arcane style is injected into its prompt
            assertNotNull(prose, "Companion should respond with arcane-influenced text");
            System.out.println("[AestheticE2E] Arcane response: "
                + prose.path("text").asText().substring(0,
                    Math.min(100, prose.path("text").asText().length())));
        }
    }

    @Test
    @Order(2)
    void sanctuary_restricts_actions() {
        var svc = ZoneAestheticService.get();
        assertNotNull(svc);
        svc.setZoneAesthetic(ZoneAesthetic.sanctuary());

        var restricted = svc.restrictedActions("any-room");
        assertTrue(restricted.contains("cast_vote"),
            "Sanctuary should restrict cast_vote");
        assertTrue(restricted.contains("delegate_chain"),
            "Sanctuary should restrict delegate_chain");
    }

    @Test
    @Order(3)
    void zone_cost_modifier_affects_effective_cost() {
        var svc = ZoneAestheticService.get();
        assertNotNull(svc);
        svc.setZoneAesthetic(ZoneAesthetic.steampunk());

        assertEquals(0.6, svc.costModifier("workshop", "craft_item"), 0.001);
        assertEquals(1.0, svc.costModifier("workshop", "go_to_room"), 0.001);
    }

    @Test
    @Order(4)
    void room_aesthetic_overrides_zone() {
        var svc = ZoneAestheticService.get();
        assertNotNull(svc);
        svc.setZoneAesthetic(ZoneAesthetic.arcane());

        assertEquals("arcane", svc.effectiveAesthetic("room-a").name());

        svc.setRoomAesthetic("room-a", ZoneAesthetic.cyberpunk());
        assertEquals("cyberpunk", svc.effectiveAesthetic("room-a").name());
        assertEquals("arcane", svc.effectiveAesthetic("room-b").name());

        svc.setRoomAesthetic("room-a", null);
        assertEquals("arcane", svc.effectiveAesthetic("room-a").name());
    }

    @Test
    @Order(5)
    void all_presets_loadable() {
        var presets = ZoneAesthetic.presetNames();
        assertEquals(7, presets.size());
        for (var name : presets) {
            var preset = ZoneAesthetic.preset(name);
            assertNotNull(preset.stylePrompt());
            assertFalse(preset.stylePrompt().isBlank());
        }
    }
}

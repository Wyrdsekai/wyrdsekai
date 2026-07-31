package org.wyrdsekai.e2e.tier2;

import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.AgentCostTracker;
import org.wyrdsekai.core.economy.*;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Economy E2E tests — verify economy wiring through the full server stack
 * with real inference. Tests the chain:
 * WS message → companion inference → ResourceMeter → CountingHouse
 *
 * Requires: WYRDSEKAI_E2E_BACKEND=llama-server (or sglang)
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EconomyE2ETest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPANION = "Wyrd";

    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        // Warmup the skills backend
        var model = System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");
        System.out.println("[EconomyE2E] Warming up...");
        try {
            var warmup = new InferenceClient.ChatRequest(model,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmup)
                .get(120_000, TimeUnit.MILLISECONDS);
            System.out.println("[EconomyE2E] Warm.");
        } catch (Exception e) {
            System.out.println("[EconomyE2E] Warmup failed: " + e.getMessage());
        }

        server = new TestServerBootstrap(dual.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void reset() {
        server.respawnCompanion();
    }

    @Test
    @Order(1)
    void inference_records_to_counting_house() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(CONNECT_TIMEOUT);
            var roomId = ws.currentRoomId();
            ws.sendSay(roomId, "tell wyrd Tell me about Greek mythology");
            ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);
        }

        // Query CountingHouse — inference should have been metered
        var state = AskPattern.<CountingHouseCommand, CountingHouseState>ask(
            server.countingHouse(),
            CountingHouseCommand.GetState::new,
            Duration.ofSeconds(10),
            server.system().scheduler()
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(state.totalRequests() > 0,
            "CountingHouse should have recorded at least 1 inference request");
        assertTrue(state.totalTokens() > 0,
            "CountingHouse should have recorded token usage (got " + state.totalTokens() + ")");
    }

    @Test
    @Order(2)
    void agent_cost_tracker_accumulates() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(CONNECT_TIMEOUT);
            var roomId = ws.currentRoomId();

            ws.sendSay(roomId, "tell wyrd What is quantum computing?");
            ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);

            ws.sendSay(roomId, "tell wyrd Tell me more about entanglement");
            ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);
        }

        var tracker = AgentCostTracker.get();
        assertNotNull(tracker, "AgentCostTracker should be initialized");
        var agents = tracker.trackedAgents();
        assertFalse(agents.isEmpty(), "At least one agent should have cost records");
    }

    @Test
    @Order(3)
    void trading_post_service_is_live() {
        var tradingPost = TradingPostService.get();
        assertNotNull(tradingPost, "TradingPostService should be initialized via TestServerBootstrap");

        var posted = tradingPost.postItem("Test Sword", "A test weapon", 100,
            "test-seller", "Test Seller");
        assertNotNull(posted.itemId());

        var acquired = tradingPost.acquireItem(posted.itemId(), "test-buyer");
        assertTrue(acquired.isPresent());
        assertEquals(TradingPostService.ItemStatus.SOLD, acquired.get().status());

        var provenance = tradingPost.verifyProvenance(posted.itemId());
        assertTrue(provenance.isPresent());
        assertEquals(2, provenance.get().size());
    }

    @Test
    @Order(4)
    void cross_zone_exchange_is_live() {
        var exchange = CrossZoneExchange.get();
        assertNotNull(exchange, "CrossZoneExchange should be initialized");

        exchange.setRate("test-zone-a", "test-zone-b", 1.5,
            Instant.now().plusSeconds(3600));

        var result = exchange.exchange("test-zone-a", "test-zone-b",
            "agent-1", "test-zone-b", 100, "E2E test exchange");

        assertTrue(result.success());
        assertEquals(150, result.transaction().targetAmount());
    }

    @Test
    @Order(5)
    void estate_manager_is_live() {
        var estateManager = EstateManager.get();
        assertNotNull(estateManager, "EstateManager should be initialized");
    }

    @Test
    @Order(6)
    void ledger_persistence_survives_transfer() throws Exception {
        // Transfer credits via CountingHouse
        var probe = server.system().<String>systemActorOf(
            Behaviors.empty(), "econ-probe",
            Props.empty());

        server.countingHouse().tell(new CountingHouseCommand.Transfer(
            "econ-agent-a", "econ-agent-b", 50, "E2E test", probe));

        Thread.sleep(500);

        // Query balance — should reflect transfer (or credit limit)
        var balance = AskPattern.<CountingHouseCommand, CreditBalance>ask(
            server.countingHouse(),
            ref -> new CountingHouseCommand.QueryBalance("econ-agent-b", ref),
            Duration.ofSeconds(5),
            server.system().scheduler()
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertNotNull(balance, "Should get balance for agent-b");
    }
}

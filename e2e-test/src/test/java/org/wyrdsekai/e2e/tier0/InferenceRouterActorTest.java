package org.wyrdsekai.e2e.tier0;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.*;
import org.wyrdsekai.core.economy.CountingHouseCommand;
import org.wyrdsekai.core.economy.ResourceMeter;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.inference.InferenceRouter.*;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestActorSystem;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 0 integration tests for InferenceRouter actor.
 * Uses WireMock to simulate inference backends. No real LLM required.
 *
 * Tests: routing, error handling, metering, model-specific routing, concurrency.
 */
@Tag("integration")
class InferenceRouterActorTest {

    private static ActorTestKit testKit;
    private WireMockInferenceServer primaryServer;
    private WireMockInferenceServer secondaryServer;

    @BeforeAll
    static void setup() {
        testKit = TestActorSystem.create("inference-router-test");
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void setupServers() {
        primaryServer = WireMockInferenceServer.openAi();
        primaryServer.start();
        primaryServer.stubChatCompletion("Hello from primary!", 10, 25);

        secondaryServer = WireMockInferenceServer.openAi();
        secondaryServer.start();
        secondaryServer.stubChatCompletion("Hello from secondary!", 8, 20);
    }

    @AfterEach
    void stopServers() {
        primaryServer.stop();
        secondaryServer.stop();
    }

    private InferenceBackend primaryBackend() {
        return new InferenceBackend.LlamaServer(
            "primary", new InferenceClient(primaryServer.baseUrl()),
            10, List.of(), null);
    }

    private InferenceBackend secondaryBackend() {
        return new InferenceBackend.LlamaServer(
            "secondary", new InferenceClient(secondaryServer.baseUrl()),
            100, List.of(), null);
    }

    @Test
    void routes_to_first_healthy_backend_by_priority() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(primaryBackend(), secondaryBackend()),
            "test-model", null, Duration.ofMinutes(5)));

        var probe = testKit.<InferResponse>createTestProbe();
        router.tell(new ChatRequest("req-1", null,
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            128, 0.7, probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(10));
        assertThat(response).isInstanceOf(InferOk.class);
        var ok = (InferOk) response;
        assertThat(ok.content()).isEqualTo("Hello from primary!");
        assertThat(ok.requestId()).isEqualTo("req-1");
    }

    @Test
    void all_backends_unavailable_returns_error() {
        primaryServer.stubChatCompletionError(500, "Down");
        secondaryServer.stubChatCompletionError(500, "Also down");

        var router = testKit.spawn(InferenceRouter.create(
            List.of(primaryBackend(), secondaryBackend()),
            "test-model", null, Duration.ofMinutes(5)));

        var probe = testKit.<InferResponse>createTestProbe();
        router.tell(new ChatRequest("req-3", null,
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            128, 0.7, probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(10));
        assertThat(response).isInstanceOf(InferError.class);
        assertThat(((InferError) response).error()).contains("failed");
    }

    @Test
    void usage_metered_to_counting_house() {
        var chProbe = testKit.<CountingHouseCommand>createTestProbe();
        var meter = new ResourceMeter(chProbe.ref());

        var router = testKit.spawn(InferenceRouter.create(
            List.of(primaryBackend()), "test-model", meter, Duration.ofMinutes(5)));

        var probe = testKit.<InferResponse>createTestProbe();
        router.tell(new ChatRequest("req-5", null,
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            128, 0.7, probe.ref()));

        probe.receiveMessage(Duration.ofSeconds(10));

        // Verify metering message was sent
        var meterMsg = chProbe.receiveMessage(Duration.ofSeconds(5));
        assertThat(meterMsg).isInstanceOf(CountingHouseCommand.RecordUsage.class);
    }

    @Test
    void model_specific_routing() {
        // Primary has "model-a", secondary has "model-b"
        var primary = new InferenceBackend.LlamaServer(
            "primary", new InferenceClient(primaryServer.baseUrl()),
            10, List.of("model-a"), null);
        var secondary = new InferenceBackend.LlamaServer(
            "secondary", new InferenceClient(secondaryServer.baseUrl()),
            100, List.of("model-b"), null);

        var router = testKit.spawn(InferenceRouter.create(
            List.of(primary, secondary), "model-a", null, Duration.ofMinutes(5)));

        var probe = testKit.<InferResponse>createTestProbe();

        // Request with model-b should route to secondary
        router.tell(new ChatRequest("req-6", "model-b",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            128, 0.7, probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(10));
        assertThat(response).isInstanceOf(InferOk.class);
        assertThat(((InferOk) response).content()).isEqualTo("Hello from secondary!");
    }

    @Test
    void list_backends_returns_all_configured() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(primaryBackend(), secondaryBackend()),
            "test-model", null, Duration.ofMinutes(5)));

        var probe = testKit.<BackendList>createTestProbe();
        router.tell(new ListBackends(probe.ref()));

        var list = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(list.backends()).hasSize(2);
        assertThat(list.backends().stream().map(BackendInfo::name).toList())
            .containsExactly("primary", "secondary");
    }

    @Test
    void infer_request_single_turn_path() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(primaryBackend()), "test-model", null, Duration.ofMinutes(5)));

        var probe = testKit.<InferResponse>createTestProbe();
        router.tell(new InferRequest("req-8", null,
            "You are a helpful guide.", "What is this place?",
            128, 0.7, probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(10));
        assertThat(response).isInstanceOf(InferOk.class);
        assertThat(((InferOk) response).content()).isNotEmpty();
    }

    @Test
    void null_model_uses_default() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(primaryBackend()), "test-model", null, Duration.ofMinutes(5)));

        var probe = testKit.<InferResponse>createTestProbe();
        router.tell(new ChatRequest("req-9", null,  // null model
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            128, 0.7, probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(10));
        assertThat(response).isInstanceOf(InferOk.class);
    }

    @Test
    void concurrent_requests_all_complete() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(primaryBackend()), "test-model", null, Duration.ofMinutes(5)));

        var probe1 = testKit.<InferResponse>createTestProbe();
        var probe2 = testKit.<InferResponse>createTestProbe();
        var probe3 = testKit.<InferResponse>createTestProbe();

        router.tell(new ChatRequest("req-c1", null,
            List.of(new InferenceClient.ChatMessage("user", "Hello 1")),
            128, 0.7, probe1.ref()));
        router.tell(new ChatRequest("req-c2", null,
            List.of(new InferenceClient.ChatMessage("user", "Hello 2")),
            128, 0.7, probe2.ref()));
        router.tell(new ChatRequest("req-c3", null,
            List.of(new InferenceClient.ChatMessage("user", "Hello 3")),
            128, 0.7, probe3.ref()));

        assertThat(probe1.receiveMessage(Duration.ofSeconds(10))).isInstanceOf(InferOk.class);
        assertThat(probe2.receiveMessage(Duration.ofSeconds(10))).isInstanceOf(InferOk.class);
        assertThat(probe3.receiveMessage(Duration.ofSeconds(10))).isInstanceOf(InferOk.class);
    }
}

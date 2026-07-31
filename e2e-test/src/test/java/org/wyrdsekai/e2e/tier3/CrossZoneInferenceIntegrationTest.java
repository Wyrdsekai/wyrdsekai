package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.typesafe.config.ConfigFactory;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.inference.NatsInferenceClient;
import org.wyrdsekai.between.inference.NatsInferenceProtocol;
import org.wyrdsekai.common.model.QuotaPolicy;
import org.wyrdsekai.core.economy.MeteringService;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.e2e.infra.EmbeddedNatsRelay;
import org.wyrdsekai.server.inference.NatsInferenceServer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Tier 2 integration: two wyrdsekai "zones" ({@code alpha} provider,
 * {@code beta} requestor) wired over a real embedded NATS relay, with the
 * local LLM endpoint mocked by WireMock.
 *
 * <p>Scenarios here catch what unit tests can't: real NATS subject routing,
 * subscribe-before-publish over actual sockets, SSE parsing against a live
 * HTTP body, and end-to-end metering attribution through the production
 * {@code InferenceRouter → NatsRemote → NatsInferenceClient → NATS → NatsInferenceServer}
 * chain.</p>
 */
class CrossZoneInferenceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EmbeddedNatsRelay relay;
    private static WireMockServer wiremock;
    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("pekko.actor.provider = \"local\""));

    // Alpha (provider) side
    private Connection alphaConn;
    private RelaySessionTransport alphaTransport;
    private ActorRef<InferenceRouter.Command> alphaRouter;
    private NatsInferenceServer alphaServer;

    // Beta (requestor) side
    private Connection betaConn;
    private RelaySessionTransport betaTransport;
    private ActorRef<InferenceRouter.Command> betaRouter;
    private NatsInferenceClient betaClient;

    @BeforeAll
    static void startFixtures() throws Exception {
        relay = new EmbeddedNatsRelay();
        relay.start();

        // Give WireMock a real (empty) file root. wiremock-jetty12 3.13.2 throws an
        // internal Jetty "baseResource is null" NPE when a request falls through to
        // static-file serving with no root configured — which happens for the
        // NatsInferenceServer health probe (GET /v1/models) in the streaming tests.
        var wmRoot = java.nio.file.Files.createTempDirectory("wiremock-root-");
        java.nio.file.Files.createDirectories(wmRoot.resolve("__files"));
        java.nio.file.Files.createDirectories(wmRoot.resolve("mappings"));
        wmRoot.toFile().deleteOnExit();
        wiremock = new WireMockServer(WireMockConfiguration.wireMockConfig()
            .dynamicPort()
            .usingFilesUnderDirectory(wmRoot.toString()));
        wiremock.start();
    }

    @AfterAll
    static void stopFixtures() {
        if (wiremock != null) wiremock.stop();
        if (relay != null) relay.stop();
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void setup() throws Exception {
        wiremock.resetAll();
        // Stub /health so LlamaServer health checks succeed — matches real
        // llama-server behavior and prevents flaky DOWN-status timing.
        wiremock.stubFor(get(urlPathEqualTo("/health"))
            .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));
        // /v1/models is what NatsInferenceServer's F25 dead-backend guard probes
        // before subscribing when streamingEnabled=true. Stub it (real llama-server
        // serves it) so the streaming provider passes its health check and
        // subscribes; combined with the file-root config in startFixtures() this
        // clears the wiremock-jetty12 baseResource NPE that broke the streaming tests.
        wiremock.stubFor(get(urlPathEqualTo("/v1/models"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[]}")));
        MeteringService.init();
        MeteringService.get().clear();

        // Alpha zone — provider side.
        alphaConn = Nats.connect(new Options.Builder()
            .server(relay.url()).connectionName("alpha-test")
            .connectionTimeout(Duration.ofSeconds(3)).build());
        alphaTransport = new RelaySessionTransport(alphaConn);
        alphaRouter = testKit.spawn(InferenceRouter.create(
            List.of(), "test-model", null), "alpha-router-" + UUID.randomUUID());

        // Beta zone — requestor side.
        betaConn = Nats.connect(new Options.Builder()
            .server(relay.url()).connectionName("beta-test")
            .connectionTimeout(Duration.ofSeconds(3)).build());
        betaTransport = new RelaySessionTransport(betaConn);
        betaRouter = testKit.spawn(InferenceRouter.create(
            List.of(), "test-model", null), "beta-router-" + UUID.randomUUID());
        betaClient = new NatsInferenceClient(betaTransport);

        // Wire beta's router to treat alpha as a NatsRemote backend.
        betaRouter.tell(new InferenceRouter.SetNatsRemoteCaller(
            (targetZone, sourceZone, chatReq, tokenCallback) -> {
                boolean streaming = tokenCallback != null;
                var natsReq = new NatsInferenceProtocol.Request(
                    UUID.randomUUID().toString(), sourceZone, "test-agent",
                    chatReq.model(),
                    chatReq.messages().stream().map(m ->
                        new NatsInferenceProtocol.Message(m.role(), m.content())).toList(),
                    chatReq.maxTokens(), chatReq.temperature(), streaming);
                var future = streaming
                    ? betaClient.requestStreaming(targetZone, natsReq, tokenCallback)
                    : betaClient.request(targetZone, natsReq);
                return future.thenApply(completion -> {
                    var msg = new InferenceClient.ChatMessage("assistant", completion.text());
                    var choice = new InferenceClient.Choice(0, msg, completion.finishReason());
                    var usage = new InferenceClient.Usage(
                        completion.promptTokens() != null ? completion.promptTokens() : 0,
                        completion.completionTokens() != null ? completion.completionTokens() : 0,
                        (completion.promptTokens() != null ? completion.promptTokens() : 0)
                            + (completion.completionTokens() != null ? completion.completionTokens() : 0));
                    return new InferenceClient.ChatResponse(
                        UUID.randomUUID().toString(), "chat.completion",
                        System.currentTimeMillis() / 1000L, chatReq.model(),
                        List.of(choice), usage);
                });
            }));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (alphaServer != null) alphaServer.stop();
        if (betaClient != null) betaClient.close();
        if (alphaTransport != null) alphaTransport.close();
        if (betaTransport != null) betaTransport.close();
    }

    private void startAlphaProvider(boolean streaming) {
        alphaServer = new NatsInferenceServer(
            alphaTransport, "alpha", alphaRouter, testKit.system(), "test-backend",
            wiremock.baseUrl(), streaming);
        alphaServer.start();
    }

    private void installBetaRemoteBackend() {
        betaRouter.tell(new InferenceRouter.AddRemoteBackend(
            "remote-alpha-natstest", "llama-server", "nats://alpha",
            List.of("test-model"), 110));
        sleep(100);
    }

    @Test void non_streaming_roundtrip_through_real_relay() throws Exception {
        stubNonStreamingResponse("hello from alpha", 20, 5);

        // Alpha needs a LOCAL backend so the router's non-streaming fallback has
        // somewhere to route to (preferredBackend=test-backend points at this).
        alphaRouter.tell(new InferenceRouter.AddRemoteBackend(
            "test-backend", "llama-server", wiremock.baseUrl(),
            List.of("test-model"), 50));
        sleep(150);
        startAlphaProvider(/*streaming=*/false);
        installBetaRemoteBackend();

        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        betaRouter.tell(new InferenceRouter.ChatRequest(
            "req-ns-1", "test-model",
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            64, 0.0, probe.ref(), "remote-alpha-natstest"));

        var ok = probe.expectMessageClass(
            InferenceRouter.InferOk.class, Duration.ofSeconds(10));
        assertThat(ok.content()).isEqualTo("hello from alpha");
        assertThat(ok.promptTokens()).isEqualTo(20);
        assertThat(ok.completionTokens()).isEqualTo(5);

        // Metering attribution — beta must credit zone "alpha", not backend handle.
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(MeteringService.get().eventCount()).isGreaterThan(0));
        var event = MeteringService.get().recentEvents(1).get(0);
        assertThat(event.providingZone()).isEqualTo("alpha");
    }

    @Test void streaming_roundtrip_delivers_tokens_through_relay() throws Exception {
        // Real SSE body that llama-server would emit, piped through WireMock.
        var sse = """
            data: {"choices":[{"delta":{"content":"hel"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"lo"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":" world"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}

            data: [DONE]

            """;
        wiremock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse)));

        startAlphaProvider(/*streaming=*/true);

        // Beta issues the request directly via NatsInferenceClient so we can
        // attach a token consumer and see per-chunk delivery.
        var collected = new CopyOnWriteArrayList<String>();
        var req = NatsInferenceClient.build(
            "beta", "test-agent", "test-model",
            null, "hi", 64, 0.0, true);
        var future = betaClient.requestStreaming("alpha", req, collected::add);

        var completion = future.get(5, TimeUnit.SECONDS);
        assertThat(completion.text()).isEqualTo("hello world");
        assertThat(completion.promptTokens()).isEqualTo(7);
        assertThat(completion.completionTokens()).isEqualTo(3);
        assertThat(collected).containsExactly("hel", "lo", " world");
    }

    @Test void streaming_chat_request_delivers_events_to_actor_ref() throws Exception {
        // The router-level StreamingChatRequest API: beta issues a message with a
        // streamRef ActorRef, and token/done events arrive as actor messages
        // (not via a Consumer callback). This is the primary consumer path for
        // CompanionActor and any other actor that wants to forward tokens to a
        // WebSocket or subscriber stream.
        var sse = """
            data: {"choices":[{"delta":{"content":"ping"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"-"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"pong"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7}}

            data: [DONE]

            """;
        wiremock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse)));

        startAlphaProvider(/*streaming=*/true);
        installBetaRemoteBackend();

        var streamProbe = testKit.<InferenceRouter.StreamEvent>createTestProbe();
        var replyProbe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        betaRouter.tell(new InferenceRouter.StreamingChatRequest(
            "stream-req-1", "test-model",
            List.of(new InferenceClient.ChatMessage("user", "ping?")),
            64, 0.0,
            streamProbe.ref(), replyProbe.ref(),
            "remote-alpha-natstest", false));

        // Three token events arrive in order, then a Done event.
        var t1 = streamProbe.expectMessageClass(
            InferenceRouter.StreamEvent.Token.class, Duration.ofSeconds(5));
        assertThat(t1.token()).isEqualTo("ping");
        var t2 = streamProbe.expectMessageClass(
            InferenceRouter.StreamEvent.Token.class, Duration.ofSeconds(2));
        assertThat(t2.token()).isEqualTo("-");
        var t3 = streamProbe.expectMessageClass(
            InferenceRouter.StreamEvent.Token.class, Duration.ofSeconds(2));
        assertThat(t3.token()).isEqualTo("pong");

        var done = streamProbe.expectMessageClass(
            InferenceRouter.StreamEvent.Done.class, Duration.ofSeconds(2));
        assertThat(done.fullText()).isEqualTo("ping-pong");
        assertThat(done.promptTokens()).isEqualTo(4);
        assertThat(done.completionTokens()).isEqualTo(3);
        assertThat(done.finishReason()).isEqualTo("stop");

        // replyTo also fires with the non-streaming-equivalent InferOk, so callers
        // can continue to Ask-pattern against the router if they want.
        var ok = replyProbe.expectMessageClass(
            InferenceRouter.InferOk.class, Duration.ofSeconds(2));
        assertThat(ok.content()).isEqualTo("ping-pong");
    }

    @Test void streaming_request_without_backend_emits_error_event() {
        // With no backend registered on beta (no installBetaRemoteBackend() here),
        // a StreamingChatRequest must emit an Error event AND an InferError reply,
        // rather than hanging on the stream ref.
        var streamProbe = testKit.<InferenceRouter.StreamEvent>createTestProbe();
        var replyProbe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        betaRouter.tell(new InferenceRouter.StreamingChatRequest(
            "stream-noback", "test-model",
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            32, 0.0,
            streamProbe.ref(), replyProbe.ref()));

        streamProbe.expectMessageClass(
            InferenceRouter.StreamEvent.Error.class, Duration.ofSeconds(3));
        replyProbe.expectMessageClass(
            InferenceRouter.InferError.class, Duration.ofSeconds(1));
    }

    @Test void provider_quota_denial_reaches_requestor_as_error() throws Exception {
        // No llama stub needed — quota denial happens before any dispatch.
        startAlphaProvider(/*streaming=*/false);
        alphaServer.setQuotaResolver(sourceZone ->
            new QuotaPolicy(50L, 0, 0, true, true, true, 0, Map.of()));

        // maxTokens=200 > quota=50 → provider rejects.
        var req = NatsInferenceClient.build(
            "beta", "test-agent", "test-model",
            null, "hi", 200, 0.0, false);

        var future = betaClient.request("alpha", req);
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
            .rootCause()
            .hasMessageContaining("QuotaExceeded")
            .hasMessageContaining("beta");
    }

    @Test void provider_does_not_bounce_request_to_another_nats_remote() throws Exception {
        // Loop prevention: if alpha happened to have a NatsRemote backend of its
        // own (say pointing at gamma), an incoming NATS request must still route
        // to the LOCAL backend. This is enforced by preferredBackend=test-backend.
        stubNonStreamingResponse("served locally", 10, 3);

        alphaRouter.tell(new InferenceRouter.AddRemoteBackend(
            "test-backend", "llama-server", wiremock.baseUrl(),
            List.of("test-model"), 50));
        // A bogus NatsRemote on alpha — would create a loop if selected.
        alphaRouter.tell(new InferenceRouter.SetNatsRemoteCaller(
            (zone, src, r, cb) -> {
                throw new IllegalStateException(
                    "Loop detected! alpha tried to bounce request to " + zone);
            }));
        alphaRouter.tell(new InferenceRouter.AddRemoteBackend(
            "remote-gamma", "llama-server", "nats://gamma",
            List.of("test-model"), 105));  // higher priority than local (50)
        sleep(150);

        startAlphaProvider(/*streaming=*/false);
        installBetaRemoteBackend();

        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        betaRouter.tell(new InferenceRouter.ChatRequest(
            "req-loop", "test-model",
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            32, 0.0, probe.ref(), "remote-alpha-natstest"));

        var ok = probe.expectMessageClass(
            InferenceRouter.InferOk.class, Duration.ofSeconds(10));
        assertThat(ok.content())
            .as("provider must pin to local backend, not bounce via NatsRemote")
            .isEqualTo("served locally");
    }

    @Test void provider_fails_fast_when_local_backend_down_instead_of_bouncing() throws Exception {
        // The nastier case: local backend marked UNHEALTHY on alpha. Before the
        // localOnly fix (task #200), selectBackendByName fell back to normal
        // selection when the preferred name was DOWN — and that fallback could
        // pick a NatsRemote, bouncing the cross-zone request onward. With the
        // fix, the provider must fail the incoming request instead.
        //
        // We configure alpha with NO /health stub so the health check fails and
        // test-backend is marked DOWN. A NatsRemote sits alongside it; if
        // anything fell back to it, the caller below would throw "Loop detected".
        wiremock.resetAll();
        wiremock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(500).withBody("should not be called")));

        alphaRouter.tell(new InferenceRouter.AddRemoteBackend(
            "test-backend", "llama-server", wiremock.baseUrl(),
            List.of("test-model"), 50));
        alphaRouter.tell(new InferenceRouter.SetNatsRemoteCaller(
            (zone, src, r, cb) -> {
                throw new IllegalStateException(
                    "Loop detected: alpha tried to bounce to " + zone + " when local was DOWN");
            }));
        alphaRouter.tell(new InferenceRouter.AddRemoteBackend(
            "remote-gamma", "llama-server", "nats://gamma",
            List.of("test-model"), 105));
        // Wait long enough for the async /health check to run and mark DOWN.
        sleep(800);

        startAlphaProvider(/*streaming=*/false);

        var req = NatsInferenceClient.build(
            "beta", "test-agent", "test-model",
            null, "hi", 32, 0.0, false);
        var future = betaClient.request("alpha", req);

        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
            .as("request must fail, not bounce cross-zone via NatsRemote")
            .rootCause()
            .satisfies(cause -> {
                assertThat(cause.getMessage())
                    .as("the error must come from provider's local-backend check, "
                      + "NOT from the loop-detecting caller")
                    .doesNotContain("Loop detected");
            });
    }

    private void stubNonStreamingResponse(String text, int promptTokens, int completionTokens) {
        var body = String.format("""
            {"id":"cmpl-1","object":"chat.completion","created":1,"model":"test-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}
            """, text, promptTokens, completionTokens, promptTokens + completionTokens);
        wiremock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

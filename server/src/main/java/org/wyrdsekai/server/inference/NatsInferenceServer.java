package org.wyrdsekai.server.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.BooleanSupplier;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.inference.NatsInferenceProtocol;
import org.wyrdsekai.common.model.QuotaPolicy;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cross-zone inference provider: subscribes on
 * {@code federation.inference.{localZone}.complete} and handles incoming requests
 * by either:
 *  <ul>
 *    <li><b>Streaming path</b> (preferred, when {@code WYRDSEKAI_INFERENCE_URL} is set):
 *        opens an SSE stream to the local OpenAI-compatible endpoint with
 *        {@code stream=true}, publishes each token as a non-terminal chunk on
 *        {@code federation.inference.stream.{streamId}}, then a terminal chunk.</li>
 *    <li><b>Non-streaming fallback</b>: routes through the local InferenceRouter
 *        (preferring {@code localBackendName} so cross-zone loopback is impossible),
 *        publishes one terminal chunk with the full text.</li>
 *  </ul>
 *
 * <p>Streaming is the default when the inference URL is configured; set
 * {@code WYRDSEKAI_NATS_INFERENCE_STREAMING=false} to force non-streaming.</p>
 */
public final class NatsInferenceServer {

    private static final Logger log = LoggerFactory.getLogger(NatsInferenceServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RelaySessionTransport transport;
    private final String localZoneId;
    private final ActorRef<InferenceRouter.Command> router;
    private final ActorSystem<?> system;
    private final String localBackendName;
    private final String inferenceUrl;
    private final boolean streamingEnabled;
    private final HttpClient httpClient;
    private final ExecutorService streamExecutor =
        Executors.newCachedThreadPool(r -> {
            var t = new Thread(r, "nats-inf-stream");
            t.setDaemon(true);
            return t;
        });
    private volatile Object subscription;

    /**
     * Resolves the {@link QuotaPolicy} this zone applies to incoming inference
     * requests from a given source zone, or {@code null} for no enforcement on
     * that zone. Wired from Main.java against FederationService.
     */
    @FunctionalInterface
    public interface QuotaResolver {
        QuotaPolicy resolve(String sourceZone);
    }

    private volatile QuotaResolver quotaResolver;
    // Household inference auto-share: when sharing is on
    // a request from a household member is served with an unlimited quota, overriding
    // any bilateral agreement. Non-household callers are unaffected.
    //
    // Audit F7 (pre-OSS): the member identity used to be a self-asserted node-id
    // string (any stranger who guessed a household node id got free unmetered
    // inference). It is now a cryptographic check — the requester signs the claim
    // with its node Ed25519 key and the verifier confirms the signature against the
    // public key on file for that node (HouseholdStore). See HouseholdVerifier.
    private volatile HouseholdVerifier householdVerifier;
    private volatile BooleanSupplier householdShareEnabled;

    /** How fresh a signed household request's authTs must be (anti-replay window). */
    private static final long HOUSEHOLD_AUTH_MAX_SKEW_MS = Duration.ofMinutes(5).toMillis();

    /**
     * Verifies that a request genuinely originates from a trusted household member.
     * Returns true only when {@code node} is a known household node AND {@code sig}
     * is a valid signature (by that node's key) over {@code signingData}. Audit F7.
     */
    @FunctionalInterface
    public interface HouseholdVerifier {
        boolean verify(String node, byte[] signingData, String sigBase64);
    }

    /** Per-source-zone daily token usage for incoming requests (provider side). */
    private final ConcurrentHashMap<String, AtomicLong> incomingDailyTokens = new ConcurrentHashMap<>();

    /**
     * Provider-side dedup on streamId — drops a redelivered request instead of
     * re-running inference + double-publishing (spec/tla/InferenceRedelivery.tla, P2).
     * A no-op on today's core-NATS transport (single delivery); hardens against any
     * future at-least-once / JetStream delivery or accidental re-publish.
     */
    private final ServedRequestDedup servedRequests = new ServedRequestDedup(8192);
    private volatile LocalDate trackingDate = LocalDate.now(ZoneOffset.UTC);

    /** Default max-tokens assumption when a request omits the field (conservative). */
    private static final long DEFAULT_REQUEST_TOKEN_ESTIMATE = 512;

    public NatsInferenceServer(RelaySessionTransport transport, String localZoneId,
                                ActorRef<InferenceRouter.Command> router,
                                ActorSystem<?> system,
                                String localBackendName) {
        this(transport, localZoneId, router, system, localBackendName,
            WyrdConfig.get().inferenceUrl(),
            WyrdConfig.get().resolveBool(
                "WYRDSEKAI_NATS_INFERENCE_STREAMING", "inference.nats_streaming", true));
    }

    /**
     * Explicit-config constructor — lets tests and embedded harnesses inject the
     * inference URL and streaming toggle without depending on process env vars.
     * Streaming is only enabled if both {@code streamingEnabled=true} and the URL
     * is non-blank.
     */
    public NatsInferenceServer(RelaySessionTransport transport, String localZoneId,
                                ActorRef<InferenceRouter.Command> router,
                                ActorSystem<?> system,
                                String localBackendName,
                                String inferenceUrl,
                                boolean streamingEnabled) {
        this.transport = transport;
        this.localZoneId = localZoneId;
        this.router = router;
        this.system = system;
        this.localBackendName = localBackendName;
        this.inferenceUrl = inferenceUrl;
        this.streamingEnabled = streamingEnabled && inferenceUrl != null && !inferenceUrl.isBlank();
        // Pin HTTP/1.1: the OpenAI-compatible backends we talk to (llama-server,
        // sglang, vllm) serve HTTP/1.1, and SSE streaming is an HTTP/1.1 mechanism.
        // java.net.http defaults to HTTP/2 and attempts an h2c upgrade on plaintext
        // http://, which some servers (and the WireMock jetty12 test harness) answer
        // with a connection close → "EOF reached while reading" on the /v1/models
        // health probe. HTTP/1.1 avoids the upgrade dance entirely.
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Install a quota resolver. Called from Main.java after FederationService is
     * available. Without a resolver, enforcement is disabled (v0 behavior).
     */
    public void setQuotaResolver(QuotaResolver resolver) {
        this.quotaResolver = resolver;
    }

    /**
     * Install the household auto-share gate. When sharing
     * is enabled, a request whose source node tests true on {@code member} is served
     * with an unlimited (family) quota regardless of any bilateral agreement. Both
     * args may be null (gate disabled).
     */
    public void setHouseholdGate(HouseholdVerifier verifier,
                                 BooleanSupplier shareEnabled) {
        this.householdVerifier = verifier;
        this.householdShareEnabled = shareEnabled;
    }

    /** Tokens consumed today by this source zone via our inference. Exposed for testing. */
    public long incomingTokensToday(String sourceZone) {
        rollIfNewDay();
        var counter = incomingDailyTokens.get(sourceZone);
        return counter != null ? counter.get() : 0L;
    }

    private void recordIncoming(String sourceZone, long tokens) {
        if (tokens <= 0 || sourceZone == null) return;
        rollIfNewDay();
        incomingDailyTokens.computeIfAbsent(sourceZone, k -> new AtomicLong()).addAndGet(tokens);
    }

    private synchronized void rollIfNewDay() {
        var today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(trackingDate)) {
            incomingDailyTokens.clear();
            trackingDate = today;
        }
    }

    public void start() {
        if (subscription != null) return;
        if (transport == null || !transport.isConnected()) {
            log.warn("NatsInferenceServer: relay transport not connected, skipping");
            return;
        }
        var subject = NatsInferenceProtocol.requestSubject(localZoneId);

        // F25 (2026-04-28): probe local backend BEFORE subscribing. NATS
        // queue-distributes requests across all subscribers on this subject,
        // so any subscriber that can't actually dispatch — e.g. an α-cluster
        // node without a running llama — black-holes ~50% of cross-zone
        // requests. Symptom: test-node saw "All backends failed" because half
        // its requests landed on mac-node (no llama) which returned
        // LocalBackendUnavailable. Fix: skip subscription on dead backend
        // and re-probe periodically so a recovering backend rejoins.
        if (streamingEnabled && !probeBackendHealth()) {
            log.warn("NatsInferenceServer: local backend at {} is NOT responding — "
                + "skipping subscription on {} to avoid black-holing cross-zone "
                + "requests via NATS queue distribution. Will re-probe every 30s "
                + "and subscribe once the backend is reachable.",
                inferenceUrl, subject);
            scheduleReprobe(subject);
            return;
        }

        subscription = transport.subscribe(subject, this::onRequest);
        log.info("NatsInferenceServer: listening on {} (streaming={}, local backend={}, inferenceUrl={})",
            subject, streamingEnabled, localBackendName, inferenceUrl);
    }

    /**
     * Background re-probe loop for the dead-backend skip path. Polls
     * {@link #probeBackendHealth()} every 30s; once the backend comes back,
     * subscribes and stops polling. One scheduler thread total — cheap.
     */
    private void scheduleReprobe(String subject) {
        var probeExec = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "nats-inference-reprobe");
            t.setDaemon(true);
            return t;
        });
        probeExec.scheduleWithFixedDelay(() -> {
            try {
                if (subscription != null) {
                    probeExec.shutdown();
                    return;
                }
                if (transport == null || !transport.isConnected()) return;
                if (!probeBackendHealth()) return;
                subscription = transport.subscribe(subject, this::onRequest);
                log.info("NatsInferenceServer: backend at {} recovered — subscribed to {}",
                    inferenceUrl, subject);
                probeExec.shutdown();
            } catch (Exception e) {
                log.debug("re-probe iteration failed: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Quick liveness probe for the local OpenAI-compatible backend.
     * Used at startup (visibility) and per-request (fail-fast on dead backend).
     * Returns false on any HTTP failure — connection refused, timeout, non-200, etc.
     */
    private boolean probeBackendHealth() {
        if (inferenceUrl == null || inferenceUrl.isBlank()) return false;
        try {
            var probe = HttpRequest.newBuilder()
                .uri(URI.create(inferenceUrl + "/v1/models"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            var resp = httpClient.send(probe, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        if (subscription != null) {
            transport.closeDispatcherObj(subscription);
            subscription = null;
        }
        streamExecutor.shutdownNow();
    }

    private void onRequest(byte[] data) {
        NatsInferenceProtocol.Request req;
        try {
            req = MAPPER.readValue(data, NatsInferenceProtocol.Request.class);
        } catch (Exception e) {
            log.error("Bad inference request on {}: {}",
                NatsInferenceProtocol.requestSubject(localZoneId), e.getMessage());
            return;
        }

        log.info("NatsInferenceServer: request streamId={} from zone '{}' (model={}, agent={}, streaming={})",
            req.streamId(), req.sourceZone(), req.model(), req.agentId(), streamingEnabled);

        // Provider-side dedup (P2): a redelivered request for an already-served
        // streamId must NOT re-run inference and double-publish its reply stream.
        if (!servedRequests.firstSight(req.streamId())) {
            log.warn("NatsInferenceServer: dropping duplicate/redelivered request streamId={} "
                + "(already served) — refusing to re-run inference + double-publish.", req.streamId());
            return;
        }

        // Quota enforcement — reject before dispatch if the incoming request would
        // push this source zone over its bilateral allowance. v1 economy checks
        // against the agreement's localQuota (what we allow others to consume from us).
        var denial = checkQuota(req);
        if (denial != null) {
            log.info("NatsInferenceServer: denying streamId={} from '{}' — {}",
                req.streamId(), req.sourceZone(), denial);
            sendError(req.streamId(), denial);
            return;
        }

        if (streamingEnabled) {
            streamExecutor.submit(() -> runStreaming(req));
        } else {
            runNonStreaming(req);
        }
    }

    /** Returns null if request is allowed, or an error message describing the denial. */
    private String checkQuota(NatsInferenceProtocol.Request req) {
        // Household inference auto-share: a household member is served unlimited when
        // sharing is on, overriding any agreement-based cap.
        // Audit F7: the exemption is granted ONLY when the request carries a fresh,
        // valid Ed25519 signature by the claimed source node (verified against the
        // public key we hold for it) — a self-asserted sourceNode is no longer
        // trusted. Any failure falls through to the bilateral quota below.
        var verifier = this.householdVerifier;
        var shareEnabled = this.householdShareEnabled;
        if (verifier != null && shareEnabled != null && shareEnabled.getAsBoolean()
                && req.sourceNode() != null && req.sig() != null && req.authTs() != null) {
            long skew = Math.abs(System.currentTimeMillis() - req.authTs());
            if (skew > HOUSEHOLD_AUTH_MAX_SKEW_MS) {
                log.warn("NatsInferenceServer: household exemption DENIED for node={} streamId={} "
                    + "— stale authTs (skew={}ms > {}ms)", req.sourceNode(), req.streamId(),
                    skew, HOUSEHOLD_AUTH_MAX_SKEW_MS);
            } else {
                var signingData = NatsInferenceProtocol.householdSigningData(
                    req.streamId(), req.sourceZone(), req.sourceNode(), req.authTs());
                if (verifier.verify(req.sourceNode(), signingData, req.sig())) {
                    return null; // allowed — verified household exemption
                }
                log.warn("NatsInferenceServer: household exemption DENIED for node={} streamId={} "
                    + "— signature did not verify; falling back to quota", req.sourceNode(), req.streamId());
            }
        }
        var resolver = this.quotaResolver;
        if (resolver == null || req.sourceZone() == null) return null;
        var quota = resolver.resolve(req.sourceZone());
        if (quota == null) return null;
        long reqTokens = req.maxTokens() != null && req.maxTokens() > 0
            ? req.maxTokens() : DEFAULT_REQUEST_TOKEN_ESTIMATE;
        long usedToday = incomingTokensToday(req.sourceZone());
        if (!quota.allowInference(usedToday, reqTokens)) {
            return "QuotaExceeded: used=" + usedToday + " + requested=" + reqTokens
                 + " > daily=" + quota.inferenceTokensPerDay()
                 + " (zone='" + req.sourceZone() + "')";
        }
        return null;
    }

    /**
     * SSE streaming path: POST to the local OpenAI-compatible endpoint with
     * {@code stream=true}, publish each delta as a StreamChunk, then a terminal one.
     */
    private void runStreaming(NatsInferenceProtocol.Request req) {
        try {
            var body = MAPPER.createObjectNode();
            body.put("model", req.model() != null ? req.model() : "");
            var msgs = body.putArray("messages");
            for (var m : req.messages()) {
                var mNode = msgs.addObject();
                mNode.put("role", m.role());
                mNode.put("content", m.content());
            }
            if (req.maxTokens() != null) body.put("max_tokens", req.maxTokens());
            if (req.temperature() != null) body.put("temperature", req.temperature());
            body.put("stream", true);
            // Include usage stats in the final SSE chunk so metering gets accurate token counts.
            var streamOpts = body.putObject("stream_options");
            streamOpts.put("include_usage", true);
            // llama-server / SGLang: enable_thinking=false in chat template to avoid <think> blocks
            var chatKwargs = body.putObject("chat_template_kwargs");
            chatKwargs.put("enable_thinking", false);

            var httpReq = HttpRequest.newBuilder()
                .uri(URI.create(inferenceUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

            var response = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (var errStream = response.body()) {
                    var err = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                    sendError(req.streamId(), "HTTP " + response.statusCode() + ": " + err);
                }
                return;
            }

            int totalPrompt = 0, totalCompletion = 0;
            String finishReason = "stop";
            var fullText = new StringBuilder();

            try (var reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) continue;
                    var payload = line.substring(5).trim();
                    if (payload.equals("[DONE]")) break;
                    try {
                        var node = MAPPER.readTree(payload);
                        // Delta token
                        var choices = node.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                            var first = choices.get(0);
                            var delta = first.path("delta");
                            var token = delta.path("content").asText(null);
                            if (token != null && !token.isEmpty()) {
                                fullText.append(token);
                                publishToken(req.streamId(), token);
                            }
                            var fr = first.path("finish_reason").asText(null);
                            if (fr != null && !fr.isBlank()) finishReason = fr;
                        }
                        var usage = node.path("usage");
                        if (usage.isObject()) {
                            var pt = usage.path("prompt_tokens").asInt(-1);
                            var ct = usage.path("completion_tokens").asInt(-1);
                            if (pt > 0) totalPrompt = pt;
                            if (ct > 0) totalCompletion = ct;
                        }
                    } catch (Exception parseErr) {
                        log.debug("SSE parse error (skipping line): {}", parseErr.getMessage());
                    }
                }
            }

            recordIncoming(req.sourceZone(), totalPrompt + totalCompletion);
            publishChunk(new NatsInferenceProtocol.StreamChunk(
                req.streamId(), null, fullText.toString(), true,
                totalPrompt > 0 ? totalPrompt : null,
                totalCompletion > 0 ? totalCompletion : null,
                finishReason, null));
            log.info("NatsInferenceServer: streaming done streamId={} ({} tokens)",
                req.streamId(), totalCompletion);
        } catch (Exception e) {
            // F22: e.getMessage() returns null for ConnectException (and a few
            // other Throwables), which led to the silent "streaming failed: null"
            // pattern that masked a 17-hour outage. Use describeError() so the
            // class name + URL are always visible, and translate connection-
            // refused into a typed LocalBackendUnavailable so β can fail fast
            // instead of retrying forever.
            var msg = describeStreamingError(e);
            log.error("NatsInferenceServer streaming failed for streamId={}: {}",
                req.streamId(), msg);
            sendError(req.streamId(), msg);
        }
    }

    /**
     * Produce a meaningful error string for the streaming-failure path.
     * {@link Throwable#getMessage()} can return {@code null} for things like
     * {@link java.net.ConnectException} when the JVM didn't attach a message;
     * we always want the caller to see something more useful than the literal
     * string "null". Connection-refused gets a typed prefix so the β-side
     * (and operators) can recognise the dead-backend case at a glance.
     */
    private String describeStreamingError(Throwable e) {
        // Walk the cause chain to find a ConnectException — the streaming HTTP
        // call wraps it in IOException sometimes.
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof ConnectException) {
                return "LocalBackendUnavailable: " + inferenceUrl
                    + " (" + cur.getClass().getSimpleName()
                    + (cur.getMessage() != null ? ": " + cur.getMessage() : "")
                    + ")";
            }
            cur = cur.getCause();
        }
        var name = e.getClass().getSimpleName();
        var raw = e.getMessage();
        return raw != null ? name + ": " + raw : name;
    }

    /** Non-streaming fallback via InferenceRouter. */
    private void runNonStreaming(NatsInferenceProtocol.Request req) {
        var chatMessages = new ArrayList<InferenceClient.ChatMessage>();
        for (var m : req.messages()) {
            chatMessages.add(new InferenceClient.ChatMessage(m.role(), m.content()));
        }
        AskPattern.<InferenceRouter.Command, InferenceRouter.InferResponse>ask(
            router,
            replyTo -> new InferenceRouter.ChatRequest(
                UUID.randomUUID().toString(), req.model(), chatMessages,
                req.maxTokens() != null ? req.maxTokens() : 512,
                req.temperature() != null ? req.temperature() : 0.7,
                replyTo, localBackendName,
                null, null, null, null, null, null, null,
                /* localOnly = */ true),  // LOOP PREVENTION: never bounce to NatsRemote
            Duration.ofSeconds(90),
            system.scheduler()
        ).whenComplete((resp, err) -> {
            if (err != null) sendError(req.streamId(), err.getMessage());
            else if (resp instanceof InferenceRouter.InferOk ok) {
                recordIncoming(req.sourceZone(), ok.promptTokens() + ok.completionTokens());
                publishChunk(new NatsInferenceProtocol.StreamChunk(
                    req.streamId(), null, ok.content(), true,
                    ok.promptTokens(), ok.completionTokens(), "stop", null));
            } else if (resp instanceof InferenceRouter.InferError oops) {
                sendError(req.streamId(), oops.error());
            }
        });
    }

    private void publishToken(String streamId, String token) {
        publishChunk(new NatsInferenceProtocol.StreamChunk(
            streamId, token, null, false, null, null, null, null));
    }

    private void sendError(String streamId, String message) {
        publishChunk(new NatsInferenceProtocol.StreamChunk(
            streamId, null, null, true, null, null, null, message));
    }

    private void publishChunk(NatsInferenceProtocol.StreamChunk chunk) {
        try {
            transport.publish(NatsInferenceProtocol.streamSubject(chunk.streamId()),
                MAPPER.writeValueAsBytes(chunk));
        } catch (Exception e) {
            log.error("Failed to publish inference chunk streamId={}: {}",
                chunk.streamId(), e.getMessage());
        }
    }
}

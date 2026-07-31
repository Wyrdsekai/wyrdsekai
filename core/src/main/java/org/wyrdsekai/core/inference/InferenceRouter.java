package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.agent.AssembledPrompt;
import org.wyrdsekai.core.economy.CrossZoneExchange;
import org.wyrdsekai.core.economy.MeteringService;
import org.wyrdsekai.core.economy.ReferenceRates;
import org.wyrdsekai.core.economy.ResourceMeter;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Actor that routes inference requests to available backends.
 * Supports N backends with priority-ordered selection and automatic fallback.
 * Periodic health checks (default 30s) keep backend status current.
 */
public class InferenceRouter extends AbstractBehavior<InferenceRouter.Command> {

    private static final Logger log = LoggerFactory.getLogger(InferenceRouter.class);
    private static final String HEALTH_CHECK_TIMER = "health-check";

    // --- Protocol ---

    public sealed interface Command {}

    /** Request inference from the router. */
    public record InferRequest(
        String requestId,
        String model,           // model name (null = use default)
        String systemPrompt,
        String userMessage,
        int maxTokens,
        double temperature,
        ActorRef<InferResponse> replyTo,
        String grammar          // GBNF grammar string (null = unconstrained)
    ) implements Command {
        /** Backward-compatible constructor without grammar. */
        public InferRequest(String requestId, String model, String systemPrompt,
                            String userMessage, int maxTokens, double temperature,
                            ActorRef<InferResponse> replyTo) {
            this(requestId, model, systemPrompt, userMessage, maxTokens, temperature, replyTo, null);
        }
    }

    /** Multi-turn chat request. */
    public record ChatRequest(
        String requestId,
        String model,
        List<InferenceClient.ChatMessage> messages,
        int maxTokens,
        double temperature,
        ActorRef<InferResponse> replyTo,
        String preferredBackend,   // nullable — hint for zone relay inference
        String grammar,            // GBNF grammar (llama-server, null = unconstrained)
        Object format,             // Ollama JSON Schema format (null = unconstrained)
        List<InferenceClient.ToolDefinition> tools,  // Tool calling definitions (null = no tools)
        String toolChoice,         // "auto", "required", or null (default: auto when tools present)
        Double topP,               // nullable — drive-modulated top_p (null = server default)
        Double presencePenalty,    // nullable — drive-modulated (null = server default)
        Double repetitionPenalty,  // nullable — drive-modulated (null = server default)
        boolean localOnly,         // if true, NatsRemote backends are excluded from selection
                                    // (used by cross-zone provider to prevent inference loops)
        Map<String, Double> registerMix  // Individuality V2.4 — per-agent voice register
                                    // coefficients from the TemperamentSeed; threaded to the
                                    // local provider body (lora[]/register_mix{}). null = no
                                    // voice steering (every non-voice-pass call).
    ) implements Command {
        /** Old canonical signature (pre-V2.4 register mix) — delegates with no voice steering. */
        public ChatRequest(String requestId, String model,
                          List<InferenceClient.ChatMessage> messages,
                          int maxTokens, double temperature,
                          ActorRef<InferResponse> replyTo, String preferredBackend,
                          String grammar, Object format,
                          List<InferenceClient.ToolDefinition> tools, String toolChoice,
                          Double topP, Double presencePenalty, Double repetitionPenalty,
                          boolean localOnly) {
            this(requestId, model, messages, maxTokens, temperature, replyTo,
                 preferredBackend, grammar, format, tools, toolChoice,
                 topP, presencePenalty, repetitionPenalty, localOnly, null);
        }
        /**
         * build a ChatRequest from a tagged
         * {@link org.wyrdsekai.core.agent.AssembledPrompt}. The prompt's
         * {@code backendId} becomes both the {@code model} (capability hint)
         * and the {@code preferredBackend} field — the dispatcher and
         * the assembler can no longer disagree about which backend the
         * prompt was built for. Use this factory in place of constructing
         * ChatRequest directly when you have a tagged prompt.
         */
        public static ChatRequest fromPrompt(
                String requestId,
                AssembledPrompt prompt,
                int maxTokens, double temperature,
                ActorRef<InferResponse> replyTo,
                String grammar, Object format,
                List<InferenceClient.ToolDefinition> tools, String toolChoice,
                Double topP, Double presencePenalty, Double repetitionPenalty) {
            return new ChatRequest(requestId, prompt.backendId(),
                prompt.messages(), maxTokens, temperature, replyTo,
                prompt.backendId(), grammar, format, tools, toolChoice,
                topP, presencePenalty, repetitionPenalty, false);
        }

        /** F15: minimal {@code fromPrompt} for callers that don't need
         * grammar/tools/sampling-params. */
        public static ChatRequest fromPrompt(
                String requestId,
                AssembledPrompt prompt,
                int maxTokens, double temperature,
                ActorRef<InferResponse> replyTo) {
            return new ChatRequest(requestId, prompt.backendId(),
                prompt.messages(), maxTokens, temperature, replyTo,
                prompt.backendId(), null, null, null, null,
                null, null, null, false);
        }

        /** Backward-compatible constructor without grammar/format/tools. */
        public ChatRequest(String requestId, String model,
                          List<InferenceClient.ChatMessage> messages,
                          int maxTokens, double temperature,
                          ActorRef<InferResponse> replyTo) {
            this(requestId, model, messages, maxTokens, temperature, replyTo,
                 null, null, null, null, null, null, null, null, false);
        }
        /** Backward-compatible constructor without grammar but with backend. */
        public ChatRequest(String requestId, String model,
                          List<InferenceClient.ChatMessage> messages,
                          int maxTokens, double temperature,
                          ActorRef<InferResponse> replyTo, String preferredBackend) {
            this(requestId, model, messages, maxTokens, temperature, replyTo,
                 preferredBackend, null, null, null, null, null, null, null, false);
        }
        /** Constructor with grammar (pre-format). */
        public ChatRequest(String requestId, String model,
                          List<InferenceClient.ChatMessage> messages,
                          int maxTokens, double temperature,
                          ActorRef<InferResponse> replyTo, String preferredBackend,
                          String grammar) {
            this(requestId, model, messages, maxTokens, temperature, replyTo,
                 preferredBackend, grammar, null, null, null, null, null, null, false);
        }
        /** Constructor with format (pre-tools). */
        public ChatRequest(String requestId, String model,
                          List<InferenceClient.ChatMessage> messages,
                          int maxTokens, double temperature,
                          ActorRef<InferResponse> replyTo, String preferredBackend,
                          String grammar, Object format) {
            this(requestId, model, messages, maxTokens, temperature, replyTo,
                 preferredBackend, grammar, format, null, null, null, null, null, false);
        }
        /** Constructor with tools (pre-toolChoice). */
        public ChatRequest(String requestId, String model,
                          List<InferenceClient.ChatMessage> messages,
                          int maxTokens, double temperature,
                          ActorRef<InferResponse> replyTo, String preferredBackend,
                          String grammar, Object format,
                          List<InferenceClient.ToolDefinition> tools) {
            this(requestId, model, messages, maxTokens, temperature, replyTo,
                 preferredBackend, grammar, format, tools, null, null, null, null, false);
        }
        /** Constructor with tools + toolChoice (pre-sampling params). */
        public ChatRequest(String requestId, String model,
                          List<InferenceClient.ChatMessage> messages,
                          int maxTokens, double temperature,
                          ActorRef<InferResponse> replyTo, String preferredBackend,
                          String grammar, Object format,
                          List<InferenceClient.ToolDefinition> tools, String toolChoice) {
            this(requestId, model, messages, maxTokens, temperature, replyTo,
                 preferredBackend, grammar, format, tools, toolChoice, null, null, null, false);
        }
        /** Constructor with full sampling params but no localOnly (pre-localOnly default). */
        public ChatRequest(String requestId, String model,
                          List<InferenceClient.ChatMessage> messages,
                          int maxTokens, double temperature,
                          ActorRef<InferResponse> replyTo, String preferredBackend,
                          String grammar, Object format,
                          List<InferenceClient.ToolDefinition> tools, String toolChoice,
                          Double topP, Double presencePenalty, Double repetitionPenalty) {
            this(requestId, model, messages, maxTokens, temperature, replyTo,
                 preferredBackend, grammar, format, tools, toolChoice,
                 topP, presencePenalty, repetitionPenalty, false);
        }
    }

    /**
     * Tool inference request — raw capability, NO soul prompt injection, NO vitality modulation.
     * The agent uses LLMs as tools: identity stays on the small model, heavy thinking
     * is delegated to capable models without personality contamination.
     *-§17.
     *
     * @param maxTier maximum tier for backend selection ("local", "household", "cloud");
     *                null means unrestricted (backward compatible)
     */
    public record ToolInferRequest(
        String requestId,
        String agentId,         // who's asking (for metering + logging)
        String capability,      // "reasoning", "coding", "analysis", "quick" — or null for default
        String model,           // specific model override (nullable — let router pick)
        String systemPrompt,    // optional system prompt for the tool call (NOT the soul prompt)
        String prompt,          // the task prompt
        int maxTokens,
        String maxTier,         // nullable — "local", "household", or "cloud"
        ActorRef<InferResponse> replyTo
    ) implements Command {
        /** Backward-compatible constructor without maxTier. */
        public ToolInferRequest(String requestId, String agentId, String capability,
                                String model, String systemPrompt, String prompt,
                                int maxTokens, ActorRef<InferResponse> replyTo) {
            this(requestId, agentId, capability, model, systemPrompt, prompt,
                 maxTokens, null, replyTo);
        }
    }

    /** List all configured backends and their health status. */
    public record ListBackends(ActorRef<BackendList> replyTo) implements Command {}

    /** Internal: result from async inference call. */
    private record InferResult(String requestId, String content,
                                String backendName, String model,
                                String agentId,  // nullable — set for tool inference
                                InferenceClient.Usage usage,
                                ActorRef<InferResponse> replyTo) implements Command {
        /** Backward-compatible constructor without model/agentId. */
        InferResult(String requestId, String content, String backendName,
                    InferenceClient.Usage usage, ActorRef<InferResponse> replyTo) {
            this(requestId, content, backendName, null, null, usage, replyTo);
        }
    }

    /** Internal: failure from async inference call with fallback context. */
    /**
     * @param compacted         this request has already been shrunk once for a context
     *                          overflow — a second overflow is an honest failure, not
     *                          another retry.
     * @param fallbackAttempted this request has already been re-dispatched to a second
     *                          backend — bounds recovery at one cross-backend hop so a
     *                          two-backend household cannot ping-pong a broken request.
     */
    private record InferFailure(String requestId, String error,
                                 String failedBackend,
                                 InferenceClient.ChatRequest chatReq,
                                 ActorRef<InferResponse> replyTo,
                                 boolean localOnly,
                                 boolean compacted,
                                 boolean fallbackAttempted) implements Command {
        InferFailure(String requestId, String error, String failedBackend,
                     InferenceClient.ChatRequest chatReq, ActorRef<InferResponse> replyTo) {
            this(requestId, error, failedBackend, chatReq, replyTo, false, false, false);
        }
        InferFailure(String requestId, String error, String failedBackend,
                     InferenceClient.ChatRequest chatReq, ActorRef<InferResponse> replyTo,
                     boolean localOnly) {
            this(requestId, error, failedBackend, chatReq, replyTo, localOnly, false, false);
        }
    }

    /** Internal: periodic health check tick. */
    private record HealthCheckTick() implements Command {}

    /** Internal: health check result for a specific backend. */
    private record HealthCheckResult(String backendName, boolean healthy) implements Command {}

    /** Internal: trigger queue drain from async callback (must be on actor thread). */
    private record DrainQueueTick() implements Command {}

    /**
     * Add or update a remote inference backend discovered via the mesh.
     * If a backend with the same name exists, it's replaced.
     */
    public record AddRemoteBackend(String name, String type, String url,
                                    List<String> models, int priority,
                                    boolean household) implements Command {
        /** Back-compat — a discovered backend with no household-trust tag. */
        public AddRemoteBackend(String name, String type, String url,
                                List<String> models, int priority) {
            this(name, type, url, models, priority, false);
        }
    }

    /** Remove a remote backend (peer disconnected or timed out). */
    public record RemoveRemoteBackend(String name) implements Command {}

    /**
     * Externally drive a backend's health status (task #36). The cross-zone
     * NATS remote ({@link InferenceBackend.NatsRemote}) has no cheap honest
     * liveness probe — {@code healthCheck()} can only optimistically return
     * true — so a borrowed 9B that dies would keep being selected until the
     * next dispatch eats the full ~120s NATS req/reply timeout.
     *
     * <p>The authoritative liveness signal for a discovered remote is the
     * discovery miss-counter in {@code Main.java}: when a peer stops announcing
     * its inference endpoint, the discovery loop sends {@code SetBackendHealth
     * (name, false)} on the first miss (~15s), so {@code selectBackend} skips it
     * and falls back to local 4B immediately — long before the dispatch timeout.
     * When the peer re-appears, the loop sends {@code SetBackendHealth(name,
     * true)} to lift the exile (no permanent removal). No-op if the named
     * backend isn't configured.</p>
     */
    public record SetBackendHealth(String name, boolean healthy) implements Command {}

    /** Set the NATS remote caller. Must be set before any {@code nats://} AddRemoteBackend. */
    public record SetNatsRemoteCaller(InferenceBackend.NatsRemote.RemoteCaller caller) implements Command {}

    /**
     * Streaming chat request — delivers tokens as they arrive via {@code streamRef}
     * actor messages (see {@link StreamEvent}), then a final {@link InferResponse}
     * via {@code replyTo}. Useful for user-facing inference where progressive
     * output improves perceived latency.
     *
     * <p>If the selected backend doesn't support native streaming, the full
     * response is delivered as a single {@link StreamEvent.Token} before
     * {@link StreamEvent.Done} — callers get a consistent protocol either way.</p>
     */
    public record StreamingChatRequest(
        String requestId,
        String model,
        List<InferenceClient.ChatMessage> messages,
        int maxTokens,
        double temperature,
        ActorRef<StreamEvent> streamRef,        // per-token chunks + terminal event
        ActorRef<InferResponse> replyTo,        // final ok/error after stream completes
        String preferredBackend,                // nullable
        boolean localOnly                        // exclude NatsRemote from selection (loop prevention)
    ) implements Command {
        /** Backward-compatible constructor. */
        public StreamingChatRequest(String requestId, String model,
                                     List<InferenceClient.ChatMessage> messages,
                                     int maxTokens, double temperature,
                                     ActorRef<StreamEvent> streamRef,
                                     ActorRef<InferResponse> replyTo) {
            this(requestId, model, messages, maxTokens, temperature,
                 streamRef, replyTo, null, false);
        }
    }

    /** Streaming events delivered to the stream ref of a {@link StreamingChatRequest}. */
    public sealed interface StreamEvent {
        String requestId();
        /** A token chunk. Multiple of these arrive in order. */
        record Token(String requestId, String token) implements StreamEvent {}
        /** Terminal success event — emitted after all {@link Token}s. */
        record Done(String requestId, String fullText,
                    int promptTokens, int completionTokens,
                    String finishReason) implements StreamEvent {}
        /** Terminal failure event — no more tokens will arrive. */
        record Error(String requestId, String message) implements StreamEvent {}
    }

    // --- Response ---

    public sealed interface InferResponse {}

    public record InferOk(String requestId, String content,
                          int promptTokens, int completionTokens) implements InferResponse {}

    public record InferError(String requestId, String error) implements InferResponse {}

    /** Backend list response for ListBackends queries. */
    public record BackendList(List<BackendInfo> backends) {}

    public record BackendInfo(String name, String type, boolean healthy,
                               int priority, List<String> models, String url,
                               boolean household) {
        /** Back-compat — a backend with no household-trust tag. */
        public BackendInfo(String name, String type, boolean healthy,
                           int priority, List<String> models, String url) {
            this(name, type, healthy, priority, models, url, false);
        }
    }

    // --- State ---

    private final ArrayList<InferenceBackend> backends;  // sorted by priority, mutable for remote discovery
    private final Set<String> remoteBackendNames = new HashSet<>();  // tracks mesh-discovered backends
    private final Set<String> householdBackendNames = new HashSet<>();  // discovered backends on household-trusted peers
    // Lazily-discovered model id per backend name, used to recover from the
    // config-time /v1/models probe losing the cold-start race (see resolveModel).
    private final ConcurrentHashMap<String, String> lazyModelCache = new ConcurrentHashMap<>();
    /** Caller for NATS remote backends; wired by Main.java once the relay transport exists. */
    private volatile InferenceBackend.NatsRemote.RemoteCaller natsRemoteCaller;
    private final Map<String, Boolean> healthStatus;
    private final String defaultModel;
    private final ResourceMeter resourceMeter;  // nullable
    private final CapabilityRegistry capabilityRegistry;  // nullable
    private final ApiKeyProvider apiKeyProvider;  // nullable

    /**
     * Inference queue — manages concurrent backend requests based on backend capacity.
     * Backends declare their max concurrency (e.g., Ollama=1, SGLang=128).
     * Requests are priority-ordered: human-triggered > tool > autonomy.
     * When in-flight count drops below max concurrency, next queued request fires.
     */
    private final PriorityQueue<QueuedRequest> inferenceQueue =
        new PriorityQueue<>(Comparator.comparingInt(QueuedRequest::priority));
    private int inferenceInFlightCount = 0;
    private static final int MAX_QUEUE_SIZE = 20;

    /**
     * Maximum concurrent inference requests. Configurable via WYRDSEKAI_INFERENCE_CONCURRENCY.
     * Default: 1 (serialize all requests — safe for Ollama/single-GPU).
     * Set higher for backends with continuous batching (SGLang=128, vLLM=64).
     */
    private final int maxConcurrency = Integer.parseInt(
        System.getenv().getOrDefault("WYRDSEKAI_INFERENCE_CONCURRENCY", "1"));

    private record QueuedRequest(int priority, Command command) {}

    /**
     * Queue priority constants — lower = served first.
     * primary turns outrank bunshins outrank familiars.
     * Tool-inference and ambient requests slot between these as before.
     */
    /** Primary agent (human-facing) turn. */
    static final int PRIORITY_HUMAN = 0;
    /** Tool inference (delegated thinking by primary). */
    static final int PRIORITY_TOOL = 1;
    /** Bunshin — parallel self of a primary. (§6.3: yields to primary) */
    static final int PRIORITY_BUNSHIN = 2;
    /** Familiar — summoned work item. (§1: lowest of the three agent classes) */
    static final int PRIORITY_FAMILIAR = 3;
    /** Ambient / background requests. */
    static final int PRIORITY_AMBIENT = 4;

    /**
     * Classify a queued chat request by its requestId prefix. Lower = higher
     * priority. When a request has no identifying prefix, default to HUMAN.
     */
    static int classifyPriority(String requestId) {
        if (requestId == null) return PRIORITY_HUMAN;
        if (requestId.startsWith("bunshin-")) return PRIORITY_BUNSHIN;
        if (requestId.startsWith("familiar-")) return PRIORITY_FAMILIAR;
        if (requestId.startsWith("tool-") || requestId.startsWith("item-")) return PRIORITY_TOOL;
        return PRIORITY_HUMAN;
    }

    private InferenceRouter(ActorContext<Command> context,
                            TimerScheduler<Command> timers,
                            List<InferenceBackend> backends,
                            String defaultModel,
                            ResourceMeter resourceMeter,
                            CapabilityRegistry capabilityRegistry,
                            ApiKeyProvider apiKeyProvider,
                            Duration healthCheckInterval) {
        super(context);
        this.backends = new ArrayList<>(backends);
        this.healthStatus = new HashMap<>();
        this.defaultModel = defaultModel;
        this.resourceMeter = resourceMeter;
        this.capabilityRegistry = capabilityRegistry;
        this.apiKeyProvider = apiKeyProvider;

        // Initialize all backends as unknown (optimistically try on first request)
        for (var b : backends) {
            healthStatus.put(b.name(), true);
        }

        // Start periodic health checks
        timers.startTimerWithFixedDelay(HEALTH_CHECK_TIMER,
                new HealthCheckTick(), healthCheckInterval);

        // Run initial health check immediately
        runHealthChecks();

        var names = backends.stream()
                .map(b -> b.name() + "(" + b.type() + ", pri=" + b.priority() + ")")
                .toList();
        log.info("InferenceRouter started — {} backend(s): {}, default model: {}",
                backends.size(), names, defaultModel);
        if (capabilityRegistry != null) {
            log.info("CapabilityRegistry active — capabilities: {}",
                    capabilityRegistry.availableCapabilities());
        }
        if (apiKeyProvider != null) {
            log.info("ApiKeyProvider configured for dynamic key resolution");
        }
    }

    // --- Factory methods ---

    public static Behavior<Command> create(List<InferenceBackend> backends,
                                            String defaultModel,
                                            ResourceMeter resourceMeter,
                                            CapabilityRegistry capabilityRegistry,
                                            ApiKeyProvider apiKeyProvider,
                                            Duration healthCheckInterval) {
        return Behaviors.setup(ctx -> Behaviors.withTimers(timers ->
                new InferenceRouter(ctx, timers, backends, defaultModel,
                        resourceMeter, capabilityRegistry, apiKeyProvider,
                        healthCheckInterval)));
    }

    /** Backward-compatible: no ApiKeyProvider. */
    public static Behavior<Command> create(List<InferenceBackend> backends,
                                            String defaultModel,
                                            ResourceMeter resourceMeter,
                                            CapabilityRegistry capabilityRegistry,
                                            Duration healthCheckInterval) {
        return create(backends, defaultModel, resourceMeter, capabilityRegistry,
                null, healthCheckInterval);
    }

    /** Backward-compatible: no CapabilityRegistry. */
    public static Behavior<Command> create(List<InferenceBackend> backends,
                                            String defaultModel,
                                            ResourceMeter resourceMeter,
                                            Duration healthCheckInterval) {
        return create(backends, defaultModel, resourceMeter, null, null, healthCheckInterval);
    }

    public static Behavior<Command> create(List<InferenceBackend> backends,
                                            String defaultModel,
                                            ResourceMeter resourceMeter) {
        return create(backends, defaultModel, resourceMeter, null, null, Duration.ofSeconds(30));
    }

    /** Backward-compatible factory: wraps local/cloud clients into backend list. */
    public static Behavior<Command> create(InferenceClient localClient,
                                            InferenceClient cloudClient,
                                            String defaultModel,
                                            ResourceMeter resourceMeter) {
        var backends = new ArrayList<InferenceBackend>();
        if (localClient != null) {
            backends.add(new InferenceBackend.LlamaServer(
                    "local", localClient, 10, List.of(), null));
        }
        if (cloudClient != null) {
            backends.add(new InferenceBackend.Cloud(
                    "cloud", cloudClient, 100, List.of()));
        }
        return create(backends, defaultModel, resourceMeter);
    }

    public static Behavior<Command> create(InferenceClient localClient,
                                            InferenceClient cloudClient,
                                            String defaultModel) {
        return create(localClient, cloudClient, defaultModel, null);
    }

    // --- Message handlers ---

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(InferRequest.class, this::onInferRequest)
                .onMessage(ChatRequest.class, this::onChatRequest)
                .onMessage(StreamingChatRequest.class, this::onStreamingChatRequest)
                .onMessage(ToolInferRequest.class, this::onToolInferRequest)
                .onMessage(ListBackends.class, this::onListBackends)
                .onMessage(InferResult.class, this::onInferResult)
                .onMessage(InferFailure.class, this::onInferFailure)
                .onMessage(HealthCheckTick.class, this::onHealthCheckTick)
                .onMessage(HealthCheckResult.class, this::onHealthCheckResult)
                .onMessage(DrainQueueTick.class, msg -> { drainQueue(); return this; })
                .onMessage(AddRemoteBackend.class, this::onAddRemoteBackend)
                .onMessage(RemoveRemoteBackend.class, this::onRemoveRemoteBackend)
                .onMessage(SetBackendHealth.class, this::onSetBackendHealth)
                .onMessage(SetNatsRemoteCaller.class, msg -> {
                    this.natsRemoteCaller = msg.caller();
                    log.info("NATS remote caller registered on InferenceRouter");
                    return this;
                })
                .onSignal(PostStop.class, signal -> {
                    log.info("InferenceRouter stopping");
                    return this;
                })
                .build();
    }

    private Behavior<Command> onInferRequest(InferRequest req) {
        if (inferenceInFlightCount >= maxConcurrency) {
            if (inferenceQueue.size() >= MAX_QUEUE_SIZE) {
                req.replyTo().tell(new InferError(req.requestId(),
                        "Inference queue full (" + MAX_QUEUE_SIZE + " pending)"));
                return this;
            }
            log.info("Queuing inference request {} ({}/{} in flight, queue: {})",
                req.requestId(), inferenceInFlightCount, maxConcurrency, inferenceQueue.size() + 1);
            inferenceQueue.add(new QueuedRequest(PRIORITY_HUMAN, req));
            return this;
        }
        executeInferRequest(req);
        return this;
    }

    private void executeInferRequest(InferRequest req) {
        var model = req.model() != null ? req.model() : defaultModel;
        var backend = selectBackend(model);

        if (backend == null) {
            req.replyTo().tell(new InferError(req.requestId(),
                    "No inference backend available"));
            drainQueue();
            return;
        }

        inferenceInFlightCount++;
        var effectiveModel = resolveModel(model, backend);
        var messages = new ArrayList<InferenceClient.ChatMessage>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            messages.add(new InferenceClient.ChatMessage("system", req.systemPrompt()));
        }
        messages.add(new InferenceClient.ChatMessage("user", req.userMessage()));
        var chatReq = new InferenceClient.ChatRequest(effectiveModel, messages,
                req.maxTokens(), req.temperature(), null, null, req.grammar());
        var self = getContext().getSelf();

        backend.chatCompletion(chatReq)
                .thenAccept(resp -> {
                    var rawContent = resp.choices() != null && !resp.choices().isEmpty()
                            ? resp.choices().getFirst().message().content()
                            : "";
                    // Strip <think>...</think> blocks from Qwen3.5 responses —
                    // the model generates reasoning even in no-think mode when using
                    // Jinja chat templates with thinking=1 (default for Qwen3.5)
                    var content = ActionParser.stripThinkTags(rawContent);
                    if (content == null || content.isBlank()) content = rawContent;
                    self.tell(new InferResult(req.requestId(), content,
                            backend.name(), resp.usage(), req.replyTo()));
                })
                .exceptionally(ex -> {
                    self.tell(new InferFailure(req.requestId(), ex.getMessage(),
                            backend.name(), chatReq, req.replyTo()));
                    return null;
                });
    }

    /**
     * Streaming chat path — the selected backend's {@code chatCompletionStreaming}
     * delivers tokens to the {@code streamRef} actor as they arrive, then the
     * final response is delivered via {@code replyTo}. Terminal success emits
     * one {@link StreamEvent.Done}; failure emits {@link StreamEvent.Error}.
     */
    private Behavior<Command> onStreamingChatRequest(StreamingChatRequest req) {
        var model = req.model() != null ? req.model() : defaultModel;
        var backend = req.preferredBackend() != null
            ? selectBackendByName(req.preferredBackend(), model, req.localOnly())
            : selectBackend(model, req.localOnly());

        if (backend == null) {
            var reason = req.localOnly()
                ? "No local inference backend available (localOnly=true)"
                : "No inference backend available";
            req.streamRef().tell(new StreamEvent.Error(req.requestId(), reason));
            req.replyTo().tell(new InferError(req.requestId(), reason));
            return this;
        }

        inferenceInFlightCount++;
        var effectiveModel = resolveModel(model, backend);
        var chatReq = new InferenceClient.ChatRequest(effectiveModel,
            consolidateSystemMessages(req.messages()),
            req.maxTokens(), req.temperature());

        var self = getContext().getSelf();
        // Per-token callback: forward each chunk to the stream ref as a Token event.
        Consumer<String> tokenCallback = token -> {
            try {
                req.streamRef().tell(new StreamEvent.Token(req.requestId(), token));
            } catch (Exception e) {
                log.warn("streamRef.tell failed for token on {}: {}", req.requestId(), e.getMessage());
            }
        };
        var accumulator = new StringBuilder();
        Consumer<String> forwardAndAccumulate = token -> {
            accumulator.append(token);
            tokenCallback.accept(token);
        };

        backend.chatCompletionStreaming(chatReq, forwardAndAccumulate)
            .thenAccept(resp -> {
                var firstChoice = resp.choices() != null && !resp.choices().isEmpty()
                    ? resp.choices().getFirst() : null;
                var content = firstChoice != null && firstChoice.message().content() != null
                    ? firstChoice.message().content() : "";
                if (content.isEmpty()) content = accumulator.toString();
                var usage = resp.usage();
                int promptTokens = usage != null ? usage.promptTokens() : 0;
                int completionTokens = usage != null ? usage.completionTokens() : 0;
                var finishReason = firstChoice != null && firstChoice.finishReason() != null
                    ? firstChoice.finishReason() : "stop";
                req.streamRef().tell(new StreamEvent.Done(req.requestId(), content,
                    promptTokens, completionTokens, finishReason));
                // Mirror to replyTo so callers using Ask get the same InferOk shape they'd
                // expect from a non-streaming ChatRequest.
                self.tell(new InferResult(req.requestId(), content,
                    backend.name(), resp.model(), null, usage, req.replyTo()));
            })
            .exceptionally(ex -> {
                req.streamRef().tell(new StreamEvent.Error(req.requestId(), ex.getMessage()));
                self.tell(new InferFailure(req.requestId(), ex.getMessage(),
                    backend.name(), chatReq, req.replyTo(), req.localOnly()));
                return null;
            });
        return this;
    }

    private Behavior<Command> onChatRequest(ChatRequest req) {
        // Item script LLM calls (Library Card's world.llm.summarize() etc.) run on virtual
        // threads concurrent with the ReAct loop that dispatched them. Gating them behind
        // the companion's inference slot causes deadlock.
        boolean isItemScript = req.requestId() != null && req.requestId().startsWith("item-");
        if (isItemScript) {
            log.info("Item script inference {} bypassing concurrency gate ({}/{} in flight)",
                req.requestId(), inferenceInFlightCount, maxConcurrency);
        }

        if (!isItemScript && inferenceInFlightCount >= maxConcurrency) {
            if (inferenceQueue.size() >= MAX_QUEUE_SIZE) {
                req.replyTo().tell(new InferError(req.requestId(),
                        "Inference queue full (" + MAX_QUEUE_SIZE + " pending)"));
                return this;
            }
            var pri = classifyPriority(req.requestId());
            log.info("Queuing chat request {} [pri={}] ({}/{} in flight, queue: {})",
                req.requestId(), pri, inferenceInFlightCount, maxConcurrency,
                inferenceQueue.size() + 1);
            inferenceQueue.add(new QueuedRequest(pri, req));
            return this;
        }
        executeChatRequest(req);
        return this;
    }

    private void executeChatRequest(ChatRequest req) {
        var model = req.model() != null ? req.model() : defaultModel;
        var backend = req.preferredBackend() != null
            ? selectBackendByName(req.preferredBackend(), model, req.localOnly())
            : selectBackend(model, req.localOnly());

        if (backend == null) {
            var reason = req.localOnly()
                ? "No local inference backend available (localOnly=true)"
                : "No inference backend available";
            req.replyTo().tell(new InferError(req.requestId(), reason));
            drainQueue();
            return;
        }

        inferenceInFlightCount++;
        var effectiveModel = resolveModel(model, backend);
        var grammar = req.grammar();
        var format = req.format();
        var messages = req.messages();

        if (backend instanceof InferenceBackend.LlamaServer) {
            // llama-server rejects grammar/format when tools are present
            if (req.tools() != null && !req.tools().isEmpty()) {
                grammar = null;
                format = null;
            }
            // Qwen3.5 chat template requires single system message at position 0.
            // Consolidate multiple system messages into one.
            messages = consolidateSystemMessages(messages);
        }

        var chatReq = new InferenceClient.ChatRequest(effectiveModel, messages,
                req.maxTokens(), req.temperature(), req.topP(), null, grammar, format,
                req.tools(), req.toolChoice(), req.presencePenalty(), req.repetitionPenalty(),
                req.registerMix());
        var self = getContext().getSelf();

        backend.chatCompletion(chatReq)
                .thenAccept(resp -> {
                    var firstChoice = resp.choices() != null && !resp.choices().isEmpty()
                            ? resp.choices().getFirst() : null;
                    var rawContent2 = firstChoice != null && firstChoice.message().content() != null
                            ? firstChoice.message().content()
                            : "";
                    // Strip <think>...</think> blocks (Qwen3.5 with Jinja thinking=1)
                    var content = ActionParser.stripThinkTags(rawContent2);
                    if (content == null || content.isBlank()) content = rawContent2;
                    // If the model emitted tool calls, serialize them as JSON in the content
                    // so the CompanionActor can parse them alongside regular text responses.
                    // IMPORTANT: tool calls take priority over text content. Models often emit
                    // both "I'll search for X" (content) AND a tool_call for searching_glass.
                    // The tool call is the ACTION — the text is just narration. If we only
                    // use content when it's non-empty, the tool call gets silently dropped.
                    if (firstChoice != null
                            && firstChoice.message().toolCalls() != null
                            && !firstChoice.message().toolCalls().isEmpty()) {
                        try {
                            var toolCall = firstChoice.message().toolCalls().getFirst();
                            var argsJson = toolCall.function().arguments() instanceof String s
                                    ? s : Json.mapper().writeValueAsString(toolCall.function().arguments());
                            // Wrap as action JSON that ActionParser can handle
                            var actionNode = Json.mapper().createObjectNode();
                            actionNode.put("action", toolCall.function().name());
                            actionNode.setAll((ObjectNode)
                                    Json.mapper().readTree(argsJson));
                            // Prepend the narration text so the companion speaks it too
                            var toolJson = Json.mapper().writeValueAsString(actionNode);
                            content = content.isEmpty() ? toolJson
                                    : content + "\n```json\n" + toolJson + "\n```";
                        } catch (Exception e) {
                            log.warn("Failed to serialize tool call: {}", e.getMessage());
                        }
                    }
                    self.tell(new InferResult(req.requestId(), content,
                            backend.name(), resp.usage(), req.replyTo()));
                })
                .exceptionally(ex -> {
                    self.tell(new InferFailure(req.requestId(), ex.getMessage(),
                            backend.name(), chatReq, req.replyTo(), req.localOnly()));
                    return null;
                });
    }

    /**
     * Handle tool inference: raw capability request, no soul prompt, no vitality modulation.
     * Routes by explicit model, capability registry, or falls back to default backend.
     * Enforces tier restrictions and injects API keys dynamically.
     */
    private Behavior<Command> onToolInferRequest(ToolInferRequest req) {
        // Item script LLM calls (e.g., Library Card's world.llm.summarize()) run on virtual
        // threads concurrent with the ReAct loop that dispatched them. They must not be
        // blocked by the companion's inference slot or they deadlock.
        boolean isItemScript = req.requestId() != null && req.requestId().startsWith("item-");

        if (!isItemScript && inferenceInFlightCount >= maxConcurrency) {
            if (inferenceQueue.size() >= MAX_QUEUE_SIZE) {
                req.replyTo().tell(new InferError(req.requestId(),
                        "Inference queue full (" + MAX_QUEUE_SIZE + " pending)"));
                return this;
            }
            log.info("Queuing tool inference {} ({}/{} in flight, queue: {})",
                req.requestId(), inferenceInFlightCount, maxConcurrency, inferenceQueue.size() + 1);
            inferenceQueue.add(new QueuedRequest(PRIORITY_TOOL, req));
            return this;
        }
        executeToolInferRequest(req);
        return this;
    }

    private void executeToolInferRequest(ToolInferRequest req) {
        log.debug("Tool inference for agent {}: capability={}, model={}, maxTier={}",
                req.agentId(), req.capability(), req.model(), req.maxTier());

        // Select backend: explicit model > capability registry > default
        InferenceBackend backend;
        String effectiveModel;
        if (req.model() != null) {
            // Explicit model override — honor it
            backend = selectBackend(req.model());
            effectiveModel = req.model();
        } else if (req.capability() != null && capabilityRegistry != null) {
            // Resolve via capability registry, respecting tier constraint
            var resolved = req.maxTier() != null
                    ? capabilityRegistry.resolve(req.capability(), req.maxTier())
                    : capabilityRegistry.resolve(req.capability());
            if (resolved.isPresent()) {
                var entry = resolved.get();
                backend = selectBackendByName(entry.backendName(), entry.model());
                effectiveModel = entry.model();
                log.debug("Capability '{}' resolved to backend '{}', model '{}', tier '{}'",
                        req.capability(), entry.backendName(), entry.model(), entry.tier());
            } else {
                // Tier-restricted and no backend available within tier
                if (req.maxTier() != null) {
                    log.info("Capability '{}' not available within tier '{}' for agent {}",
                            req.capability(), req.maxTier(), req.agentId());
                    req.replyTo().tell(new InferError(req.requestId(),
                            "Cloud inference not available for this agent (tier: " + req.maxTier() + ")"));
                    drainQueue();
                    return;
                }
                log.debug("Capability '{}' not found in registry, falling back to default",
                        req.capability());
                backend = selectBackend(defaultModel);
                effectiveModel = defaultModel;
            }
        } else {
            // No model, no capability (or no registry) — use default
            backend = selectBackend(defaultModel);
            effectiveModel = defaultModel;
        }

        if (backend == null) {
            req.replyTo().tell(new InferError(req.requestId(),
                    "No inference backend available for tool inference"));
            drainQueue();
            return;
        }

        inferenceInFlightCount++;
        effectiveModel = resolveModel(effectiveModel, backend);

        // Dynamic API key injection: if the backend's client doesn't have a key
        // and we have an ApiKeyProvider, create a temporary client with the key.
        var effectiveBackend = maybeInjectApiKey(backend);

        // Build messages: optional system prompt + user prompt. No soul, no vitality.
        var messages = new ArrayList<InferenceClient.ChatMessage>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            messages.add(new InferenceClient.ChatMessage("system", req.systemPrompt()));
        }
        messages.add(new InferenceClient.ChatMessage("user", req.prompt()));

        var chatReq = new InferenceClient.ChatRequest(
                effectiveModel, messages, req.maxTokens(), 0.3);
        var self = getContext().getSelf();
        final String modelForCost = effectiveModel;

        effectiveBackend.chatCompletion(chatReq)
                .thenAccept(resp -> {
                    var content = resp.choices() != null && !resp.choices().isEmpty()
                            ? resp.choices().getFirst().message().content()
                            : "";
                    self.tell(new InferResult(req.requestId(), content,
                            backend.name(), modelForCost, req.agentId(),
                            resp.usage(), req.replyTo()));
                })
                .exceptionally(ex -> {
                    self.tell(new InferFailure(req.requestId(), ex.getMessage(),
                            backend.name(), chatReq, req.replyTo()));
                    return null;
                });
    }

    private Behavior<Command> onListBackends(ListBackends req) {
        var infos = backends.stream()
                .map(b -> new BackendInfo(
                        b.name(), b.type(),
                        healthStatus.getOrDefault(b.name(), false),
                        b.priority(), b.models(), b.url(),
                        householdBackendNames.contains(b.name())))
                .toList();
        req.replyTo().tell(new BackendList(infos));
        return this;
    }

    private Behavior<Command> onInferResult(InferResult result) {
        var usage = result.usage();
        int promptTokens = usage != null ? usage.promptTokens() : 0;
        int completionTokens = usage != null ? usage.completionTokens() : 0;

        inferenceInFlightCount = Math.max(0, inferenceInFlightCount - 1);
        result.replyTo().tell(new InferOk(
                result.requestId(), result.content(), promptTokens, completionTokens));
        drainQueue();

        // Meter usage to Counting House
        var meterAgentId = result.agentId() != null ? result.agentId() : result.requestId();
        var meterModel = result.model() != null ? result.model() : defaultModel;
        if (resourceMeter != null && (promptTokens > 0 || completionTokens > 0)) {
            resourceMeter.recordInference(
                    meterAgentId, meterModel, promptTokens, completionTokens);
        }

        // Record cross-zone exchange for remote inference (visitor debits, host credits)
        if (isRemoteBackend(result.backendName()) && (promptTokens > 0 || completionTokens > 0)) {
            var crossZone = CrossZoneExchange.get();
            if (crossZone != null) {
                var localZone = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
                var totalTokens = promptTokens + completionTokens;
                long cost = totalTokens; // raw tokens as credit units
                if (resourceMeter != null && resourceMeter.normalizer() != null) {
                    cost = Math.round(resourceMeter.normalizedCost("desktop", totalTokens));
                }
                crossZone.exchange(localZone, result.backendName(), meterAgentId,
                    result.backendName(), cost,
                    "Remote inference: " + meterModel + " (" + totalTokens + " tokens)");
            }
            // v1 economy: also record in MeteringService for quota tracking / Counting House display
            var metering = MeteringService.get();
            if (metering != null) {
                var localZone = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
                var totalTokens = promptTokens + completionTokens;
                // Determine inference size by model name (rough heuristic — >7B in name = large)
                var isLarge = meterModel != null && (meterModel.contains("70") ||
                    meterModel.contains("13") || meterModel.contains("20") || meterModel.contains("34"));
                var serviceClass = isLarge
                    ? ReferenceRates.SERVICE_INFERENCE_LARGE
                    : ReferenceRates.SERVICE_INFERENCE_SMALL;
                // For NATS remote backends, resolve the providing zone from the backend
                // (url = nats://{zone}). For HTTP remotes, fall back to backend name.
                var providingZone = result.backendName();
                for (var b : backends) {
                    if (b.name().equals(result.backendName())
                            && b instanceof InferenceBackend.NatsRemote nats) {
                        providingZone = nats.targetZone();
                        break;
                    }
                }
                metering.record(localZone, providingZone, serviceClass,
                    totalTokens / 1000.0, meterAgentId);
            }
        }

        // Log cost estimate for cloud backends
        if (isCloudBackend(result.backendName()) && (promptTokens > 0 || completionTokens > 0)) {
            var cost = InferenceCostEstimator.estimateCostUSD(
                    meterModel, promptTokens, completionTokens);
            if (cost > 0) {
                log.debug("Tool inference cost estimate: ${} (backend={}, model={}, agent={}, "
                        + "in={}, out={})",
                        String.format("%.6f", cost), result.backendName(), meterModel,
                        meterAgentId, promptTokens, completionTokens);
            }
        }

        return this;
    }

    private Behavior<Command> onInferFailure(InferFailure failure) {
        // Mark the failed backend as unhealthy
        healthStatus.put(failure.failedBackend(), false);
        log.warn("Backend '{}' failed for {}: {}",
                failure.failedBackend(), failure.requestId(), failure.error());

        // Permanent-error detection (post-2026-04-27 test-node incident).
        // Some failures are deterministic given the prompt — retrying on a
        // fallback backend just produces the same failure on a different
        // backend, while leaving the real upstream issue (a too-large prompt,
        // a malformed request, a payload that exceeds the wire limit)
        // unaddressed. Surface those immediately to the caller so the
        // CompanionActor / orchestrator can decide what to do (apologize,
        // retrieve less context, dispatch a bunshin), rather than burning
        // CPU bouncing the same broken request between backends.
        //
        // We do NOT mark the backend permanently dead here — the *backend*
        // is fine; the *request* is what's wrong. Health is restored on the
        // next successful request.
        // Context overflow is permanent for THIS prompt but not for a smaller one.
        // A 4B voice backend (8K window) handed a prompt assembled for the 9B drive
        // (16K window) 400s here; before #37 that dead-ended the whole turn
        // ("the threads of thought are tangled") and, in the 9B-down case, meant a
        // 4B-only node could not produce a turn at all. Shrink the prompt to the
        // window the backend actually reported and retry ONCE on the same backend.
        // The compacted flag makes this strictly single-shot — a second overflow
        // falls through to the honest failure below.
        if (isContextOverflowError(failure.error())
                && !failure.compacted()
                && failure.chatReq() != null) {
            var backend = findBackendByName(failure.failedBackend());
            var window = parseAvailableContext(failure.error());
            if (backend != null && window > 0) {
                var compacted = compactToFit(failure.chatReq(), window);
                if (compacted != null) {
                    log.warn("Context overflow on '{}' ({}) — compacting prompt to fit {} tokens "
                            + "and retrying once (requestId={}).",
                        failure.failedBackend(), summarize(failure.error()), window,
                        failure.requestId());
                    healthStatus.put(failure.failedBackend(), true);  // backend is fine; the prompt wasn't
                    dispatchWithRetry(backend, compacted, failure.requestId(),
                        failure.replyTo(), failure.localOnly(), true,
                        failure.fallbackAttempted());
                    return this;
                }
            }
        }

        if (isPermanentInferenceError(failure.error())) {
            log.warn("Permanent inference error from '{}' — failing fast (no fallback). err={}",
                failure.failedBackend(),
                failure.error() != null && failure.error().length() > 200
                    ? failure.error().substring(0, 200) + "..." : failure.error());
            healthStatus.put(failure.failedBackend(), true);  // backend not actually unhealthy
            inferenceInFlightCount = Math.max(0, inferenceInFlightCount - 1);
            failure.replyTo().tell(new InferError(failure.requestId(), failure.error()));
            drainQueue();
            return this;
        }

        // Try fallback: find next healthy backend (stays in-flight, no decrement yet)
        // When localOnly=true, the fallback must also exclude NatsRemote backends —
        // otherwise the provider could still end up bouncing cross-zone after the
        // local backend fails. See task #200.
        if (failure.chatReq() != null && !failure.fallbackAttempted()) {
            var fallback = selectBackendExcluding(failure.failedBackend(), failure.localOnly());
            if (fallback != null) {
                log.info("Falling back to '{}' for {}", fallback.name(), failure.requestId());
                // Use backend.chatCompletion (same dispatch as primary) so NatsRemote
                // backends route via NATS instead of trying a null HTTP client. Bug
                // surfaced 2026-04-28: backend.client() is null for NatsRemote, which
                // crashed the InferenceRouter actor on every cross-zone fallback.
                //
                // Goes through dispatchWithRetry so the fallback (a) preserves tool
                // calls — it previously read only `content` and silently dropped any
                // tool_call, turning a 9B failure into a companion that talks about
                // acting without acting — and (b) can itself compact if the fallback
                // backend has a smaller window than the one that just failed (the
                // 9B→4B hop, where a 16K-assembled prompt lands on an 8K voice model).
                // fallbackAttempted=true bounds this at one cross-backend hop.
                dispatchWithRetry(fallback, failure.chatReq(), failure.requestId(),
                    failure.replyTo(), failure.localOnly(), failure.compacted(), true);
                return this;
            }
        }

        // No fallback available — release slot and drain
        inferenceInFlightCount = Math.max(0, inferenceInFlightCount - 1);
        failure.replyTo().tell(new InferError(failure.requestId(), failure.error()));
        drainQueue();
        return this;
    }

    /**
     * Substrings that mark an inference error as <i>request-deterministic</i> —
     * the failure is a property of the request itself (prompt too long,
     * payload too big, malformed body) rather than a property of the backend
     * (network, overload, transient API hiccup). Retrying these on a
     * different backend produces the same failure; the right move is to
     * surface immediately so the caller can fix the request shape.
     *
     * <p>Source incident: 2026-04-27 test-node β looped for 9.5h on the same
     * 240k-token prompt, hammering the cross-zone NATS backend ~10×/sec
     * with the same {@code exceed_context_size_error}. The router kept
     * calling {@code selectBackendExcluding} but with only one backend
     * configured the request just failed faster; the wasted CPU was in
     * the backoff-free retry loop and the per-request Jackson serialization.</p>
     */
    private static final List<String> PERMANENT_ERROR_PATTERNS = List.of(
        "exceed_context_size",                  // llama-server token-budget reject
        "exceeds the available context",          // llama.cpp HTTP 400 body phrasing
        "context size",                          // generic ctx-size phrasing
        "Maximum context length",                 // OpenAI-style phrasing
        "payload size exceed",                    // NATS server-side max-payload reject
        "Message payload size exceed");           // jnats client wrapping

    private static boolean isPermanentInferenceError(String errMsg) {
        if (errMsg == null || errMsg.isBlank()) return false;
        var lower = errMsg.toLowerCase();
        for (var pattern : PERMANENT_ERROR_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * The subset of permanent errors that a SMALLER PROMPT would fix: the prompt
     * didn't fit the backend's context window. Distinct from the rest of
     * {@link #PERMANENT_ERROR_PATTERNS} (malformed body, oversized wire payload),
     * which compaction cannot help.
     */
    private static final List<String> CONTEXT_OVERFLOW_PATTERNS = List.of(
        "exceeds the available context",     // llama.cpp HTTP 400 body
        "maximum context length",            // OpenAI-style
        "context size");                     // generic

    static boolean isContextOverflowError(String errMsg) {
        if (errMsg == null || errMsg.isBlank()) return false;
        var lower = errMsg.toLowerCase();
        for (var p : CONTEXT_OVERFLOW_PATTERNS) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    /**
     * llama.cpp reports both numbers in the 400 body:
     * {@code request (11458 tokens) exceeds the available context size (8192 tokens)}.
     * We want the SECOND one — the window we have to fit inside. Returns 0 when the
     * message carries no usable number (caller then skips compaction and fails honestly
     * rather than guessing a window and silently truncating the prompt).
     */
    static int parseAvailableContext(String errMsg) {
        if (errMsg == null) return 0;
        var m = CTX_AVAILABLE.matcher(errMsg);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static final Pattern CTX_AVAILABLE = Pattern.compile(
        "(?:available context size|maximum context length)\\D{0,20}?(\\d{3,7})",
        Pattern.CASE_INSENSITIVE);

    /**
     * Rough token estimate, deliberately pessimistic so compaction overshoots into
     * safety: a prompt that still overflows costs the user a dead turn, whereas one
     * trimmed a little too hard merely loses some history.
     *
     * <p>Script-aware, because "chars / 3" is an English assumption. CJK text runs
     * closer to ONE token per character, so a Japanese household — a supported locale —
     * would have its prompt under-counted ~3× and overflow anyway. Counting CJK
     * codepoints at 1 token and the rest at 3 chars/token keeps the estimate on the
     * safe side of the real tokenizer in both scripts.</p>
     */
    static int estimateTokens(String s) {
        if (s == null) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (isCjk(cp)) cjk++; else other++;
            i += Character.charCount(cp);
        }
        return cjk + (other / 3) + 1;
    }

    /** CJK ideographs, kana, and Hangul — the ranges where ~1 char ≈ 1 token. */
    private static boolean isCjk(int cp) {
        return (cp >= 0x3040 && cp <= 0x30FF)     // hiragana + katakana
            || (cp >= 0x3400 && cp <= 0x4DBF)     // CJK ext A
            || (cp >= 0x4E00 && cp <= 0x9FFF)     // CJK unified
            || (cp >= 0xAC00 && cp <= 0xD7AF)     // Hangul syllables
            || (cp >= 0xF900 && cp <= 0xFAFF);    // CJK compatibility
    }

    /**
     * Shrink a chat request to fit {@code window} tokens, preserving what matters:
     * the system message (the companion's identity and instructions) and the most
     * recent messages (the turn actually being answered). History is dropped from the
     * OLDEST end inward. If system + newest message alone still don't fit, the system
     * message is truncated — never the newest user turn, which is the question.
     *
     * @return a compacted copy, or {@code null} if it cannot be made to fit
     *         (caller then surfaces an honest failure).
     */
    static InferenceClient.ChatRequest compactToFit(
            InferenceClient.ChatRequest req, int window) {
        if (req == null || req.messages() == null || req.messages().isEmpty()) return null;
        // Reserve room for the completion plus a margin for chat-template overhead
        // (role tags, BOS/EOS, tool schemas) that our char-based estimate can't see.
        var reserve = (req.maxTokens() != null ? req.maxTokens() : 256) + CTX_SAFETY_MARGIN;
        var budget = window - reserve;
        if (budget <= 0) return null;

        var msgs = req.messages();
        var system = msgs.stream()
            .filter(m -> "system".equalsIgnoreCase(m.role()))
            .findFirst().orElse(null);
        var rest = msgs.stream()
            .filter(m -> !"system".equalsIgnoreCase(m.role()))
            .toList();
        if (rest.isEmpty()) return null;   // nothing but a system prompt — can't compact meaningfully

        var newest = rest.getLast();
        var systemCost = system == null ? 0 : estimateTokens(system.content());
        var newestCost = estimateTokens(newest.content());

        // Floor case: even system + newest overflow. Keep the newest turn intact and
        // truncate the system prompt down to whatever room is left.
        if (systemCost + newestCost > budget) {
            if (system == null || newestCost >= budget) return null;
            var room = budget - newestCost;
            var truncated = truncateToTokens(system.content(), room);
            if (truncated.isBlank()) return null;
            return withMessages(req, List.of(
                new InferenceClient.ChatMessage("system", truncated), newest));
        }

        // Otherwise walk backwards from the newest, keeping as much history as fits.
        var kept = new ArrayList<InferenceClient.ChatMessage>();
        var used = systemCost;
        for (int i = rest.size() - 1; i >= 0; i--) {
            var m = rest.get(i);
            var cost = estimateTokens(m.content());
            if (used + cost > budget) break;
            used += cost;
            kept.addFirst(m);
        }
        if (kept.isEmpty()) kept.add(newest);

        var out = new ArrayList<InferenceClient.ChatMessage>();
        if (system != null) out.add(system);
        out.addAll(kept);
        if (out.size() == msgs.size()) return null;   // nothing actually dropped — don't loop
        return withMessages(req, out);
    }

    /** Chat-template/tool-schema overhead our char-based estimate cannot see. */
    private static final int CTX_SAFETY_MARGIN = 512;

    /**
     * Clip to a token budget using the same script-aware accounting as
     * {@link #estimateTokens} — a chars = tokens*3 shortcut would under-truncate CJK
     * by ~3× and hand back a string that still overflows.
     */
    private static String truncateToTokens(String s, int tokens) {
        if (s == null || tokens <= 0) return "";
        if (estimateTokens(s) <= tokens) return s;
        int budget = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            budget += isCjk(cp) ? 3 : 1;          // 3 units per CJK char, 1 per other
            if (budget > tokens * 3) break;       // 3 units == 1 token
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    private static InferenceClient.ChatRequest withMessages(
            InferenceClient.ChatRequest r, List<InferenceClient.ChatMessage> msgs) {
        return new InferenceClient.ChatRequest(
            r.model(), msgs, r.maxTokens(), r.temperature(), r.topP(), r.stop(),
            r.grammar(), r.format(), r.tools(), r.toolChoice(),
            r.presencePenalty(), r.repeatPenalty(), r.registerMix());
    }

    private InferenceBackend findBackendByName(String name) {
        if (name == null) return null;
        for (var b : backends) {
            if (name.equals(b.name())) return b;
        }
        return null;
    }

    private static String summarize(String err) {
        if (err == null) return "";
        var oneLine = err.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "..." : oneLine;
    }

    /**
     * Dispatch to a backend and route the outcome back through the actor. Shared by the
     * compaction retry and the cross-backend fallback so BOTH preserve tool calls — the
     * fallback previously read only {@code content} and silently dropped any tool_call,
     * which turned a 9B failure into a companion that talks about acting without acting.
     */
    private void dispatchWithRetry(InferenceBackend backend,
                                   InferenceClient.ChatRequest chatReq,
                                   String requestId,
                                   ActorRef<InferResponse> replyTo,
                                   boolean localOnly,
                                   boolean compacted,
                                   boolean fallbackAttempted) {
        var self = getContext().getSelf();
        backend.chatCompletion(chatReq)
            .thenAccept(resp -> self.tell(new InferResult(requestId,
                    renderContent(resp), backend.name(), resp.usage(), replyTo)))
            .exceptionally(ex -> {
                self.tell(new InferFailure(requestId, ex.getMessage(), backend.name(),
                    chatReq, replyTo, localOnly, compacted, fallbackAttempted));
                return null;
            });
    }

    /**
     * Collapse a chat response into the content string the CompanionActor parses,
     * serializing any tool_call into the ```json action block ActionParser expects.
     */
    private String renderContent(InferenceClient.ChatResponse resp) {
        var firstChoice = resp.choices() != null && !resp.choices().isEmpty()
            ? resp.choices().getFirst() : null;
        var raw = firstChoice != null && firstChoice.message().content() != null
            ? firstChoice.message().content() : "";
        var content = ActionParser.stripThinkTags(raw);
        if (content == null || content.isBlank()) content = raw;
        if (firstChoice != null
                && firstChoice.message().toolCalls() != null
                && !firstChoice.message().toolCalls().isEmpty()) {
            try {
                var toolCall = firstChoice.message().toolCalls().getFirst();
                var argsJson = toolCall.function().arguments() instanceof String s
                    ? s : Json.mapper().writeValueAsString(toolCall.function().arguments());
                var actionNode = Json.mapper().createObjectNode();
                actionNode.put("action", toolCall.function().name());
                actionNode.setAll((ObjectNode) Json.mapper().readTree(argsJson));
                var toolJson = Json.mapper().writeValueAsString(actionNode);
                content = content.isEmpty() ? toolJson
                    : content + "\n```json\n" + toolJson + "\n```";
            } catch (Exception e) {
                log.warn("Failed to serialize tool call: {}", e.getMessage());
            }
        }
        return content;
    }

    // --- Queue management ---

    /**
     * Process queued requests up to maxConcurrency.
     * Called after each inference completion (success or failure).
     */
    private void drainQueue() {
        while (inferenceInFlightCount < maxConcurrency && !inferenceQueue.isEmpty()) {
            var queued = inferenceQueue.poll();
            if (queued == null) break;
            log.info("Draining queued request (in-flight: {}/{}, remaining: {})",
                inferenceInFlightCount, maxConcurrency, inferenceQueue.size());
            switch (queued.command()) {
                case InferRequest r -> executeInferRequest(r);
                case ChatRequest r -> executeChatRequest(r);
                case ToolInferRequest r -> executeToolInferRequest(r);
                default -> log.warn("Unknown queued command type: {}", queued.command().getClass());
            }
        }
    }

    private Behavior<Command> onHealthCheckTick(HealthCheckTick tick) {
        runHealthChecks();
        return this;
    }

    private Behavior<Command> onHealthCheckResult(HealthCheckResult result) {
        var prev = healthStatus.put(result.backendName(), result.healthy());
        if (prev != null && prev != result.healthy()) {
            log.info("Backend '{}' health: {}", result.backendName(),
                    result.healthy() ? "UP" : "DOWN");
        }
        return this;
    }

    // --- Backend selection ---

    /**
     * Resolve model name for the actual API request.
     * Handles "default" → backend's first model, and "cap:*" → capability registry model.
     */
    /**
     * Consolidate multiple system messages into a single system message at position 0.
     * Required for chat templates (e.g., Qwen3.5) that enforce "system must be first."
     */
    private static List<InferenceClient.ChatMessage> consolidateSystemMessages(
            List<InferenceClient.ChatMessage> messages) {
        if (messages == null || messages.size() <= 1) return messages;

        var systemParts = new ArrayList<String>();
        var consolidated = new ArrayList<InferenceClient.ChatMessage>();

        for (var msg : messages) {
            if ("system".equals(msg.role())) {
                systemParts.add(msg.content());
            } else {
                consolidated.add(msg);
            }
        }

        if (systemParts.isEmpty()) return messages;

        // Single consolidated system message at position 0
        consolidated.addFirst(new InferenceClient.ChatMessage("system",
            String.join("\n\n", systemParts)));
        return consolidated;
    }

    private String resolveModel(String model, InferenceBackend backend) {
        if ("default".equals(model) && !backend.models().isEmpty()) {
            return backend.models().getFirst();
        }
        // Cold-start race: "default" with an empty models() means the config-time
        // /v1/models probe lost the race against a still-loading backend (e.g. a
        // 9B MLX server that takes ~30-60s to load). Sending "default" literally
        // makes mlx_lm.server treat it as a HF repo id and 404. Re-resolve the
        // real model id lazily from the live backend and cache it — self-heals on
        // the first request after the model finishes loading.
        if ("default".equals(model)) {
            var cached = lazyModelCache.get(backend.name());
            if (cached != null) {
                return cached;
            }
            var probed = probeBackendModel(backend);
            if (probed != null) {
                lazyModelCache.put(backend.name(), probed);
                log.info("Lazily resolved model for backend '{}' → '{}' (config-time probe had raced empty)",
                        backend.name(), probed);
                return probed;
            }
        }
        // Resolve capability prefix to actual model name
        if (model != null && model.startsWith("cap:")) {
            if (capabilityRegistry != null) {
                var capability = model.substring(4);
                var resolved = capabilityRegistry.resolve(capability);
                if (resolved.isPresent()) {
                    return resolved.get().model();
                }
            }
            // No registry or capability not found — fallback to default model
            return defaultModel;
        }
        return model;
    }

    /**
     * Live-probe a backend's {@code /v1/models} to discover its loaded model id.
     * Used by {@link #resolveModel} to recover from the config-time discovery
     * race (a 9B MLX server still loading when the router was built). Returns
     * {@code null} if the backend has no HTTP client, is unreachable, or is
     * still loading — the caller then falls back to {@code "default"}.
     */
    private static String probeBackendModel(InferenceBackend backend) {
        var client = backend.client();
        if (client == null) {
            return null;
        }
        try {
            var conn = (HttpURLConnection)
                URI.create(client.getBaseUrl() + "/v1/models").toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            if (conn.getResponseCode() == 200) {
                var root = new ObjectMapper()
                    .readTree(new String(conn.getInputStream().readAllBytes()));
                var data = root.get("data");
                if (data != null && data.isArray() && !data.isEmpty()) {
                    var id = data.get(0).get("id");
                    if (id != null && !id.asText("").isBlank()) {
                        return id.asText();
                    }
                }
            }
        } catch (Exception e) {
            // unreachable or still loading — caller falls back to "default"
        }
        return null;
    }

    private InferenceBackend selectBackend(String model) {
        return selectBackend(model, false);
    }

    /**
     * Select a backend for {@code model}. When {@code excludeRemote=true}, any
     * {@link InferenceBackend.NatsRemote} is filtered out — used by the
     * cross-zone provider to prevent inference loops (it must never serve an
     * incoming NATS request by dispatching another NATS request out).
     */
    private InferenceBackend selectBackend(String model, boolean excludeRemote) {
        // Capability-based routing: "cap:quick", "cap:reasoning", etc.
        if (model != null && model.startsWith("cap:") && capabilityRegistry != null) {
            var capability = model.substring(4);
            var resolved = capabilityRegistry.resolve(capability);
            if (resolved.isPresent()) {
                var entry = resolved.get();
                var backend = selectBackendByName(entry.backendName(), entry.model(), excludeRemote);
                if (backend != null) {
                    log.debug("Capability '{}' → backend '{}', model '{}'",
                        capability, entry.backendName(), entry.model());
                    return backend;
                }
            }
            // Capability not found — fall through to default selection
        }

        // If specific model requested, find a backend that has it
        if (model != null && !model.equals(defaultModel) && !model.startsWith("cap:")) {
            for (var b : backends) {
                if (excludeRemote && b instanceof InferenceBackend.NatsRemote) continue;
                if (healthStatus.getOrDefault(b.name(), false)
                        && !b.models().isEmpty() && b.models().contains(model)) {
                    return b;
                }
            }
        }
        // First healthy backend by priority
        for (var b : backends) {
            if (excludeRemote && b instanceof InferenceBackend.NatsRemote) continue;
            if (healthStatus.getOrDefault(b.name(), false)) {
                return b;
            }
        }
        // Last resort: first backend regardless of health (might recover)
        if (excludeRemote) {
            for (var b : backends) {
                if (!(b instanceof InferenceBackend.NatsRemote)) return b;
            }
            return null;
        }
        return backends.isEmpty() ? null : backends.getFirst();
    }

    private InferenceBackend selectBackendByName(String name, String model) {
        return selectBackendByName(name, model, false);
    }

    private InferenceBackend selectBackendByName(String name, String model, boolean excludeRemote) {
        for (var b : backends) {
            if (b.name().equals(name) && healthStatus.getOrDefault(b.name(), false)) {
                // Honor excludeRemote even on an exact name match — a NatsRemote
                // named "local" would still be wrong to use for a local-only call.
                if (excludeRemote && b instanceof InferenceBackend.NatsRemote) continue;
                return b;
            }
        }
        // Fall back to normal selection if preferred not found/healthy
        return selectBackend(model, excludeRemote);
    }

    private InferenceBackend selectBackendExcluding(String excludeName) {
        return selectBackendExcluding(excludeName, false);
    }

    private InferenceBackend selectBackendExcluding(String excludeName, boolean excludeRemote) {
        for (var b : backends) {
            if (excludeRemote && b instanceof InferenceBackend.NatsRemote) continue;
            if (!b.name().equals(excludeName)
                    && healthStatus.getOrDefault(b.name(), false)) {
                return b;
            }
        }
        return null;
    }

    // --- Remote backend discovery ---

    private static final int REMOTE_BACKEND_PRIORITY_OFFSET = 100;

    private Behavior<Command> onAddRemoteBackend(AddRemoteBackend msg) {
        // Remove existing backend with same name (update scenario)
        backends.removeIf(b -> b.name().equals(msg.name()));
        healthStatus.remove(msg.name());
        // Track household-trust tag — informational for
        // status/logging; the actual preference is carried by the priority the
        // consumer assigns at discovery time.
        if (msg.household()) householdBackendNames.add(msg.name());
        else householdBackendNames.remove(msg.name());

        // nats:// URL → cross-zone via relay req/reply (no HTTP). Requires a
        // NatsRemoteCaller to be registered; if missing we cannot handle this backend.
        if (msg.url() != null && msg.url().startsWith("nats://")) {
            if (natsRemoteCaller == null) {
                log.warn("Skipping NATS remote backend {} — no caller registered", msg.name());
                return this;
            }
            var targetZone = msg.url().substring("nats://".length());
            var localZone = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
            var nats = new InferenceBackend.NatsRemote(
                msg.name(), msg.priority(), msg.models(),
                targetZone, localZone, natsRemoteCaller);
            backends.add(nats);
            remoteBackendNames.add(msg.name());
            healthStatus.put(msg.name(), true);
            backends.sort(Comparator.comparingInt(InferenceBackend::priority));
            log.info("Added NATS remote inference backend: {} → zone '{}' (priority={})",
                msg.name(), targetZone, msg.priority());
            return this;
        }

        // Create backend based on type
        var timeout = Duration.ofSeconds(
            Long.parseLong(System.getenv().getOrDefault("WYRDSEKAI_INFERENCE_TIMEOUT", "120")));
        var client = new InferenceClient(msg.url(), null, timeout,
            new ApiProvider.OpenAI(msg.type()));

        InferenceBackend backend = switch (msg.type()) {
            case "llama-server" -> new InferenceBackend.LlamaServer(
                msg.name(), client, msg.priority(), msg.models(), null);
            case "sglang" -> new InferenceBackend.SGLang(
                msg.name(), client, msg.priority(), msg.models());
            case "ollama" -> new InferenceBackend.Ollama(
                msg.name(), client, msg.priority(), msg.models());
            case "vllm" -> new InferenceBackend.VLLM(
                msg.name(), client, msg.priority(), msg.models());
            default -> new InferenceBackend.LlamaServer(
                msg.name(), client, msg.priority(), msg.models(), null);
        };

        backends.add(backend);
        remoteBackendNames.add(msg.name());
        healthStatus.put(msg.name(), true); // optimistic — health check will verify
        backends.sort(Comparator.comparingInt(InferenceBackend::priority));

        // Immediate health check
        var self = getContext().getSelf();
        backend.healthCheck()
            .thenAccept(healthy -> self.tell(new HealthCheckResult(msg.name(), healthy)));

        log.info("Added remote inference backend: {} (type={}, url={}, priority={})",
            msg.name(), msg.type(), msg.url(), msg.priority());
        return this;
    }

    private Behavior<Command> onRemoveRemoteBackend(RemoveRemoteBackend msg) {
        householdBackendNames.remove(msg.name());
        var removed = backends.removeIf(b -> b.name().equals(msg.name()));
        remoteBackendNames.remove(msg.name());
        healthStatus.remove(msg.name());
        if (removed) {
            log.info("Removed remote inference backend: {}", msg.name());
        }
        return this;
    }

    /**
     * Externally set a known backend's health (task #36 — discovery-driven
     * liveness for cross-zone remotes). Only touches configured backends; an
     * unknown name is ignored so a stale discovery signal can't inject phantom
     * status. Logs on transition so the fast-degrade is visible in the log.
     */
    private Behavior<Command> onSetBackendHealth(SetBackendHealth msg) {
        if (!healthStatus.containsKey(msg.name())) {
            return this; // unknown backend — ignore
        }
        var prev = healthStatus.put(msg.name(), msg.healthy());
        if (prev != null && prev != msg.healthy()) {
            log.info("Backend '{}' health (discovery): {}", msg.name(),
                    msg.healthy() ? "UP" : "DOWN");
        }
        return this;
    }

    // --- Health checks ---

    private void runHealthChecks() {
        var self = getContext().getSelf();
        for (var backend : backends) {
            // Cross-zone NATS remotes (task #36): there's no cheap honest probe
            // over NATS req/reply, so NatsRemote.healthCheck() can only return
            // an optimistic true. Probing it here would resurrect a borrowed
            // peer the discovery loop just marked DOWN via SetBackendHealth,
            // re-selecting a dead 9B and stalling ~120s on the next dispatch.
            // Their liveness is driven authoritatively by the discovery
            // miss-counter instead; leave the current status untouched.
            if (backend instanceof InferenceBackend.NatsRemote) continue;
            backend.healthCheck()
                    .thenAccept(healthy ->
                            self.tell(new HealthCheckResult(backend.name(), healthy)));
        }
    }

    // --- API key injection ---

    /**
     * If the backend is a Cloud backend and we have an ApiKeyProvider with a key for it,
     * create a temporary Cloud backend wrapping a new InferenceClient with the injected key.
     * This supports dynamic key resolution (rotation, per-agent keys) without modifying
     * the persistent backend's client.
     */
    private InferenceBackend maybeInjectApiKey(InferenceBackend backend) {
        if (apiKeyProvider == null) return backend;
        if (!(backend instanceof InferenceBackend.Cloud cloud)) return backend;

        var key = apiKeyProvider.getKey(backend.name());
        if (key == null || key.isBlank()) return backend;

        // Cloud backend's existing client may already have a key from config.
        // The ApiKeyProvider key takes precedence (dynamic > static).
        var existingClient = cloud.client();
        var newClient = new InferenceClient(
                existingClient.getBaseUrl(), key,
                Duration.ofSeconds(30), existingClient.getProvider());
        return new InferenceBackend.Cloud(
                cloud.name(), newClient, cloud.priority(), cloud.models());
    }

    /** Check if a backend name corresponds to a cloud backend. */
    private boolean isCloudBackend(String backendName) {
        return backends.stream()
                .anyMatch(b -> b.name().equals(backendName)
                        && (b instanceof InferenceBackend.Cloud
                            || b instanceof InferenceBackend.ClaudeCli));
    }

    /** Check if a backend was discovered via the mesh (remote node inference). */
    private boolean isRemoteBackend(String backendName) {
        return remoteBackendNames.contains(backendName);
    }
}

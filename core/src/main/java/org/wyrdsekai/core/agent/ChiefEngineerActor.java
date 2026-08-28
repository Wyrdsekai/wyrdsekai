package org.wyrdsekai.core.agent;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.RecipientRef;
import org.apache.pekko.actor.typed.javadsl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.persistence.WorldDnaService;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Chief Engineer agent that lives in The Boiler Room.
 * Monitors system health via metric suppliers, responds to status queries,
 * and runs periodic health checks to detect anomalies.
 *
 * State machine: IDLE → THINKING → IDLE (same as CompanionActor)
 * - On Said with status keywords → debounce → assemble prompt → InferenceRouter
 * - On EntityEntered (player) → greet with brief status
 * - On HealthCheckTick (30s) → read metrics, alert if anomaly detected
 */
public class ChiefEngineerActor extends AbstractBehavior<ChiefEngineerActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(ChiefEngineerActor.class);

    // --- Protocol ---

    public sealed interface Command {}

    private record RoomEventReceived(RoomNotification notification) implements Command {}
    private record RoomResponseReceived(RoomResponse response) implements Command {}
    private record InferenceResponseReceived(
        InferenceRouter.InferResponse response) implements Command {}
    private record ProcessEvents() implements Command {}
    private record GreetPlayer(String playerName) implements Command {}
    private record RefreshRoomState() implements Command {}
    private record HealthCheckTick() implements Command {}
    private record VitalityTick() implements Command {}

    // --- Configuration ---

    private static final Duration DEBOUNCE_DELAY = Duration.ofMillis(1000);
    private static final Duration GREET_DELAY = Duration.ofSeconds(1);
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration HEALTH_CHECK_INTERVAL = Duration.ofSeconds(30);
    private static final Duration VITALITY_TICK_INTERVAL = Duration.ofSeconds(1);
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(30);
    private static final String DEBOUNCE_TIMER_KEY = "debounce";
    private static final String REFRESH_TIMER_KEY = "refresh";
    private static final String HEALTH_CHECK_TIMER_KEY = "health-check";
    private static final String VITALITY_TICK_KEY = "vitality-tick";

    /** Keywords that trigger an LLM-assisted response. */
    private static final Set<String> STATUS_KEYWORDS = Set.of(
        "status", "metrics", "health", "pressure", "check", "report",
        "inference", "backend", "llm", "topology", "network", "cluster",
        "federation", "zones", "node", "engine", "boiler"
    );

    // --- State ---

    private enum State { IDLE, THINKING }

    private final AgentProfile profile;
    private final RecipientRef<RoomCommand> roomRef;
    private final String roomId;
    private final ActorRef<InferenceRouter.Command> inferenceRouter;
    private final WorldDnaService worldDnaService;  // nullable

    private final Supplier<String> systemMetricsSupplier;    // nullable
    private final Supplier<String> topologySupplier;         // nullable
    private final Supplier<String> inferenceStatusSupplier;  // nullable
    private final Supplier<String> economySupplier;          // nullable

    private final ActorRef<RoomNotification> roomNotificationAdapter;
    private final ActorRef<RoomResponse> roomResponseAdapter;
    private final ActorRef<InferenceRouter.InferResponse> inferenceResponseAdapter;
    private final TimerScheduler<Command> timers;

    private State state = State.IDLE;
    private RoomSnapshot currentSnapshot;
    private final List<WorldEvent.Said> conversationHistory = new ArrayList<>();
    private WorldEvent.Said pendingTrigger;
    // Arrival-order queue, not a single slot — two messages landing in one busy
    // window must both survive (companion cpB2 loss, 2026-08-23). Bounded; overflow
    // drops the oldest.
    private final Deque<WorldEvent.Said> deferredTriggers = new ArrayDeque<>();
    private Instant lastFailure = Instant.MIN;
    private String locale = "en";
    private VitalityState vitality = VitalityState.initial()
        .withFocus(0.7)
        .withRapport(0.1)
        .withAlignment(0.5);

    private ChiefEngineerActor(ActorContext<Command> context, TimerScheduler<Command> timers,
                                AgentProfile profile, RecipientRef<RoomCommand> roomRef,
                                String roomId,
                                ActorRef<InferenceRouter.Command> inferenceRouter,
                                WorldDnaService worldDnaService,
                                Supplier<String> systemMetrics,
                                Supplier<String> topology,
                                Supplier<String> inferenceStatus,
                                Supplier<String> economy) {
        super(context);
        this.timers = timers;
        this.profile = profile;
        this.roomRef = roomRef;
        this.roomId = roomId;
        this.inferenceRouter = inferenceRouter;
        this.worldDnaService = worldDnaService;
        this.systemMetricsSupplier = systemMetrics;
        this.topologySupplier = topology;
        this.inferenceStatusSupplier = inferenceStatus;
        this.economySupplier = economy;

        // Message adapters
        this.roomNotificationAdapter = context.messageAdapter(
            RoomNotification.class, RoomEventReceived::new);
        this.roomResponseAdapter = context.messageAdapter(
            RoomResponse.class, RoomResponseReceived::new);
        this.inferenceResponseAdapter = context.messageAdapter(
            InferenceRouter.InferResponse.class, InferenceResponseReceived::new);

        // Subscribe to room notifications
        roomRef.tell(new RoomCommand.Subscribe(roomNotificationAdapter,
            VisibilityLevel.PRIVILEGED));

        // Enter the room
        roomRef.tell(new RoomCommand.EnterRoom(
            profile.entityId(), profile.name(), profile.entityType(),
            "materialization", roomResponseAdapter));

        // Get initial room snapshot
        roomRef.tell(new RoomCommand.LookRoom(profile.entityId(), roomResponseAdapter));

        // Start timers
        timers.startTimerWithFixedDelay(REFRESH_TIMER_KEY, new RefreshRoomState(),
            REFRESH_INTERVAL);
        timers.startTimerWithFixedDelay(HEALTH_CHECK_TIMER_KEY, new HealthCheckTick(),
            HEALTH_CHECK_INTERVAL);
        timers.startTimerWithFixedDelay(VITALITY_TICK_KEY, new VitalityTick(),
            VITALITY_TICK_INTERVAL);

        log.info("Chief Engineer '{}' ({}) spawned in room {}",
            profile.name(), profile.entityId(), roomId);
    }

    public static Behavior<Command> create(AgentProfile profile,
                                            RecipientRef<RoomCommand> roomRef,
                                            String roomId,
                                            ActorRef<InferenceRouter.Command> inferenceRouter,
                                            WorldDnaService worldDnaService,
                                            Supplier<String> systemMetrics,
                                            Supplier<String> topology,
                                            Supplier<String> inferenceStatus,
                                            Supplier<String> economy) {
        return Behaviors.setup(ctx ->
            Behaviors.withTimers(timers ->
                new ChiefEngineerActor(ctx, timers, profile, roomRef, roomId,
                    inferenceRouter, worldDnaService,
                    systemMetrics, topology, inferenceStatus, economy)));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(RoomEventReceived.class, this::onRoomEvent)
            .onMessage(RoomResponseReceived.class, this::onRoomResponse)
            .onMessage(InferenceResponseReceived.class, this::onInferenceResponse)
            .onMessage(ProcessEvents.class, this::onProcessEvents)
            .onMessage(GreetPlayer.class, this::onGreetPlayer)
            .onMessage(RefreshRoomState.class, this::onRefreshRoomState)
            .onMessage(HealthCheckTick.class, this::onHealthCheck)
            .onMessage(VitalityTick.class, this::onVitalityTick)
            .build();
    }

    // --- Event handlers ---

    private Behavior<Command> onRoomEvent(RoomEventReceived msg) {
        var event = msg.notification().event();

        switch (event) {
            case WorldEvent.Said said -> {
                // Ignore own speech, narrator, and system messages
                if (said.entityId().equals(profile.entityId())
                        || "narrator".equals(said.entityId())
                        || "system".equals(said.entityId())) break;

                // Ignore other agents' speech to prevent feedback loops
                if (currentSnapshot != null && currentSnapshot.entities().stream()
                        .anyMatch(e -> e.id().equals(said.entityId()) && "agent".equals(e.type()))) {
                    break;
                }

                // Adopt the speaker's locale
                if (said.locale() != null && !said.locale().isEmpty()) {
                    this.locale = said.locale();
                }

                // Only respond to status-related keywords
                if (!containsStatusKeyword(said.text())) break;

                addToHistory(said);

                vitality = vitality
                    .withRapport(vitality.rapport() + 0.03)
                    .withFocus(vitality.focus() + 0.05)
                    .withAlignment(vitality.alignment() + 0.02);

                if (state == State.IDLE) {
                    var modulation = VitalityModulation.compute(vitality, profile);
                    pendingTrigger = said;
                    timers.startSingleTimer(DEBOUNCE_TIMER_KEY,
                        new ProcessEvents(), modulation.debounceDelay());
                } else {
                    if (deferredTriggers.size() >= 16) deferredTriggers.pollFirst();
                    deferredTriggers.addLast(said);
                }
            }

            case WorldEvent.EntityEntered entered -> {
                if ("player".equals(entered.entityType())
                        && !entered.entityId().equals(profile.entityId())) {
                    timers.startSingleTimer(
                        "greet-" + entered.entityId(),
                        new GreetPlayer(entered.entityName()),
                        GREET_DELAY);
                }
            }

            default -> {}
        }

        return this;
    }

    private Behavior<Command> onRoomResponse(RoomResponseReceived msg) {
        switch (msg.response()) {
            case RoomResponse.Ok ok -> currentSnapshot = ok.snapshot();
            case RoomResponse.ObjectTakenOk taken -> currentSnapshot = taken.snapshot();
            case RoomResponse.Rejected rejected ->
                log.warn("Room rejected engineer command: {} — {}",
                    rejected.code(), rejected.reason());
            case RoomResponse.HintAction _ -> {}
            case RoomResponse.Narrated _ -> {} // Narration-only ack — no snapshot to apply
            case RoomResponse.HookRan _ -> {} // Script-hook narration — no snapshot to apply
            case RoomResponse.ToolDefinitions _ -> {} // Tool-def query reply — no snapshot to apply
        }
        return this;
    }

    private Behavior<Command> onProcessEvents(ProcessEvents msg) {
        if (state != State.IDLE || pendingTrigger == null) return this;

        if (Duration.between(lastFailure, Instant.now()).compareTo(FAILURE_COOLDOWN) < 0) {
            pendingTrigger = null;
            return this;
        }

        state = State.THINKING;

        var metricsContext = buildMetricsContext();
        var dnaPatterns = queryDnaPatterns();
        // F15: tag the prompt as cap:full so a config redirect can't silently
        // route a 5K-token Boiler-Room sandwich to the 4K voice backend.
        var prompt = PromptAssembler.assembleForFull(
            profile, currentSnapshot, conversationHistory, pendingTrigger,
            vitality, dnaPatterns, metricsContext);

        vitality = vitality
            .withEnergy(vitality.energy() - 0.004)
            .withContextBudget(vitality.contextBudget() - 0.05)
            .withMomentum(vitality.momentum() + 0.10);

        var modulation = VitalityModulation.compute(vitality, profile);
        var requestId = UUID.randomUUID().toString();
        inferenceRouter.tell(InferenceRouter.ChatRequest.fromPrompt(
            requestId, prompt,
            modulation.maxResponseTokens(), modulation.temperature(),
            inferenceResponseAdapter));

        log.debug("Chief Engineer thinking (trigger: {} said '{}')",
            pendingTrigger.entityName(), truncate(pendingTrigger.text(), 50));

        return this;
    }

    private Behavior<Command> onGreetPlayer(GreetPlayer msg) {
        if (state != State.IDLE) return this;
        if (Duration.between(lastFailure, Instant.now()).compareTo(FAILURE_COOLDOWN) < 0) {
            return this;
        }

        state = State.THINKING;

        vitality = vitality
            .withRapport(vitality.rapport() + 0.05)
            .withMomentum(vitality.momentum() + 0.05);

        var syntheticEvent = new WorldEvent.Said(
            roomId, Instant.now(), "system", msg.playerName(),
            "[" + msg.playerName() + " enters the Boiler Room]");
        pendingTrigger = syntheticEvent;

        var metricsContext = buildMetricsContext();
        var dnaPatterns = queryDnaPatterns();
        // F15: tagged cap:full (greeting flow needs the full sandwich).
        var prompt = PromptAssembler.assembleForFull(
            profile, currentSnapshot, conversationHistory, syntheticEvent,
            vitality, dnaPatterns, metricsContext);

        var modulation = VitalityModulation.compute(vitality, profile);
        var requestId = UUID.randomUUID().toString();
        inferenceRouter.tell(InferenceRouter.ChatRequest.fromPrompt(
            requestId, prompt,
            modulation.maxResponseTokens(), modulation.temperature(),
            inferenceResponseAdapter));

        log.debug("Chief Engineer greeting player '{}'", msg.playerName());
        return this;
    }

    private Behavior<Command> onInferenceResponse(InferenceResponseReceived msg) {
        state = State.IDLE;
        var wasTrigger = pendingTrigger;
        pendingTrigger = null;

        switch (msg.response()) {
            case InferenceRouter.InferOk ok -> {
                var content = ok.content();
                log.debug("Chief Engineer inference complete ({} prompt, {} completion tokens)",
                    ok.promptTokens(), ok.completionTokens());

                vitality = vitality
                    .withConfidence(vitality.confidence() + 0.05)
                    .withErrorPressure(vitality.errorPressure() - 0.03);

                var parseResult = ActionParser.parseAll(content);
                var prose = parseResult.hasAction() || parseResult.hasHints()
                    ? ActionParser.extractProse(content)
                    : content;

                if (prose != null && !prose.isBlank()) {
                    if (parseResult.hasHints()) {
                        speakWithHints(prose, parseResult.hints());
                    } else {
                        speak(prose);
                    }
                } else if (parseResult.hasHints()) {
                    updateRoomHints(parseResult.hints());
                }

                // Record operational pattern
                if (worldDnaService != null && wasTrigger != null) {
                    try {
                        worldDnaService.record("operational_report",
                            "{\"trigger\":\"" + truncate(wasTrigger.text(), 100) + "\"}",
                            roomId, profile.entityId(), "foundation");
                    } catch (Exception e) {
                        log.debug("DNA record failed: {}", e.getMessage());
                    }
                }
            }

            case InferenceRouter.InferError error -> {
                lastFailure = Instant.now();
                log.warn("Inference failed for Chief Engineer: {}", error.error());

                vitality = vitality
                    .withErrorPressure(vitality.errorPressure() + 0.15)
                    .withConfidence(vitality.confidence() - 0.10)
                    .withEnergy(vitality.energy() - 0.0025);

                if (wasTrigger != null && !"system".equals(wasTrigger.entityId())) {
                    var catalog = ScriptMessageCatalog.forLang(locale);
                    speak(catalog.get("agent.engineer.inference_fail"));
                }
            }
        }

        if (!deferredTriggers.isEmpty() && pendingTrigger == null) {
            pendingTrigger = deferredTriggers.pollFirst();
            var modulation = VitalityModulation.compute(vitality, profile);
            timers.startSingleTimer(DEBOUNCE_TIMER_KEY,
                new ProcessEvents(), modulation.debounceDelay());
        }

        return this;
    }

    private Behavior<Command> onRefreshRoomState(RefreshRoomState msg) {
        roomRef.tell(new RoomCommand.LookRoom(profile.entityId(), roomResponseAdapter));
        return this;
    }

    /**
     * Periodic health check — reads metrics and alerts on anomalies.
     * No LLM call — just simple threshold checks on raw metric strings.
     */
    private Behavior<Command> onHealthCheck(HealthCheckTick msg) {
        var metrics = readMetric(systemMetricsSupplier);
        if (metrics == null) return this;

        // Simple anomaly detection via keyword scanning of formatted metrics
        var anomalies = new ArrayList<String>();
        var lower = metrics.toLowerCase();

        if (lower.contains("heap") && containsHighPercentage(lower, "heap")) {
            anomalies.add("heap pressure elevated");
        }
        if (lower.contains("unhealthy") || lower.contains("unavailable")) {
            anomalies.add("component health degraded");
        }

        if (!anomalies.isEmpty() && state == State.IDLE) {
            var catalog = ScriptMessageCatalog.forLang(locale);
            speak(catalog.get("agent.engineer.anomaly_alert", String.join(", ", anomalies)));

            vitality = vitality
                .withFocus(vitality.focus() + 0.05)
                .withErrorPressure(vitality.errorPressure() + 0.05);
        }

        return this;
    }

    private Behavior<Command> onVitalityTick(VitalityTick msg) {
        vitality = vitality.tick();
        // Chief Engineer: focus decays slower, energy recovers faster (day-scale
        // recalibrated 2026-07-18 with TICK_ENERGY_RATE — stays slightly net-positive
        // at idle so the system agent never tires from merely existing)
        vitality = vitality
            .withFocus(Math.min(1.0, vitality.focus() + 0.001))   // net -0.001 vs -0.002
            .withEnergy(Math.min(1.0, vitality.energy() + 0.00015));
        return this;
    }

    // --- Actions ---

    private void speak(String text) {
        roomRef.tell(new RoomCommand.SayInRoom(
            profile.entityId(), profile.name(), text, roomResponseAdapter));
        addToHistory(new WorldEvent.Said(
            roomId, Instant.now(), profile.entityId(), profile.name(), text));
    }

    private void speakWithHints(String text, List<Hint> hints) {
        speak(text);
        if (hints != null && !hints.isEmpty()) {
            updateRoomHints(hints);
        }
    }

    private void updateRoomHints(List<Hint> hints) {
        roomRef.tell(new RoomCommand.UpdateHints(hints, roomResponseAdapter));
    }

    // --- Metrics ---

    /**
     * Build a metrics context string for inclusion in the LLM prompt.
     * Appended as additional system context after the room context.
     */
    String buildMetricsContext() {
        var sb = new StringBuilder();
        sb.append("[Current system metrics]\n");

        var system = readMetric(systemMetricsSupplier);
        if (system != null) sb.append("System: ").append(system).append("\n");

        var topology = readMetric(topologySupplier);
        if (topology != null) sb.append("Topology: ").append(topology).append("\n");

        var inference = readMetric(inferenceStatusSupplier);
        if (inference != null) sb.append("Inference: ").append(inference).append("\n");

        var economy = readMetric(economySupplier);
        if (economy != null) sb.append("Economy: ").append(economy).append("\n");

        return sb.toString();
    }

    private static String readMetric(Supplier<String> supplier) {
        if (supplier == null) return null;
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    // --- Utilities ---

    private boolean containsStatusKeyword(String text) {
        if (text == null) return false;
        var lower = text.toLowerCase();
        return STATUS_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private boolean containsHighPercentage(String text, String context) {
        // Very simple: look for numbers > 80 near the context word
        var idx = text.indexOf(context);
        if (idx < 0) return false;
        var region = text.substring(Math.max(0, idx - 30), Math.min(text.length(), idx + 50));
        return region.matches(".*\\b(8[0-9]|9[0-9]|100)\\s*%.*");
    }

    private List<WorldDnaService.DnaPattern> queryDnaPatterns() {
        if (worldDnaService == null) return List.of();
        try {
            return worldDnaService.queryTopPatterns("operational_report", "foundation", 3);
        } catch (Exception e) {
            log.debug("World DNA query failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void addToHistory(WorldEvent.Said event) {
        conversationHistory.add(event);
        int maxHistory = VitalityModulation.compute(vitality, profile)
            .conversationHistorySize();
        while (conversationHistory.size() > maxHistory) {
            conversationHistory.removeFirst();
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}

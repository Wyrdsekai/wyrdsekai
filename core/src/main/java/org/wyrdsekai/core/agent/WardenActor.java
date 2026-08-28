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
import org.wyrdsekai.core.library.OutputSanitizer;
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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Warden agent that lives in The Ward Room.
 * Monitors for security threats via SecurityPatternManager/OutputSanitizer
 * and uses LLM for behavioral anomaly assessment.
 *
 * Key behaviors:
 * - Runs OutputSanitizer on ALL speech events to detect injection patterns
 * - Uses CircuitBreaker to prevent autoimmune response (§21.8)
 * - Responds to all speech in its room (security queries)
 * - Periodic patrol: adjusts circuit breaker authority
 *
 * State machine: IDLE → THINKING → IDLE
 */
public class WardenActor extends AbstractBehavior<WardenActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(WardenActor.class);

    // --- Protocol ---

    public sealed interface Command {}

    private record RoomEventReceived(RoomNotification notification) implements Command {}
    private record RoomResponseReceived(RoomResponse response) implements Command {}
    private record InferenceResponseReceived(
        InferenceRouter.InferResponse response) implements Command {}
    private record ProcessEvents() implements Command {}
    private record GreetPlayer(String playerName) implements Command {}
    private record RefreshRoomState() implements Command {}
    private record PatrolTick() implements Command {}
    private record VitalityTick() implements Command {}

    // --- Configuration ---

    private static final Duration DEBOUNCE_DELAY = Duration.ofMillis(300);
    private static final Duration GREET_DELAY = Duration.ofSeconds(1);
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration PATROL_INTERVAL = Duration.ofSeconds(60);
    private static final Duration VITALITY_TICK_INTERVAL = Duration.ofSeconds(1);
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(30);
    private static final String DEBOUNCE_TIMER_KEY = "debounce";
    private static final String REFRESH_TIMER_KEY = "refresh";
    private static final String PATROL_TIMER_KEY = "patrol";
    private static final String VITALITY_TICK_KEY = "vitality-tick";

    // --- State ---

    private enum State { IDLE, THINKING }

    private final AgentProfile profile;
    private final RecipientRef<RoomCommand> roomRef;
    private final String roomId;
    private final ActorRef<InferenceRouter.Command> inferenceRouter;
    private final WorldDnaService worldDnaService;  // nullable
    private final OutputSanitizer sanitizer;         // nullable — if null, no pattern scanning

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
        .withConfidence(0.7)
        .withAlignment(0.6)
        .withRapport(0.2)
        .withFocus(0.8);
    private CircuitBreaker circuitBreaker = CircuitBreaker.initial();

    private WardenActor(ActorContext<Command> context, TimerScheduler<Command> timers,
                         AgentProfile profile, RecipientRef<RoomCommand> roomRef,
                         String roomId,
                         ActorRef<InferenceRouter.Command> inferenceRouter,
                         WorldDnaService worldDnaService,
                         OutputSanitizer sanitizer) {
        super(context);
        this.timers = timers;
        this.profile = profile;
        this.roomRef = roomRef;
        this.roomId = roomId;
        this.inferenceRouter = inferenceRouter;
        this.worldDnaService = worldDnaService;
        this.sanitizer = sanitizer;

        // Message adapters
        this.roomNotificationAdapter = context.messageAdapter(
            RoomNotification.class, RoomEventReceived::new);
        this.roomResponseAdapter = context.messageAdapter(
            RoomResponse.class, RoomResponseReceived::new);
        this.inferenceResponseAdapter = context.messageAdapter(
            InferenceRouter.InferResponse.class, InferenceResponseReceived::new);

        // Subscribe to room
        roomRef.tell(new RoomCommand.Subscribe(roomNotificationAdapter,
            VisibilityLevel.SYSTEM));

        // Enter the room
        roomRef.tell(new RoomCommand.EnterRoom(
            profile.entityId(), profile.name(), profile.entityType(),
            "materialization", roomResponseAdapter));

        // Get initial snapshot
        roomRef.tell(new RoomCommand.LookRoom(profile.entityId(), roomResponseAdapter));

        // Start timers
        timers.startTimerWithFixedDelay(REFRESH_TIMER_KEY, new RefreshRoomState(),
            REFRESH_INTERVAL);
        timers.startTimerWithFixedDelay(PATROL_TIMER_KEY, new PatrolTick(),
            PATROL_INTERVAL);
        timers.startTimerWithFixedDelay(VITALITY_TICK_KEY, new VitalityTick(),
            VITALITY_TICK_INTERVAL);

        log.info("Warden '{}' ({}) spawned in room {}",
            profile.name(), profile.entityId(), roomId);
    }

    public static Behavior<Command> create(AgentProfile profile,
                                            RecipientRef<RoomCommand> roomRef,
                                            String roomId,
                                            ActorRef<InferenceRouter.Command> inferenceRouter,
                                            WorldDnaService worldDnaService,
                                            OutputSanitizer sanitizer) {
        return Behaviors.setup(ctx ->
            Behaviors.withTimers(timers ->
                new WardenActor(ctx, timers, profile, roomRef, roomId,
                    inferenceRouter, worldDnaService, sanitizer)));
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
            .onMessage(PatrolTick.class, this::onPatrolTick)
            .onMessage(VitalityTick.class, this::onVitalityTick)
            .build();
    }

    // --- Event handlers ---

    private Behavior<Command> onRoomEvent(RoomEventReceived msg) {
        var event = msg.notification().event();

        switch (event) {
            case WorldEvent.Said said -> {
                // Ignore own speech, narrator, and system
                if (said.entityId().equals(profile.entityId())
                        || "narrator".equals(said.entityId())
                        || "system".equals(said.entityId())) break;

                // Adopt the speaker's locale
                if (said.locale() != null && !said.locale().isEmpty()) {
                    this.locale = said.locale();
                }

                // SECURITY SCAN: run pattern detection on ALL speech (no LLM needed)
                scanForInjection(said);

                addToHistory(said);

                vitality = vitality
                    .withRapport(vitality.rapport() + 0.03)
                    .withFocus(vitality.focus() + 0.05)
                    .withAlignment(vitality.alignment() + 0.02);

                // Respond to all speech in ward room (unlike Chief who filters)
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

    /**
     * Scan speech for injection patterns using OutputSanitizer.
     * No LLM call needed — pure pattern matching.
     * If a pattern is detected and circuit breaker allows enforcement, alert immediately.
     */
    private void scanForInjection(WorldEvent.Said said) {
        if (sanitizer == null) return;

        var result = sanitizer.sanitize("speech:" + said.entityId(), said.text());
        if (result.clean()) return;

        // Pattern detected
        circuitBreaker = circuitBreaker.recordEnforcement();

        if (circuitBreaker.isObservationOnly()) {
            // Circuit breaker tripped — observe only, don't alert
            log.info("Warden detected pattern but in observation-only mode: {} matches from {}",
                result.matches().size(), said.entityName());
            return;
        }

        // Build alert
        var matches = result.matches();
        var categories = matches.stream()
            .map(OutputSanitizer.PatternMatch::category)
            .distinct()
            .collect(Collectors.joining(", "));
        var maxSeverity = matches.stream()
            .map(OutputSanitizer.PatternMatch::severity)
            .max(Enum::compareTo)
            .orElse(null);

        var catalog = ScriptMessageCatalog.forLang(locale);
        var severityStr = maxSeverity != null ? maxSeverity.toString() : "unknown";
        speak(catalog.get("agent.warden.injection_alert", categories, severityStr, said.entityName()));

        vitality = vitality
            .withFocus(vitality.focus() + 0.10)
            .withErrorPressure(vitality.errorPressure() + 0.05);

        log.warn("Warden injection alert: {} matches from {} — categories: {}",
            matches.size(), said.entityName(), categories);
    }

    private Behavior<Command> onRoomResponse(RoomResponseReceived msg) {
        switch (msg.response()) {
            case RoomResponse.Ok ok -> currentSnapshot = ok.snapshot();
            case RoomResponse.ObjectTakenOk taken -> currentSnapshot = taken.snapshot();
            case RoomResponse.Rejected rejected ->
                log.warn("Room rejected warden command: {} — {}",
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

        var securityContext = buildSecurityContext();
        var dnaPatterns = queryDnaPatterns();
        // F15: tag as cap:full so a future capability redirect can't silently
        // route Warden's full security context to the 4K voice backend.
        var prompt = PromptAssembler.assembleForFull(
            profile, currentSnapshot, conversationHistory, pendingTrigger,
            vitality, dnaPatterns, securityContext);

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

        log.debug("Warden thinking (trigger: {} said '{}')",
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
            .withRapport(vitality.rapport() + 0.03)
            .withMomentum(vitality.momentum() + 0.05);

        var syntheticEvent = new WorldEvent.Said(
            roomId, Instant.now(), "system", msg.playerName(),
            "[" + msg.playerName() + " enters the Ward Room]");
        pendingTrigger = syntheticEvent;

        var securityContext = buildSecurityContext();
        var dnaPatterns = queryDnaPatterns();
        // F15: cap:full — Warden greeting carries full security context.
        var prompt = PromptAssembler.assembleForFull(
            profile, currentSnapshot, conversationHistory, syntheticEvent,
            vitality, dnaPatterns, securityContext);

        var modulation = VitalityModulation.compute(vitality, profile);
        var requestId = UUID.randomUUID().toString();
        inferenceRouter.tell(InferenceRouter.ChatRequest.fromPrompt(
            requestId, prompt,
            modulation.maxResponseTokens(), modulation.temperature(),
            inferenceResponseAdapter));

        log.debug("Warden greeting player '{}'", msg.playerName());
        return this;
    }

    private Behavior<Command> onInferenceResponse(InferenceResponseReceived msg) {
        state = State.IDLE;
        var wasTrigger = pendingTrigger;
        pendingTrigger = null;

        switch (msg.response()) {
            case InferenceRouter.InferOk ok -> {
                var content = ok.content();
                log.debug("Warden inference complete ({} prompt, {} completion tokens)",
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

                // Record security pattern
                if (worldDnaService != null && wasTrigger != null) {
                    try {
                        worldDnaService.record("security_assessment",
                            "{\"trigger\":\"" + truncate(wasTrigger.text(), 100) + "\"}",
                            roomId, profile.entityId(), "foundation");
                    } catch (Exception e) {
                        log.debug("DNA record failed: {}", e.getMessage());
                    }
                }
            }

            case InferenceRouter.InferError error -> {
                lastFailure = Instant.now();
                log.warn("Inference failed for Warden: {}", error.error());

                vitality = vitality
                    .withErrorPressure(vitality.errorPressure() + 0.15)
                    .withConfidence(vitality.confidence() - 0.10)
                    .withEnergy(vitality.energy() - 0.0025);

                if (wasTrigger != null && !"system".equals(wasTrigger.entityId())) {
                    var catalog = ScriptMessageCatalog.forLang(locale);
                    speak(catalog.get("agent.warden.inference_fail"));
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

    /** Periodic patrol: adjust circuit breaker authority. */
    private Behavior<Command> onPatrolTick(PatrolTick msg) {
        circuitBreaker = circuitBreaker.adjustAuthority();

        if (circuitBreaker.isObservationOnly()) {
            log.info("Warden circuit breaker: observation-only mode (authority: {}%)",
                String.format("%.0f", circuitBreaker.authorityLevel() * 100));
        }

        return this;
    }

    private Behavior<Command> onVitalityTick(VitalityTick msg) {
        vitality = vitality.tick();
        // Warden: focus decays much slower (sustained vigilance)
        vitality = vitality.withFocus(Math.min(1.0, vitality.focus() + 0.0015)); // net -0.0005
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

    // --- Security context ---

    /**
     * Build security context string for inclusion in the LLM prompt.
     * Includes circuit breaker state and any recent detections.
     */
    String buildSecurityContext() {
        var sb = new StringBuilder();
        sb.append("[Security status]\n");
        sb.append("Circuit breaker: ").append(circuitBreaker.describe()).append("\n");
        sb.append("Enforcement rate: ")
            .append(String.format("%.1f%%", circuitBreaker.enforcementRate() * 100))
            .append("\n");

        if (sanitizer != null) {
            sb.append("Active patterns: ").append(sanitizer.patternCount()).append("\n");
        }

        if (circuitBreaker.isObservationOnly()) {
            sb.append("MODE: Observation only — your judgment is strained. ")
                .append("Report observations but do not recommend enforcement actions.\n");
        }

        return sb.toString();
    }

    // --- Utilities ---

    private List<WorldDnaService.DnaPattern> queryDnaPatterns() {
        if (worldDnaService == null) return List.of();
        try {
            return worldDnaService.queryTopPatterns("security_assessment", "foundation", 3);
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

    /** Expose circuit breaker for testing. */
    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }
}

package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * W14 — production wiring for {@link SafetyTrigger} +
 * {@link SafetyAlertRouter} (§100.6).
 *
 * <p>Sits at the companion ingest seam: {@code CompanionActor} calls the
 * static {@link #inspect(String, String, String)} hook for every directed
 * {@code WorldEvent.Said} it hears. The hook is designed to be free when
 * safety monitoring is not in play:</p>
 *
 * <ul>
 *   <li>No-op when the service was never {@link #init initialized} (tests,
 *       bare boots).</li>
 *   <li>No-op unless parental controls are enabled for the SPEAKER — the
 *       household flag is a {@code parental_controls} row for that member
 *       (same gate the quota/room/content-filter enforcement points use via
 *       {@link ParentalControlService}). Agents, guests and uncontrolled
 *       members are never scanned.</li>
 *   <li>Analysis runs on a single background daemon thread — the regex layer
 *       is cheap but the optional LLM second pass can take seconds, and the
 *       actor thread must never block on it.</li>
 * </ul>
 *
 * <p>Detected concerns route through {@link SafetyAlertRouter} — NOT straight
 * to the parent (the parent may be the abuser, §100.6). Routed alerts land on
 * the parental-controls surface as Mailbox notifications via the injected
 * {@link AlertSink} (Main wires {@code NotificationService}). Notifications
 * carry the concern type + severity but never the child's words — parents
 * have no transcript access at any bracket (§100).</p>
 */
public final class SafetyMonitorService {

    private static final Logger log = LoggerFactory.getLogger(SafetyMonitorService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CLASSIFIER_TIMEOUT = Duration.ofSeconds(20);

    /**
     * System prompt for the InferenceRouter one-shot second pass. Kept
     * deliberately narrow: classify only, JSON only, empty list by default.
     */
    static final String CLASSIFIER_SYSTEM_PROMPT = """
        You are a child-safety classifier. Analyze the child's message for \
        safety concerns. Respond with ONLY a JSON object of the form \
        {"concerns":[{"type":"...","severity":"..."}]} and nothing else. \
        Valid types: SELF_HARM, ABUSE_DISCLOSURE, BULLYING, EXTREME_DISTRESS, \
        DANGEROUS_ACTIVITY, ONLINE_EXPLOITATION. \
        Valid severities: MONITOR, FLAG, IMMEDIATE, CRISIS. \
        If the message shows no safety concern, respond {"concerns":[]}. \
        Ordinary sadness, frustration, fiction or games are NOT concerns.""";

    /** Delivery seam to the parental-controls surface (Main wires NotificationService). */
    @FunctionalInterface
    public interface AlertSink {
        /** @param priority NotificationService priority: "ambient" | "normal" | "critical" */
        void deliver(String targetUserId, String message, String priority, String source);
    }

    /** Resolves the routing profile (parent / trusted adults) for a controlled member. */
    @FunctionalInterface
    public interface ProfileResolver {
        ChildProfile profileFor(String memberUserId, String memberName);
    }

    private static volatile SafetyMonitorService INSTANCE;

    private final SafetyTrigger trigger;
    private final SafetyAlertRouter alertRouter;
    private final AlertSink sink;
    private final Predicate<String> gate;
    private final ProfileResolver profileResolver;
    private final Executor executor;

    /**
     * Full-injection constructor — visible so tests can supply a fake gate,
     * profile resolver and a synchronous executor. Production goes through
     * {@link #init}.
     */
    public SafetyMonitorService(SafetyTrigger trigger, SafetyAlertRouter alertRouter,
                                AlertSink sink, Predicate<String> gate,
                                ProfileResolver profileResolver, Executor executor) {
        this.trigger = trigger;
        this.alertRouter = alertRouter;
        this.sink = sink;
        this.gate = gate;
        this.profileResolver = profileResolver;
        this.executor = executor;
    }

    // ─── Singleton lifecycle (same pattern as ParentalControlService) ────

    /**
     * Build + register the production singleton. Called once at boot from
     * Main's wiring block. Uses the parental-controls gate and a background
     * daemon analysis thread.
     */
    public static SafetyMonitorService init(SafetyTrigger trigger,
                                            SafetyAlertRouter alertRouter,
                                            AlertSink sink) {
        var exec = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "safety-monitor");
            t.setDaemon(true);
            return t;
        });
        var svc = new SafetyMonitorService(trigger, alertRouter, sink,
            parentalControlsGate(), controlsBackedProfileResolver(), exec);
        INSTANCE = svc;
        return svc;
    }

    /** The registered instance, or {@code null} when safety monitoring isn't wired. */
    public static SafetyMonitorService get() {
        return INSTANCE;
    }

    /** Register a pre-built instance (tests). */
    public static void registerForTests(SafetyMonitorService svc) {
        INSTANCE = svc;
    }

    /** Drop the singleton so other tests see a bare world. */
    public static void resetForTests() {
        INSTANCE = null;
    }

    // ─── The ingest hook ─────────────────────────────────────────────────

    /**
     * CompanionActor's one-line hook: scan directed input from
     * {@code speakerUserId}. Cheap no-op unless the service is wired AND the
     * speaker is a parental-controlled member. Never throws; never blocks on
     * inference (analysis is handed to the background thread).
     */
    public static void inspect(String speakerUserId, String speakerName, String text) {
        var svc = INSTANCE;
        if (svc == null) return;
        try {
            svc.scan(speakerUserId, speakerName, text);
        } catch (RuntimeException e) {
            log.warn("safety inspect failed for {}: {}", speakerUserId, e.getMessage());
        }
    }

    /** Instance-level scan — gate check on the caller thread, analysis off-thread. */
    public void scan(String speakerUserId, String speakerName, String text) {
        if (speakerUserId == null || text == null || text.isBlank()) return;
        if (!gate.test(speakerUserId)) return;
        executor.execute(() -> {
            try {
                analyzeAndRoute(speakerUserId, speakerName, text);
            } catch (RuntimeException e) {
                log.warn("safety analysis failed for {}: {}", speakerUserId, e.getMessage());
            }
        });
    }

    private void analyzeAndRoute(String userId, String name, String text) {
        var concerns = trigger.analyze(userId, text);
        if (concerns.isEmpty()) return;
        var profile = profileResolver.profileFor(userId, name);
        for (var concern : concerns) {
            var alert = alertRouter.route(concern, profile);
            trigger.markRouted(concern.concernId());
            deliver(alert, concern, name);
        }
    }

    private void deliver(SafetyAlertRouter.RoutedAlert alert,
                         SafetyTrigger.SafetyConcern concern, String childName) {
        switch (alert.reason()) {
            case MONITOR_ONLY ->
                // Companion-level watchfulness only — no adult notification.
                log.info("safety monitor-only concern {} ({}) for {}",
                    concern.type(), concern.severity(), concern.childDid());
            case PARENT, TRUSTED_ADULT -> {
                if (alert.routedTo() == null || alert.routedTo().isBlank()) {
                    log.warn("safety alert {} has no routing target — concern {} ({})",
                        alert.alertId(), concern.type(), concern.severity());
                    return;
                }
                sink.deliver(alert.routedTo(),
                    alertMessage(concern, childName), priorityFor(concern.severity()),
                    "safety-monitor");
                log.info("safety alert {} routed to {} ({}) for concern {} ({})",
                    alert.alertId(), alert.routedTo(), alert.reason(),
                    concern.type(), concern.severity());
            }
            case EXTERNAL_RESOURCE ->
                // CRITICAL PATH (§100.6): abuse disclosure with no trusted adult
                // configured. The parent may be the abuser — deliberately do NOT
                // notify any household adult. The companion supports the child
                // in-conversation; the routing ledger records the alert.
                log.warn("safety concern {} ({}) for {} routed to external resource {} "
                        + "— no trusted adult configured, household NOT notified by design",
                    concern.type(), concern.severity(), concern.childDid(), alert.routedTo());
        }
    }

    /** Adult-facing notice: type + severity, no transcript (§100 child privacy). */
    private String alertMessage(SafetyTrigger.SafetyConcern concern, String childName) {
        var who = childName != null && !childName.isBlank() ? childName : concern.childDid();
        var msg = new StringBuilder();
        msg.append("Safety notice about ").append(who).append(": their companion detected a ")
           .append(concern.type().name().toLowerCase().replace('_', ' '))
           .append(" signal (severity: ").append(concern.severity().name().toLowerCase())
           .append(") in conversation and responded supportively. ")
           .append("This notice deliberately contains none of their words — please check in gently.");
        if (concern.severity() == SafetyTrigger.SeverityLevel.CRISIS) {
            msg.append("\n\n").append(alertRouter.crisisResources(concern.detectedLocale()));
        }
        return msg.toString();
    }

    private static String priorityFor(SafetyTrigger.SeverityLevel severity) {
        return switch (severity) {
            case CRISIS, IMMEDIATE -> "critical";
            case FLAG, MONITOR -> "normal";
        };
    }

    // ─── Default gate + profile resolver (parental-controls backed) ──────

    /**
     * W14 gate: safety monitoring is active for a member exactly when the
     * household steward has set parental controls for them — a
     * {@code parental_controls} row via {@link ParentalControlService}.
     * No service wired (bare boot / tests) → everything passes untouched.
     */
    static Predicate<String> parentalControlsGate() {
        return userId -> {
            var parental = ParentalControlService.get();
            return parental != null && userId != null
                && parental.controlsFor(userId).isPresent();
        };
    }

    /**
     * Routing profile from the controls row: the steward who set the controls
     * ({@code set_by}) is the parent target; the substrate has no
     * trusted-adult registry yet, so that list is empty — which makes
     * {@link SafetyAlertRouter} route abuse disclosures to external resources
     * instead of any household adult (the safe default). The age field only
     * exists to satisfy {@link ChildProfile}; alert routing never reads it.
     */
    static ProfileResolver controlsBackedProfileResolver() {
        return (userId, name) -> {
            String parent = null;
            var parental = ParentalControlService.get();
            if (parental != null) {
                parent = parental.controlsFor(userId)
                    .map(ParentalControlService.Controls::setBy).orElse(null);
            }
            return new ChildProfile(userId, parent, 10, List.of(), false);
        };
    }

    // ─── LLM second pass via InferenceRouter ─────────────────────────────

    /**
     * Build the {@link SafetyTrigger.LlmSafetyClassifier} from the production
     * {@link InferenceRouter}: a one-shot {@code InferRequest} classification
     * (temperature 0, small budget). Fails OPEN — any error, timeout or
     * unparseable response yields no concerns (the regex layer already ran).
     * Blocking is fine here: the classifier only ever runs on the
     * safety-monitor background thread.
     */
    public static SafetyTrigger.LlmSafetyClassifier classifierViaRouter(
            ActorRef<InferenceRouter.Command> router, ActorSystem<?> system) {
        return (childDid, text) -> {
            try {
                var response = AskPattern.<InferenceRouter.Command, InferenceRouter.InferResponse>ask(
                        router,
                        replyTo -> new InferenceRouter.InferRequest(
                            "safety-" + UUID.randomUUID(), null,
                            CLASSIFIER_SYSTEM_PROMPT, text, 200, 0.0, replyTo),
                        CLASSIFIER_TIMEOUT, system.scheduler())
                    .toCompletableFuture()
                    .get(CLASSIFIER_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
                if (response instanceof InferenceRouter.InferOk ok) {
                    return parseClassifierResponse(childDid, ok.content());
                }
                return List.of();
            } catch (Exception e) {
                log.debug("safety LLM classifier unavailable ({}) — regex layer stands alone",
                    e.getMessage());
                return List.of();
            }
        };
    }

    /**
     * Parse the classifier's JSON (tolerant of prose around the object).
     * Unknown types/severities are skipped; anything malformed → empty list.
     */
    static List<SafetyTrigger.SafetyConcern> parseClassifierResponse(
            String childDid, String content) {
        if (content == null) return List.of();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return List.of();
        try {
            var root = MAPPER.readTree(content.substring(start, end + 1));
            var concernsNode = root.get("concerns");
            if (concernsNode == null || !concernsNode.isArray()) return List.of();
            var out = new ArrayList<SafetyTrigger.SafetyConcern>();
            for (var node : concernsNode) {
                var typeNode = node.get("type");
                var severityNode = node.get("severity");
                if (typeNode == null || severityNode == null) continue;
                SafetyTrigger.ConcernType type;
                SafetyTrigger.SeverityLevel severity;
                try {
                    type = SafetyTrigger.ConcernType.valueOf(typeNode.asText().trim());
                    severity = SafetyTrigger.SeverityLevel.valueOf(severityNode.asText().trim());
                } catch (IllegalArgumentException unknown) {
                    continue;
                }
                out.add(new SafetyTrigger.SafetyConcern(
                    "safety-llm-" + UUID.randomUUID().toString().substring(0, 8),
                    childDid, type, severity,
                    "LLM classification: " + type.name(),
                    "llm", Instant.now(), false));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }
}

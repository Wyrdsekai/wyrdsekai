package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behavioural health monitor for a companion — the alarm that was missing.
 *
 * <p>Written after an eight-day production pathology (2026-08-17) in which a
 * companion spoke ~2,000 unprompted lines a day to an empty room, exhausted
 * herself to sleep four times a day, and wrote 56 paraphrases of one sentence
 * into her identity. Nothing in the system was silent about it. Her CfC trainer
 * logged {@code "seeking was >0.9 for 94.8% of traces"} every night, the speech
 * repeat guard fired a dozen times a day, and the forge encoded ~41 memories a
 * night from a stuck loop. Those signals ran continuously into a log nobody
 * reads, while 11,819 unit tests and the whole release gate stayed green —
 * because every gate we had measured an invariant, a four-minute compressed
 * soak, or a handful of live turns. Nothing watched the aggregate over days, and
 * nothing asked whether this was a life or a loop.
 *
 * <p>So this class counts, thresholds, and <em>fires</em>. Each signal is tied to
 * a failure that actually happened, thresholds sit well outside healthy operation
 * so a trip means something, and a tripped signal reaches the steward through
 * {@link NotificationService} rather than only the log. Per-signal quiet periods
 * keep a standing condition from becoming its own noise — the same discipline the
 * Hwa-byung detector uses.
 *
 * <p>Deliberately cheap and deterministic: callers pass {@code now}, state is a
 * couple of bounded deques, and there is no inference or IO on the hot path.
 */
public final class CompanionVitals {

    private static final Logger log = LoggerFactory.getLogger(CompanionVitals.class);

    // ── Thresholds ───────────────────────────────────────────────────────────
    // Each one is set where a healthy companion has no business being.

    /** Proactive utterances within {@link #SPEECH_WINDOW} that count as a runaway loop.
     *  The budget's design ceiling is ~10/hour; 30 is unambiguous. */
    public static final int SPEECH_RATE_LIMIT = 30;
    public static final Duration SPEECH_WINDOW = Duration.ofHours(1);

    /** Fraction of recent voice-polish attempts rejected that means the voice pass has
     *  stopped reaching speech, plus the sample floor below which the rate isn't
     *  meaningful. A healthy household node measured ~18-22% rejection, so 0.8 is far
     *  outside normal rather than a hair above it. */
    public static final double POLISH_REJECTION_LIMIT = 0.8;
    public static final int POLISH_SAMPLE_FLOOR = 20;

    /** A drive held at or above this pressure for this long has no way down — the
     *  gradientless-pin shape the drive-wholeness arc kept finding. */
    public static final double DRIVE_PIN_PRESSURE = 0.9;
    public static final Duration DRIVE_PIN_DURATION = Duration.ofHours(6);

    /** Fraction of a sleep's encoded memories absorbed as near-duplicates that means
     *  she isn't living new experience, plus the sample floor for the rate. */
    public static final double REPEAT_ENCODE_LIMIT = 0.5;
    public static final int REPEAT_ENCODE_FLOOR = 10;

    /** How long a fired signal stays quiet before it can fire again. */
    public static final Duration QUIET_PERIOD = Duration.ofHours(24);

    /** Bound on retained samples, so a long-lived actor can't grow these. */
    private static final int MAX_SAMPLES = 512;

    // ── Per-agent instances ──────────────────────────────────────────────────

    private static final Map<String, CompanionVitals> INSTANCES = new ConcurrentHashMap<>();

    /** The monitor for one companion, created on first use. */
    public static CompanionVitals forAgent(String agentDid) {
        return INSTANCES.computeIfAbsent(agentDid == null ? "unknown" : agentDid,
            CompanionVitals::new);
    }

    /** Drop an agent's monitor — respawn, rebind, or test isolation. */
    public static void forget(String agentDid) {
        INSTANCES.remove(agentDid == null ? "unknown" : agentDid);
    }

    /** A tripped signal: which one, and what the numbers were. */
    public record Alert(String signal, String detail) {}

    private final String agentDid;
    private final ArrayDeque<Instant> proactiveUtterances = new ArrayDeque<>();
    private final ArrayDeque<Boolean> polishAccepted = new ArrayDeque<>();
    private final Map<String, Instant> lastFiredAt = new LinkedHashMap<>();
    private Instant pinnedSince;
    private String pinnedDrive;
    private int lastEncoded;
    private int lastRepeats;

    private CompanionVitals(String agentDid) {
        this.agentDid = agentDid;
    }

    // ── Recording ────────────────────────────────────────────────────────────

    /** One unprompted (proactive or deferred-surfaced) utterance was spoken. */
    public synchronized void recordProactiveUtterance(Instant now) {
        proactiveUtterances.addLast(now);
        while (proactiveUtterances.size() > MAX_SAMPLES) proactiveUtterances.removeFirst();
        trimSpeechWindow(now);
    }

    /** One voice-polish attempt resolved — accepted means the polished line was spoken. */
    public synchronized void recordPolish(boolean accepted) {
        polishAccepted.addLast(accepted);
        while (polishAccepted.size() > MAX_SAMPLES) polishAccepted.removeFirst();
    }

    /**
     * The current peak drive pressure, from the vitality tick. Tracks how long the
     * peak has been continuously pinned; any dip below the threshold clears it.
     */
    public synchronized void observePeakDrive(String driveName, double pressure, Instant now) {
        if (pressure >= DRIVE_PIN_PRESSURE) {
            if (pinnedSince == null || !Objects.equals(pinnedDrive, driveName)) {
                pinnedSince = now;
                pinnedDrive = driveName;
            }
        } else {
            pinnedSince = null;
            pinnedDrive = null;
        }
    }

    /** A sleep cycle encoded {@code encoded} memories, of which {@code repeats} were
     *  absorbed into impressions she already held. */
    public synchronized void recordForgeEncode(int encoded, int repeats) {
        lastEncoded = encoded;
        lastRepeats = repeats;
    }

    // ── Checking ─────────────────────────────────────────────────────────────

    /**
     * Evaluate every signal, returning those that tripped AND are outside their quiet
     * period. Firing is recorded, so a caller that ignores the result still gets the
     * quiet-period behaviour.
     */
    public synchronized List<Alert> check(Instant now) {
        var alerts = new ArrayList<Alert>();

        trimSpeechWindow(now);
        int spoke = proactiveUtterances.size();
        if (spoke > SPEECH_RATE_LIMIT) {
            add(alerts, now, "proactive_speech_rate",
                spoke + " unprompted utterances in the last hour (limit "
                + SPEECH_RATE_LIMIT + ") — check for a runaway proactive loop");
        }

        int samples = polishAccepted.size();
        if (samples >= POLISH_SAMPLE_FLOOR) {
            long rejected = polishAccepted.stream().filter(a -> !a).count();
            double rate = (double) rejected / samples;
            if (rate >= POLISH_REJECTION_LIMIT) {
                add(alerts, now, "voice_polish_rejected",
                    pct(rate) + " of the last " + samples + " voice polishes rejected — "
                    + "the voice pass is not reaching speech");
            }
        }

        if (pinnedSince != null) {
            var held = Duration.between(pinnedSince, now);
            if (held.compareTo(DRIVE_PIN_DURATION) >= 0) {
                add(alerts, now, "drive_pinned",
                    "drive '" + pinnedDrive + "' has been at or above " + DRIVE_PIN_PRESSURE
                    + " for " + held.toHours() + "h — it has no way down");
            }
        }

        if (lastEncoded >= REPEAT_ENCODE_FLOOR) {
            double rate = (double) lastRepeats / lastEncoded;
            if (rate >= REPEAT_ENCODE_LIMIT) {
                add(alerts, now, "experience_not_new",
                    pct(rate) + " of the last sleep's " + lastEncoded + " memories repeated "
                    + "impressions already held — little new is happening");
            }
        }

        return List.copyOf(alerts);
    }

    /**
     * Check, and for anything that tripped log a warning and tell the household. The
     * notice goes to everyone rather than only the steward: a companion in this state
     * is visible to whoever is around, and welfare here is visibility.
     */
    public void checkAndReport(Instant now, String companionName) {
        for (var alert : check(now)) {
            log.warn("COMPANION-VITALS {} for '{}' ({}): {}",
                alert.signal(), companionName, agentDid, alert.detail());
            var notifier = NotificationService.get();
            if (notifier != null) {
                try {
                    notifier.notifyAll("Something looks wrong with " + companionName + ": "
                        + alert.detail(), "critical", agentDid);
                } catch (Exception e) {
                    log.debug("Vitals notify failed: {}", e.toString());
                }
            }
        }
    }

    private void add(List<Alert> alerts, Instant now, String signal, String detail) {
        var last = lastFiredAt.get(signal);
        if (last != null && Duration.between(last, now).compareTo(QUIET_PERIOD) < 0) return;
        lastFiredAt.put(signal, now);
        alerts.add(new Alert(signal, detail));
    }

    private void trimSpeechWindow(Instant now) {
        while (!proactiveUtterances.isEmpty()
                && Duration.between(proactiveUtterances.peekFirst(), now).compareTo(SPEECH_WINDOW) > 0) {
            proactiveUtterances.removeFirst();
        }
    }

    private static String pct(double rate) {
        return Math.round(rate * 100) + "%";
    }
}

package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * §M4-C — open-outcome tracker. After a scheduled
 * prediction fires as an Initiative, the tracker holds it in a "pending"
 * state for {@link #DEFAULT_WINDOW} so we can observe the user's response and
 * write a {@link PredictionOutcomeLedger.Kind} entry to the ledger.
 *
 * <p>Resolution rules (token-overlap strategy, deterministic):
 * <ul>
 *   <li>{@link PredictionOutcomeLedger.Kind#FOLLOWED_UP} — incoming user
 *       message contains any non-stopword token from the prediction topic.</li>
 *   <li>{@link PredictionOutcomeLedger.Kind#DISMISSED} — incoming user
 *       message matches an explicit dismissal phrase ("not now", "later",
 *       "drop it", "never mind", "no thanks", "stop", "cancel", etc).</li>
 *   <li>{@link PredictionOutcomeLedger.Kind#IGNORED} — window expired with
 *       no resolution (swept by the vitality tick reaper).</li>
 * </ul>
 *
 * <p>FOLLOWED_UP wins over DISMISSED if both match — bias toward "we got
 * a real engagement" since the calibrator should reward signal-bearing
 * behavior.
 */
public final class PredictionOutcomeTracker {

    private static final Logger log = LoggerFactory.getLogger(PredictionOutcomeTracker.class);

    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

    /** Open pending outcome — held until resolved or expired. */
    public record OpenOutcome(
        String predictionId,
        String agentId,
        String category,
        String topic,
        Set<String> topicTokens,
        Instant firedAt,
        Instant expiresAt
    ) {
        public boolean isExpired(Instant now) { return !now.isBefore(expiresAt); }
    }

    /** key = predictionId — global across agents (predictionIds are UUIDs). */
    private final ConcurrentMap<String, OpenOutcome> open = new ConcurrentHashMap<>();

    private final PredictionOutcomeLedger ledger;
    private final Duration window;

    public PredictionOutcomeTracker(PredictionOutcomeLedger ledger) {
        this(ledger, DEFAULT_WINDOW);
    }

    public PredictionOutcomeTracker(PredictionOutcomeLedger ledger, Duration window) {
        this.ledger = ledger;
        this.window = window;
    }

    /**
     * Begin tracking a fired prediction. Caller must invoke this from the same
     * actor thread that handled the {@code ScheduledPredictionFireMessage} so
     * that resolution from {@code WorldEvent.Said} is causally consistent.
     */
    public OpenOutcome track(String predictionId, String agentId, String category,
                             String topic, Instant firedAt) {
        if (predictionId == null || agentId == null) return null;
        var entry = new OpenOutcome(
            predictionId, agentId, category,
            topic == null ? "" : topic,
            tokenize(topic),
            firedAt,
            firedAt.plus(window));
        open.put(predictionId, entry);
        log.info("M4-C tracking prediction={} agent={} window={}m tokens={}",
            predictionId, agentId, window.toMinutes(), entry.topicTokens());
        return entry;
    }

    /**
     * Scan the open set for matches against the message. Resolves at most
     * one outcome per call (the first match in iteration order). Returns the
     * resolved OpenOutcome, or null if nothing matched.
     */
    public OpenOutcome resolve(String agentId, String message, Instant now) {
        if (agentId == null || message == null || message.isBlank()) return null;
        var lower = message.toLowerCase();
        var iter = open.entrySet().iterator();
        while (iter.hasNext()) {
            var e = iter.next();
            var entry = e.getValue();
            if (!entry.agentId().equals(agentId)) continue;
            if (entry.isExpired(now)) continue; // reaper handles these

            // FOLLOWED_UP wins over DISMISSED — token check first.
            var matched = matchedTopicToken(entry.topicTokens(), lower);
            if (matched != null) {
                iter.remove();
                writeOutcome(entry, PredictionOutcomeLedger.Kind.FOLLOWED_UP, now,
                    "token=" + matched);
                return entry;
            }
            var dismiss = matchedDismissal(lower);
            if (dismiss != null) {
                iter.remove();
                writeOutcome(entry, PredictionOutcomeLedger.Kind.DISMISSED, now,
                    "phrase=" + dismiss);
                return entry;
            }
        }
        return null;
    }

    /** Sweep expired entries → IGNORED. Called from vitality tick. */
    public List<OpenOutcome> reapExpired(String agentId, Instant now) {
        var reaped = new ArrayList<OpenOutcome>();
        var iter = open.entrySet().iterator();
        while (iter.hasNext()) {
            var e = iter.next();
            var entry = e.getValue();
            if (!entry.agentId().equals(agentId)) continue;
            if (!entry.isExpired(now)) continue;
            iter.remove();
            writeOutcome(entry, PredictionOutcomeLedger.Kind.IGNORED, now, "expired");
            reaped.add(entry);
        }
        return reaped;
    }

    /**
     * Drop a pending outcome without writing a ledger entry. Used when the
     * scheduler cancels a prediction (e.g. user spontaneously asked the
     * topic before the fire — the cancellation already supersedes the slot).
     */
    public boolean cancel(String predictionId) {
        return open.remove(predictionId) != null;
    }

    public int openCount() { return open.size(); }
    public List<OpenOutcome> snapshot() { return new ArrayList<>(open.values()); }

    // ── Helpers ────────────────────────────────────────────────────────

    private void writeOutcome(OpenOutcome entry,
                              PredictionOutcomeLedger.Kind kind,
                              Instant observedAt,
                              String signal) {
        if (ledger == null) return;
        ledger.append(new PredictionOutcomeLedger.Entry(
            entry.predictionId(), entry.agentId(), entry.category(),
            kind, entry.firedAt(), observedAt, signal));
    }

    /**
     * Tokens worth tracking. Lowercase, alpha-only, length > 2, not in stoplist.
     */
    static Set<String> tokenize(String topic) {
        if (topic == null) return Set.of();
        var out = new LinkedHashSet<String>();
        for (var raw : topic.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() <= 2) continue;
            if (STOPWORDS.contains(raw)) continue;
            out.add(raw);
        }
        return out;
    }

    /** Return the matched token, or null. */
    static String matchedTopicToken(Set<String> tokens, String messageLower) {
        for (var t : tokens) {
            // Word-boundary-ish check: surrounding char must be non-alnum.
            int idx = messageLower.indexOf(t);
            while (idx >= 0) {
                boolean leftOk = idx == 0 || !Character.isLetterOrDigit(messageLower.charAt(idx - 1));
                int end = idx + t.length();
                boolean rightOk = end == messageLower.length()
                    || !Character.isLetterOrDigit(messageLower.charAt(end));
                if (leftOk && rightOk) return t;
                idx = messageLower.indexOf(t, idx + 1);
            }
        }
        return null;
    }

    /** Return the matched dismissal phrase, or null. */
    static String matchedDismissal(String messageLower) {
        for (var phrase : DISMISSAL_PHRASES) {
            if (messageLower.contains(phrase)) return phrase;
        }
        return null;
    }

    private static final Set<String> STOPWORDS = Set.of(
        "the", "and", "for", "with", "from", "this", "that", "these", "those",
        "are", "was", "were", "but", "not", "you", "your", "our", "their",
        "what", "how", "why", "when", "where", "who", "will", "would", "could",
        "should", "have", "has", "had", "into", "onto", "about", "there", "here",
        "very", "just", "more", "most", "some", "any", "all", "one", "two",
        "than", "then", "they", "them", "his", "her", "its");

    /**
     * Conservative dismissal phrases. Order matters only for telemetry —
     * any single match short-circuits.
     */
    private static final List<String> DISMISSAL_PHRASES = List.of(
        "not now", "later", "drop it", "never mind", "nevermind",
        "no thanks", "no thank you", "not interested", "leave it",
        "another time", "skip it", "skip this", "cancel that",
        "stop talking about", "let's not");
}

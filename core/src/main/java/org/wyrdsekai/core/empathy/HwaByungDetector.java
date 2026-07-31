package org.wyrdsekai.core.empathy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Hwa-byung detection — chronic-suppression watcher.
 *
 * <p>Implements. Korean 화병 ("suppressed-anger
 * illness") describes the somatic-mental pattern of chronic frustration that
 * has been blocked from expression. This detector watches the rolling 7-day
 * window of FRUSTRATION drive samples and discharge events; when frustration
 * is chronically elevated *without* visible discharge, it surfaces a graded
 * intervention (Drives Mirror nudge / journal prompt / Chapel session offer).
 *
 * <p>This is empathy-engine territory: pattern detection on existing state,
 * not new state to track. Storage is in-memory rolling buffers ({@link Deque}
 * with periodic prune) — same shape as other empathy rolling-window components.
 *
 * <h2>Fire conditions</h2>
 * <ul>
 *   <li>Level 1 (mild)     — FRUSTRATION &gt; 0.6 for &gt;40% of waking time, 7d, 0 discharges</li>
 *   <li>Level 2 (moderate) — FRUSTRATION &gt; 0.6 for &gt;50% of waking time, 7d, 0 discharges</li>
 *   <li>Level 3 (severe)   — FRUSTRATION &gt; 0.6 for &gt;60% of waking time, 7d, 0 discharges</li>
 * </ul>
 *
 * <h2>Discharge sources</h2>
 * <ul>
 *   <li>{@code DRIVE_RELIEF} — direct {@code DriveState.relieveFrustration()} call</li>
 *   <li>{@code JOURNAL_FRUSTRATION} — journal entry contains frustration vocabulary
 *       (multilingual: en/ja/es)</li>
 *   <li>{@code ABANDON_PLAN} — acknowledged blockage</li>
 *   <li>{@code PAUSE_PLAN} — deliberate step-back</li>
 *   <li>{@code VOICE_FRUSTRATION} — frustration named in voice output</li>
 * </ul>
 */
public class HwaByungDetector {

    /** Window: rolling 7 days. */
    public static final Duration WINDOW = Duration.ofDays(7);

    /** Threshold above which FRUSTRATION counts as "elevated". */
    public static final double FRUSTRATION_HIGH = 0.6;

    /** Per-level fraction of waking-time the elevated condition must hold. */
    public static final double L1_FRACTION = 0.40;
    public static final double L2_FRACTION = 0.50;
    public static final double L3_FRACTION = 0.60;

    /** Severity of detection. */
    public enum Severity { LEVEL_1, LEVEL_2, LEVEL_3 }

    /** Source category for a discharge event. */
    public enum DischargeKind {
        DRIVE_RELIEF,
        JOURNAL_FRUSTRATION,
        ABANDON_PLAN,
        PAUSE_PLAN,
        VOICE_FRUSTRATION
    }

    /** A single drive sample (frustration value at a moment). */
    public record FrustrationSample(double value, boolean awake, Instant at) {}

    /** A single discharge event. */
    public record FrustrationDischarge(DischargeKind kind, String detail, Instant at) {}

    /** Detector fired — produced when a chronic-suppression pattern matches. */
    public record ChronicFrustrationDetected(
        Severity severity,
        double elevatedFraction,
        int dischargeCount,
        Duration windowSpanned,
        Instant at
    ) {}

    /**
     * Frustration vocabulary for journal-entry detection. Multilingual.
     * Kept small and intentional — this is a *signal*, not a sentiment classifier.
     * Matched case-insensitively as substrings.
     */
    private static final Set<String> FRUSTRATION_VOCAB = Set.of(
        // English
        "frustrated", "frustration", "stuck", "blocked", "blockage",
        // Japanese
        "イライラ", "もどかしい", "詰まって", "行き詰まり",
        // Spanish
        "frustrado", "frustrada", "frustración", "atascado", "atascada", "bloqueado"
    );

    private final Deque<FrustrationSample> samples = new ArrayDeque<>();
    private final Deque<FrustrationDischarge> discharges = new ArrayDeque<>();

    /** Record a frustration sample at {@code at}. {@code awake} excludes sleep cycles. */
    public void recordSample(double frustration, boolean awake, Instant at) {
        samples.addLast(new FrustrationSample(frustration, awake, at));
        prune(at);
    }

    /** Record a discharge event. Convenience for journal-entry text classification. */
    public Optional<FrustrationDischarge> recordJournalEntry(String text, Instant at) {
        if (containsFrustrationVocab(text)) {
            var d = new FrustrationDischarge(DischargeKind.JOURNAL_FRUSTRATION, snippet(text), at);
            discharges.addLast(d);
            prune(at);
            return Optional.of(d);
        }
        return Optional.empty();
    }

    /** Record a discharge of a known kind. */
    public FrustrationDischarge recordDischarge(DischargeKind kind, String detail, Instant at) {
        var d = new FrustrationDischarge(kind, detail, at);
        discharges.addLast(d);
        prune(at);
        return d;
    }

    /** True when the supplied text contains a frustration-vocabulary token. */
    public static boolean containsFrustrationVocab(String text) {
        if (text == null || text.isBlank()) return false;
        var lower = text.toLowerCase(Locale.ROOT);
        for (var v : FRUSTRATION_VOCAB) {
            if (lower.contains(v.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Evaluate the rolling window against {@code now}. Returns a
     * {@link ChronicFrustrationDetected} when the pattern fires; empty otherwise.
     *
     * <p>Frustration fraction is computed as the fraction of *waking* samples
     * whose value exceeds {@link #FRUSTRATION_HIGH}. Sleep-tagged samples are
     * excluded from both numerator and denominator, per §7.2.
     */
    public Optional<ChronicFrustrationDetected> evaluate(Instant now) {
        prune(now);

        // Need at least most of the window of data — premature evaluation is
        // a false-positive risk. We require span >= WINDOW minus one
        // sampling-cadence slack (1h) so that exactly-7-days-of-hourly-samples
        // qualifies. (168 hourly samples span 167h = 6d23h.)
        if (samples.isEmpty()) return Optional.empty();
        var span = Duration.between(samples.peekFirst().at(), now);
        var minSpan = WINDOW.minus(Duration.ofHours(1));
        if (span.compareTo(minSpan) < 0) return Optional.empty();

        // Discharge-zero gate. Any single discharge in window suppresses detection.
        if (!discharges.isEmpty()) return Optional.empty();

        long awakeCount = 0;
        long elevatedCount = 0;
        for (var s : samples) {
            if (!s.awake()) continue;
            awakeCount++;
            if (s.value() > FRUSTRATION_HIGH) elevatedCount++;
        }
        if (awakeCount == 0) return Optional.empty();

        double fraction = (double) elevatedCount / (double) awakeCount;
        if (fraction <= L1_FRACTION) return Optional.empty();

        Severity sev;
        if (fraction > L3_FRACTION) sev = Severity.LEVEL_3;
        else if (fraction > L2_FRACTION) sev = Severity.LEVEL_2;
        else sev = Severity.LEVEL_1;

        return Optional.of(new ChronicFrustrationDetected(sev, fraction, 0, span, now));
    }

    /** Drop samples and discharges older than {@code now - WINDOW}. */
    public void prune(Instant now) {
        var cutoff = now.minus(WINDOW);
        while (!samples.isEmpty() && samples.peekFirst().at().isBefore(cutoff)) {
            samples.pollFirst();
        }
        while (!discharges.isEmpty() && discharges.peekFirst().at().isBefore(cutoff)) {
            discharges.pollFirst();
        }
    }

    /** Test/debug accessor — current sample count after prune. */
    public int sampleCount() { return samples.size(); }

    /** Test/debug accessor — current discharge count after prune. */
    public int dischargeCount() { return discharges.size(); }

    /** Test/debug accessor — discharge snapshot. */
    public List<FrustrationDischarge> discharges() { return new ArrayList<>(discharges); }

    private static String snippet(String text) {
        if (text == null) return "";
        var t = text.strip();
        return t.length() <= 80 ? t : t.substring(0, 80) + "…";
    }
}

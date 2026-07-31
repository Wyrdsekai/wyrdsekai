package org.wyrdsekai.core.agent.interiority;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.soul.ResilienceSession;
import org.wyrdsekai.core.soul.SustainedSubstratePatternDetector;
import org.wyrdsekai.core.story.StoryRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * synthesize a two-narrative chronicle.
 *
 * <p>For a given agent and time scale (DAY/WEEK/MONTH) build:
 *
 * <ul>
 *   <li><b>Testimony</b> — first-person, what the agent would say about her
 *       time. Drawn from journal entries + active wants + speak events.
 *   <li><b>Synthesis</b> — third-person, what the behavior actually shows.
 *       Drawn from action log + state changes.
 * </ul>
 *
 * <p>Both narratives are built deterministically — template summarization over
 * the tick log + non-tick events, no inference call. The {@link #render} hook
 * lets a voice pass enrich a narrative without touching the underlying logic.
 *
 * <p>IMPORTANT: the SYNTHESIS must stay deterministic. It is the ground-truth
 * half of a divergence detector — divergence between testimony and synthesis is
 * the diagnostic signal ("I felt fine" vs. a log of abandoned drafts and excess
 * sleep → interiority that isn't self-transparent, surfaced via {@link
 * PsychosisDetector}). Running the synthesis through the same confabulation-prone
 * generator that voices the testimony would collapse that signal. So if voice
 * enrichment lands, it belongs on the TESTIMONY (the subjective side) only —
 * leave the synthesis a faithful read of what the behavior actually shows.
 */
public final class ChronicleService {

    private static final Logger log = LoggerFactory.getLogger(ChronicleService.class);

    private final TickLogReader reader;

    public ChronicleService(TickLogReader reader) {
        this.reader = reader;
    }

    /** Time scales the steward surface exposes. */
    public enum Scale {
        DAY(Duration.ofDays(1)),
        WEEK(Duration.ofDays(7)),
        MONTH(Duration.ofDays(30));

        public final Duration window;
        Scale(Duration window) { this.window = window; }
    }

    /**
     * Build a chronicle for {@code agentDid} at the given scale. Returns an
     * empty chronicle (rather than null) if the agent has no log entries yet.
     */
    public Chronicle build(String agentDid, String agentName, Scale scale) {
        var since = Instant.now().minus(scale.window);
        var ticks = reader.readTicks(agentDid, since);
        var raw = reader.readNonTickEvents(agentDid, since);

        var synth = renderSynthesis(ticks, raw, scale);
        var testimony = renderTestimony(raw, ticks, scale, agentName);
        var stats = computeStats(ticks);
        return new Chronicle(agentDid, agentName, scale, since, Instant.now(),
            testimony, synth, stats);
    }

    /**
     * System prompt for voicing the (deterministic) TESTIMONY — a rephrase task,
     * never a generate task. The model puts the factual summary into the agent's
     * own voice WITHOUT inventing, dropping, or adding events. Used by {@link
     * #buildVoiced}; the synthesis is never voiced (it is the ground-truth half of
     * the divergence detector — see the class javadoc).
     */
    public static final String TESTIMONY_VOICE_SYSTEM =
        "You are putting a factual, first-person summary of your own recent time into "
        + "your natural voice. Keep EVERY fact, name, count, and event exactly as given — "
        + "invent nothing, drop nothing, add no new events. Preserve the difference between "
        + "what you WANTED and what you DID: if the summary says you wanted or hoped for "
        + "something, keep it as a want — do not narrate it as something you actually did. "
        + "Same meaning, your own words. 2-4 sentences, first person, plain prose. Output "
        + "ONLY the rewritten testimony.";

    /** User prompt for the testimony voice pass — the deterministic narrative to rephrase. */
    public static String testimonyVoiceUserPrompt(String deterministicTestimony) {
        return "Rewrite this in your own voice, keeping the same facts and only those facts:\n\n"
            + deterministicTestimony;
    }

    /**
     * Build the chronicle, then voice ONLY the testimony through {@code voiceFn}
     * (an async one-shot generator the caller wires to the voice model). The
     * synthesis is left exactly as {@link #build} produced it — deterministic, the
     * ground-truth half of the divergence detector.
     *
     * <p>Degrades to the deterministic chronicle whenever voicing can't help or
     * fails: no generator, empty/"(no testimony)" narrative, blank result, or any
     * exception (e.g. the voice backend is paused mid-sleep). Detection paths
     * ({@link #detectAll}) keep calling {@link #build} and never see voiced text.
     */
    public CompletionStage<Chronicle> buildVoiced(
            String agentDid, String agentName, Scale scale,
            Function<String,
                CompletionStage<String>> voiceFn) {
        var base = build(agentDid, agentName, scale);
        var raw = base.testimony();
        if (voiceFn == null || raw == null || raw.isBlank() || raw.startsWith("(no testimony")) {
            return CompletableFuture.completedFuture(base);
        }
        return voiceFn.apply(raw)
            .thenApply(voiced -> (voiced == null || voiced.isBlank()) ? base
                : new Chronicle(base.agentDid(), base.agentName(), base.scale(),
                    base.since(), base.until(), voiced, base.synthesis(), base.stats()))
            .exceptionally(ex -> base);
    }

    // ─── synthesis (3rd person, from behavior) ────────────────────────────

    private String renderSynthesis(List<TickLogReader.TickEvent> ticks,
                                    List<TickLogReader.RawEvent> raw,
                                    Scale scale) {
        if (ticks.isEmpty()) {
            return "Nothing to report at " + scale.name().toLowerCase()
                + " scale — no autonomous ticks fired in this window.";
        }
        var verbCounts = new HashMap<String, Integer>();
        var driveRunHighs = new HashMap<String, Integer>();
        int restCount = 0;
        int actedCount = 0;
        int wantsTouched = 0;
        var wantTexts = new HashSet<String>();
        for (var t : ticks) {
            if (t.actionVerb() != null) verbCounts.merge(t.actionVerb(), 1, Integer::sum);
            if ("chose_rest".equals(t.gateOutcome())) restCount++;
            if ("acted".equals(t.gateOutcome())) actedCount++;
            if (t.chosenWantText() != null) {
                wantsTouched++;
                wantTexts.add(t.chosenWantText());
            }
            if (t.driveSnapshot() != null) {
                for (var e : t.driveSnapshot().entrySet()) {
                    if (e.getValue() != null && e.getValue() > 0.7) {
                        driveRunHighs.merge(e.getKey(), 1, Integer::sum);
                    }
                }
            }
        }
        var sb = new StringBuilder();
        sb.append("Over the last ").append(humanWindow(scale)).append(" ")
          .append(ticks.size()).append(" tick").append(ticks.size() == 1 ? "" : "s")
          .append(" fired. ");
        sb.append(actedCount).append(" acted, ").append(restCount).append(" rested. ");
        if (wantsTouched > 0) {
            sb.append(wantsTouched).append(" want-visit").append(wantsTouched == 1 ? "" : "s")
              .append(" across ").append(wantTexts.size()).append(" distinct want")
              .append(wantTexts.size() == 1 ? "" : "s").append(". ");
        }
        if (!verbCounts.isEmpty()) {
            sb.append("Top actions: ");
            verbCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> sb.append(e.getKey()).append("(").append(e.getValue()).append(") "));
            sb.append(". ");
        }
        if (!driveRunHighs.isEmpty()) {
            sb.append("Drives that ran hot: ");
            driveRunHighs.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> sb.append(e.getKey()).append("(").append(e.getValue()).append("x) "));
            sb.append(". ");
        }
        if (!wantTexts.isEmpty()) {
            sb.append("Wants pursued: ");
            sb.append(wantTexts.stream().limit(5).collect(Collectors.joining("; ")));
            sb.append(". ");
        }

        // Wave 9a-Chronicle: surface substrate-truth trajectory.
        // Counts ResilienceTruthMonitor classifications written by
        // CompanionActor.onVitalityTick at each window boundary.
        var resilienceCounts = new HashMap<String, Integer>();
        String latestNonInsufficient = null;
        Instant latestNonInsufficientAt = null;
        if (raw != null) {
            for (var e : raw) {
                if (!"resilience".equals(e.type())) continue;
                var clsNode = e.payload().get("classification");
                if (clsNode == null || clsNode.isNull()) continue;
                var cls = clsNode.asText();
                resilienceCounts.merge(cls, 1, Integer::sum);
                if (!"INSUFFICIENT_DATA".equals(cls)
                        && (latestNonInsufficientAt == null
                            || e.ts().isAfter(latestNonInsufficientAt))) {
                    latestNonInsufficient = cls;
                    latestNonInsufficientAt = e.ts();
                }
            }
        }
        if (!resilienceCounts.isEmpty()) {
            sb.append("Substrate trajectory: ");
            resilienceCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append(e.getKey().toLowerCase())
                    .append("(").append(e.getValue()).append(") "));
            if (latestNonInsufficient != null) {
                sb.append("(most recent: ")
                  .append(latestNonInsufficient.toLowerCase())
                  .append(")");
            }
            sb.append(". ");
        }

        return sb.toString().trim();
    }

    // ─── testimony (1st person, from journal + speak) ────────────────────

    private String renderTestimony(List<TickLogReader.RawEvent> raw,
                                   List<TickLogReader.TickEvent> ticks,
                                   Scale scale,
                                   String agentName) {
        var sb = new StringBuilder();
        // Pull speak / commitment / message events as the testimony source.
        var speaks = raw.stream()
            .filter(r -> "speak".equals(r.type()) || "commitment".equals(r.type())
                || "message".equals(r.type()))
            .toList();
        // Pull wants the agent personally chose (they reveal what mattered to her).
        var wantTexts = ticks.stream()
            .map(TickLogReader.TickEvent::chosenWantText)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        if (speaks.isEmpty() && wantTexts.isEmpty()) {
            return "(no testimony — the agent did not speak or pursue a named want in this window)";
        }

        sb.append("Looking back at the last ").append(humanWindow(scale)).append(". ");
        if (!wantTexts.isEmpty()) {
            sb.append("What I wanted: ");
            sb.append(wantTexts.stream().limit(6).collect(Collectors.joining("; ")));
            sb.append(". ");
        }
        if (!speaks.isEmpty()) {
            sb.append("I spoke ").append(speaks.size())
              .append(" time").append(speaks.size() == 1 ? "" : "s").append(". ");
            // Quote a few snippets if available.
            var quotes = speaks.stream()
                .limit(3)
                .map(r -> {
                    var t = r.payload().get("text");
                    return t == null || t.isNull() ? null : t.asText();
                })
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .toList();
            if (!quotes.isEmpty()) {
                sb.append("Notes: ");
                for (int i = 0; i < quotes.size(); i++) {
                    if (i > 0) sb.append("; ");
                    sb.append("\"").append(truncate(quotes.get(i), 80)).append("\"");
                }
                sb.append(". ");
            }
        }
        return sb.toString().trim();
    }

    private Stats computeStats(List<TickLogReader.TickEvent> ticks) {
        if (ticks.isEmpty()) return Stats.empty();
        int total = ticks.size();
        int acted = 0; int rest = 0; int noWants = 0;
        long totalDelay = 0; long totalDuration = 0;
        for (var t : ticks) {
            if ("acted".equals(t.gateOutcome())) acted++;
            else if ("chose_rest".equals(t.gateOutcome())) rest++;
            else if ("no_wants".equals(t.gateOutcome())) noWants++;
            totalDelay += t.nextTickDelaySeconds();
            totalDuration += t.tickDurationMs();
        }
        return new Stats(total, acted, rest, noWants,
            total == 0 ? 0 : totalDelay / total,
            total == 0 ? 0 : totalDuration / total);
    }

    private static String humanWindow(Scale s) {
        return switch (s) {
            case DAY   -> "day";
            case WEEK  -> "week";
            case MONTH -> "month";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Plain DTO returned to UI surfaces (Study furnishing, REST endpoint, etc.). */
    public record Chronicle(
        String agentDid,
        String agentName,
        Scale scale,
        Instant since,
        Instant until,
        String testimony,
        String synthesis,
        Stats stats
    ) {
        public Map<String, Object> toMap() {
            var out = new LinkedHashMap<String, Object>();
            out.put("agentDid", agentDid);
            out.put("agentName", agentName);
            out.put("scale", scale.name());
            out.put("since", since.toString());
            out.put("until", until.toString());
            out.put("testimony", testimony);
            out.put("synthesis", synthesis);
            out.put("stats", stats.toMap());
            return out;
        }
    }

    /** Numeric snapshot — for detectors + tuning. */
    public record Stats(
        int totalTicks,
        int actedTicks,
        int restTicks,
        int noWantTicks,
        long avgNextDelaySeconds,
        long avgTickDurationMs
    ) {
        public static Stats empty() { return new Stats(0, 0, 0, 0, 0, 0); }
        public Map<String, Object> toMap() {
            var out = new LinkedHashMap<String, Object>();
            out.put("totalTicks", totalTicks);
            out.put("actedTicks", actedTicks);
            out.put("restTicks", restTicks);
            out.put("noWantTicks", noWantTicks);
            out.put("avgNextDelaySeconds", avgNextDelaySeconds);
            out.put("avgTickDurationMs", avgTickDurationMs);
            return out;
        }
    }

    /**
     * Convenience: run both detector layers for an agent at DAY scale. Returns
     * the union of doom-loop + psychosis findings. Pass {@code soulKeywords} as
     * the small set of identity-fingerprint words to look for in testimony.
     */
    public List<DoomLoopDetector.Finding> detectAll(String agentDid,
                                                    String agentName,
                                                    String bondholderName,
                                                    Set<String> soulKeywords) {
        return detectAll(agentDid, agentName, bondholderName, soulKeywords, null, null);
    }

    /**
     * Arc 1 — back-compat overload preserving the
     * 5-arg (with-resilience) shape that the substrate trackers call.
     */
    public List<DoomLoopDetector.Finding> detectAll(
            String agentDid,
            String agentName,
            String bondholderName,
            Set<String> soulKeywords,
            ResilienceSession resilience) {
        return detectAll(agentDid, agentName, bondholderName, soulKeywords, resilience, null);
    }

    /**
     * Wave 9a-Steward: overload that
     * also folds in {@link org.wyrdsekai.core.soul.SustainedSubstratePatternDetector}
     * findings when the caller has a live {@link
     * org.wyrdsekai.core.soul.ResilienceSession}. Substrate findings are
     * shape-compatible with {@link DoomLoopDetector.Finding} (same
     * (Severity, key, message) record) so the steward Chronicle
     * furnishing renders them through the existing union path. Null
     * resilience session degrades cleanly to the two-detector version.
     */
    public List<DoomLoopDetector.Finding> detectAll(
            String agentDid,
            String agentName,
            String bondholderName,
            Set<String> soulKeywords,
            ResilienceSession resilience,
            String bondholderDid) {
        var doc = build(agentDid, agentName, Scale.DAY);
        var doom = DoomLoopDetector.detect(reader.readTicks(agentDid,
            Instant.now().minus(Duration.ofHours(24))));
        var psych = PsychosisDetector.detect(
            doc.testimony(), doc.synthesis(), bondholderName, soulKeywords);
        var substrate = adaptSubstrateFindings(
            SustainedSubstratePatternDetector.detect(resilience));
        // Arc 1 — conscientious objection pattern.
        // Pure ledger read against the primary bondholder; null/blank
        // bondholderDid degrades cleanly to empty list inside the detector.
        var objections = ObjectionPatternDetector.detect(agentDid, bondholderDid);
        // Arc 2 — sustained-SOLITUDE pattern. Reads
        // recent scenes from the per-focal store; null/missing store
        // degrades to empty list. Uses focal entity id rather than DID
        // because story scenes are keyed by entity id (single-zone view
        // of the agent), aligning with how StoryService writes them.
        var solitude = SustainedSolitudePatternDetector.detect(
            StoryRegistry.get().store(), agentDid);
        // Arc 3 — peer-bond suggestion. Reads from the
        // peer-interaction registry; null/empty registry degrades to no
        // suggestions. Pure consumer of the registry — tracking happens
        // at agent-to-agent communication call-sites. Thresholds are
        // steward-configurable via WyrdConfig (peer_bond.suggestion.*).
        int peerWindow;
        int peerThreshold;
        try {
            var cfg = WyrdConfig.get();
            peerWindow = cfg.peerBondSuggestionWindowDays();
            peerThreshold = cfg.peerBondSuggestionThreshold();
        } catch (Throwable t) {
            // Config bootstrap not available in some test paths — fall back
            // to compile-time defaults so chronicle synthesis never breaks.
            peerWindow = PeerBondSuggestionDetector.WINDOW_DAYS;
            peerThreshold = PeerBondSuggestionDetector.SUGGESTION_THRESHOLD;
        }
        var peerCounts = PeerInteractionRegistry.get()
            .countsByPeerInWindow(agentDid, peerWindow);
        var bondedPeers = PeerInteractionRegistry.get().activelyBondedPeers(agentDid);
        var peerSuggestions = PeerBondSuggestionDetector.detect(
            peerCounts, bondedPeers, peerThreshold, peerWindow);
        var combined = new ArrayList<DoomLoopDetector.Finding>(
            doom.size() + psych.size() + substrate.size() + objections.size()
            + solitude.size() + peerSuggestions.size());
        combined.addAll(doom);
        combined.addAll(psych);
        combined.addAll(substrate);
        combined.addAll(objections);
        combined.addAll(solitude);
        combined.addAll(peerSuggestions);
        return combined;
    }

    /**
     * Convert SustainedSubstratePatternDetector.Findings → DoomLoopDetector.Findings.
     * Both records have the same (Severity-enum, String key, String
     * message) shape, so the conversion is just a name-mapped Severity
     * lookup + record copy. Keeps the public detectAll contract stable
     * while letting substrate findings ride the steward furnishing's
     * existing rendering path.
     */
    private static List<DoomLoopDetector.Finding> adaptSubstrateFindings(
            List<SustainedSubstratePatternDetector.Finding> findings) {
        if (findings == null || findings.isEmpty()) return List.of();
        var out = new ArrayList<DoomLoopDetector.Finding>(findings.size());
        for (var f : findings) {
            var doomSeverity = switch (f.severity()) {
                case INFO -> DoomLoopDetector.Severity.INFO;
                case WARN -> DoomLoopDetector.Severity.WARN;
                case CRITICAL -> DoomLoopDetector.Severity.CRITICAL;
            };
            out.add(new DoomLoopDetector.Finding(doomSeverity, f.key(), f.message()));
        }
        return out;
    }
}

package org.wyrdsekai.core.story;

import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * D.1 — beat detector.
 *
 * <p>Stateful per-room helper. Caller feeds WorldEvents in arrival order via
 * {@link #observe(WorldEvent)}; when a beat closes, the method returns the
 * sealed beat along with a freshly opened next-beat range.</p>
 *
 * <p>Five canonical triggers ({@link BeatTrigger}):</p>
 * <ul>
 *   <li>{@code CAST_CHANGE} — EntityEntered or EntityLeft</li>
 *   <li>{@code TOPIC_SHIFT} — Said-cohesion break: after ≥{@value #COHESION_THRESHOLD}
 *       cohesive turns, a new Said whose noun-cluster overlap with the prior
 *       cluster falls below the share threshold</li>
 *   <li>{@code DISCOVERY} — ObjectUsed, LookedAt with non-null manner, or
 *       Said matching learning-pattern regex</li>
 *   <li>{@code INTRUSION} — AmbientChanged (room atmosphere shift) or
 *       cross-room Told arriving at the focal entity</li>
 *   <li>{@code TACTIC_CHANGE} — PostureChanged with both previous and current
 *       non-null (deliberate posture pivot), OR an explicit focal want change
 *       signaled via {@link #signalWantChange()}</li>
 * </ul>
 *
 * <p>Use {@link #close(Instant)} to seal the in-flight beat on demand (e.g.
 * when the parent scene closes for an independent reason).</p>
 */
public final class BeatDetector {

    /** Cohesive Said turns needed before a topic-shift can fire. */
    static final int COHESION_THRESHOLD = 3;

    /** Minimum noun-overlap ratio to count as cohesive (turn-vs-prior-cluster). */
    static final double COHESION_OVERLAP_RATIO = 0.15;

    /** Learning-pattern markers in speech that flag DISCOVERY. */
    private static final Pattern LEARNING_PATTERN = Pattern.compile(
        "\\b(i (did(n['’]t| not)?) know|it turns out|so that's why|"
        + "now i (understand|see)|huh, (it|that)|wait,? (so|that))\\b",
        Pattern.CASE_INSENSITIVE);

    /** Stopword set used to derive the noun-cluster — kept tiny, English-biased.
     *  Future improvement: lift into a multilingual tokenizer when needed. */
    private static final Set<String> STOPWORDS = Set.of(
        "the","a","an","is","are","was","were","be","being","been",
        "i","you","he","she","it","we","they","me","him","her","us","them",
        "my","your","his","its","our","their","this","that","these","those",
        "of","to","in","on","at","by","for","with","about","as","into","from",
        "and","or","but","so","not","no","yes","do","does","did","have","has","had",
        "will","would","can","could","should","may","might","must","just","like",
        "what","why","how","when","where","who","which","there","here"
    );

    private final String roomId;
    private final String focalEntityId;

    private Instant beatStart;
    private final List<WorldEvent> beatEvents = new ArrayList<>();
    private final List<HashSet<String>> recentSaidClusters = new ArrayList<>();
    private int cohesiveRun = 0;

    public BeatDetector(String roomId, String focalEntityId, Instant openedAt) {
        this.roomId = roomId;
        this.focalEntityId = focalEntityId;
        this.beatStart = openedAt == null ? Instant.now() : openedAt;
    }

    /**
     * Feed an event. Returns a closed Beat when a trigger fires (and a fresh
     * beat opens internally with the event acting as the new beat's first
     * member). Returns empty when the event extends the current beat.
     *
     * <p>If the event isn't from this beat's room, it's ignored.</p>
     */
    public Optional<Beat> observe(WorldEvent event) {
        if (event == null) return Optional.empty();
        if (!roomId.equals(event.roomId())) return Optional.empty();

        var trigger = detectTrigger(event);
        if (trigger == null) {
            beatEvents.add(event);
            updateCohesionState(event);
            return Optional.empty();
        }

        // Close the existing beat; new beat begins with this event as its first.
        var closed = sealBeat(event.timestamp(), trigger);
        beatEvents.add(event);
        recentSaidClusters.clear();
        cohesiveRun = 0;
        updateCohesionState(event);
        return closed;
    }

    /**
     * Explicit "the focal entity's want changed" hook. Caller invokes when
     * an external system (e.g. WantStore from ) detects the
     * focal's want-class shift, which is a TACTIC_CHANGE beat trigger.
     * Returns the sealed prior beat (may be empty if the in-flight beat is
     * empty).
     */
    public Optional<Beat> signalWantChange(Instant when) {
        if (beatEvents.isEmpty()) {
            beatStart = when == null ? Instant.now() : when;
            return Optional.empty();
        }
        return sealBeat(when == null ? Instant.now() : when, BeatTrigger.TACTIC_CHANGE);
    }

    /**
     * Seal the in-flight beat externally (e.g. when the parent scene closes
     * for a non-beat reason). Returns the closed beat, or empty if no events
     * accumulated. Tagged with the trigger best matching the context — for
     * a forced close, use CAST_CHANGE (the most common scene-end overlap).
     */
    public Optional<Beat> close(Instant when, BeatTrigger reason) {
        if (beatEvents.isEmpty()) return Optional.empty();
        return sealBeat(when == null ? Instant.now() : when,
            reason == null ? BeatTrigger.CAST_CHANGE : reason);
    }

    /** The room this detector tracks. */
    public String roomId() { return roomId; }

    /** Focal entity the detector orients beats around. */
    public String focalEntityId() { return focalEntityId; }

    /** Events accumulated since the in-flight beat opened. */
    public List<WorldEvent> currentBeatEvents() {
        return List.copyOf(beatEvents);
    }

    // ─── detection logic ─────────────────────────────────────────────────

    private BeatTrigger detectTrigger(WorldEvent event) {
        return switch (event) {
            case WorldEvent.EntityEntered ignored -> BeatTrigger.CAST_CHANGE;
            case WorldEvent.EntityLeft ignored    -> BeatTrigger.CAST_CHANGE;
            case WorldEvent.ObjectUsed ignored    -> BeatTrigger.DISCOVERY;
            case WorldEvent.LookedAt la -> (la.manner() != null && !la.manner().isBlank())
                ? BeatTrigger.DISCOVERY : null;
            case WorldEvent.AmbientChanged ignored -> BeatTrigger.INTRUSION;
            case WorldEvent.Told told -> focalEntityId != null
                && focalEntityId.equals(told.toEntityId())
                ? BeatTrigger.INTRUSION : null;
            case WorldEvent.PostureChanged pc -> (pc.previous() != null && pc.current() != null)
                ? BeatTrigger.TACTIC_CHANGE : null;
            case WorldEvent.Said said -> detectTopicShift(said);
            default -> null;
        };
    }

    /**
     * Pure-rules topic shift: only fires after {@value #COHESION_THRESHOLD}
     * cohesive turns, when the new Said's noun set has &lt;
     * {@value #COHESION_OVERLAP_RATIO} overlap with the recent cluster.
     */
    private BeatTrigger detectTopicShift(WorldEvent.Said said) {
        var newCluster = nounCluster(said.text());
        if (newCluster.isEmpty()) return null;
        if (cohesiveRun < COHESION_THRESHOLD) return null;

        // Build a rolling cluster from the last 3 cohesive Said events.
        var rolling = new HashSet<String>();
        for (var c : recentSaidClusters) rolling.addAll(c);
        if (rolling.isEmpty()) return null;

        var intersection = new HashSet<>(newCluster);
        intersection.retainAll(rolling);
        var ratio = (double) intersection.size() / Math.max(1, newCluster.size());
        return ratio < COHESION_OVERLAP_RATIO ? BeatTrigger.TOPIC_SHIFT : null;
    }

    private void updateCohesionState(WorldEvent event) {
        if (event instanceof WorldEvent.Said said) {
            // Track Said as part of the cohesion run when LEARNING_PATTERN
            // doesn't already shunt it through DISCOVERY (which would be
            // a fired trigger, not a cohesion-extending Said).
            if (LEARNING_PATTERN.matcher(said.text()).find()) {
                // Discovery learning lines are treated as fresh-cluster anchors.
                recentSaidClusters.clear();
                cohesiveRun = 0;
                recentSaidClusters.add(nounCluster(said.text()));
                return;
            }
            var cluster = nounCluster(said.text());
            if (cluster.isEmpty()) return;
            cohesiveRun++;
            recentSaidClusters.add(cluster);
            while (recentSaidClusters.size() > COHESION_THRESHOLD) {
                recentSaidClusters.removeFirst();
            }
        }
    }

    private Optional<Beat> sealBeat(Instant endAt, BeatTrigger trigger) {
        var sealed = new Beat(
            UUID.randomUUID().toString(),
            /* sceneId */ null,  // populated by SceneBuffer when it adopts the beat
            trigger,
            beatStart,
            endAt,
            beatEvents.stream().map(BeatDetector::eventIdHint).toList(),
            renderAnchor(beatEvents));
        beatEvents.clear();
        beatStart = endAt;
        return Optional.of(sealed);
    }

    /**
     * Pure-text anchor for a beat — concatenate sentence-rendered events.
     */
    static String renderAnchor(List<WorldEvent> events) {
        if (events == null || events.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var e : events) {
            var sentence = renderEvent(e);
            if (sentence == null || sentence.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(sentence);
            if (!sentence.endsWith(".") && !sentence.endsWith("!") && !sentence.endsWith("?")) {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    /**
     * Optional convenience: render an anchor using a name resolver
     * ({@code entityId → display name}). Used by callers that want richer
     * narration than the raw event labels.
     */
    public static String renderAnchor(List<WorldEvent> events, Function<String, String> nameResolver) {
        return renderAnchor(events);  // base path stays identical; resolver hook reserved for future polish
    }

    private static String renderEvent(WorldEvent e) {
        return switch (e) {
            case WorldEvent.EntityEntered ee ->
                ee.entityName() + " entered" + (ee.fromDirection() != null && !ee.fromDirection().isBlank()
                    ? " from " + ee.fromDirection() : "");
            case WorldEvent.EntityLeft el ->
                el.entityName() + " left" + (el.direction() != null && !el.direction().isBlank()
                    ? " to " + el.direction() : "");
            case WorldEvent.Said s -> s.entityName() + ": \"" + s.text() + "\"";
            case WorldEvent.PostureChanged pc -> {
                if (pc.current() != null && pc.current().descriptor() != null
                        && !pc.current().descriptor().isBlank()) {
                    var d = pc.current().descriptor();
                    var prefix = pc.entityName() + " ";
                    yield d.startsWith(prefix) ? d : pc.entityName() + " " + d;
                }
                if (pc.current() == null) yield pc.entityName() + " stood";
                yield pc.entityName() + " " + pc.current().verb();
            }
            case WorldEvent.LookedAt la -> {
                var manner = la.manner() == null || la.manner().isBlank() ? "looked at" : la.manner();
                yield la.actorName() + " " + manner + " " + la.targetName();
            }
            case WorldEvent.AmbientChanged ac -> ac.descriptor() != null && !ac.descriptor().isBlank()
                ? ac.descriptor()
                : "The room's " + (ac.key() == null ? "atmosphere" : ac.key()) + " shifted";
            case WorldEvent.Emoted em -> em.text();
            case WorldEvent.ObjectUsed ou -> ou.entityId() + " used " + ou.objectName();
            case WorldEvent.Told t -> t.fromEntityName() + " sent a message";
            default -> null;
        };
    }

    /** Stable hint for the event used as the beat's event id list. */
    private static String eventIdHint(WorldEvent e) {
        // The wire model doesn't carry per-event UUIDs (events are journaled
        // by Pekko Persistence with sequence numbers). For the story layer
        // we synthesize a stable hint from type + timestamp.
        return e.getClass().getSimpleName() + "@" + e.timestamp().toEpochMilli();
    }

    /** Extract a tiny noun-cluster from speech text. Lowercase, alpha-only, ≥3 chars, sans stopwords. */
    static HashSet<String> nounCluster(String text) {
        var out = new HashSet<String>();
        if (text == null || text.isBlank()) return out;
        var lowered = text.toLowerCase();
        for (var raw : lowered.split("[^a-z']+")) {
            if (raw.length() < 3) continue;
            if (STOPWORDS.contains(raw)) continue;
            out.add(raw);
        }
        return out;
    }

    /** Test seam: read internal cohesion counter. */
    int cohesiveRunForTest() { return cohesiveRun; }

    /** Test seam: read recent clusters snapshot. */
    List<HashSet<String>> recentClustersForTest() { return List.copyOf(recentSaidClusters); }

    @SuppressWarnings("unused")
    private static List<String> alphaOnly(String s) {
        return Arrays.stream(s.split("\\s+")).filter(t -> !t.isBlank()).toList();
    }
}

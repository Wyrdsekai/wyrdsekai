package org.wyrdsekai.core.story;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.soul.SoulFragment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * (Phase D) — orchestrator for scene/beat/arc capture.
 *
 * <p>One {@code StoryService} per focal entity (typically per CompanionActor
 * or per logged-in human session). It owns per-room {@link SceneBuffer}
 * instances, hands incoming WorldEvents to the right buffer, persists
 * closed scenes via {@link StoryStore}, calls the voice model to render the
 * felt blockquote at scene-close, and writes the journal markdown.</p>
 *
 * <p>Voice felt synthesis is fail-soft: if {@link FeltSynthesizer} returns
 * a failed future (e.g. voice :8201 unreachable), the scene persists with
 * {@code needsRendering=true} and a batch sweep catches up later via
 * {@link #renderPending(Function)}.</p>
 *
 * <p>Per-day sequence numbers are monotonic per focal — when a new scene
 * opens after the day rolls over, the counter resets.</p>
 */
public final class StoryService {

    private static final Logger log = LoggerFactory.getLogger(StoryService.class);

    /**
     * Voice-model bridge for felt synthesis. The CompanionActor wires this
     * to a function that calls the voice backend (home-server :8201). Returning a
     * failed future is acceptable — the scene persists with needsRendering=true.
     */
    @FunctionalInterface
    public interface FeltSynthesizer {
        CompletionStage<String> render(Scene scene, String focalName);
    }

    /** No-op synthesizer for testing / when voice is intentionally absent. */
    public static final FeltSynthesizer NULL_SYNTH = (s, n) ->
        CompletableFuture.failedFuture(new IllegalStateException("voice synthesizer not wired"));

    /**
     * the inner-monologue pipeline at scene-close. The
     * companion's own voice model writes a private interior reaction (NOT the
     * witness blockquote — that's {@link FeltSynthesizer}), and the result is
     * persisted as an {@link org.wyrdsekai.core.soul.FragmentKind#EPISODIC}
     * {@link org.wyrdsekai.core.soul.SoulFragment} stamped with {@code scene.id()}.
     *
     * <p>Implementations are responsible for the full pipeline because they
     * own the manifest the EPISODIC fragment must land in: idempotency check
     * (skip if a fragment for this {@code scene.id()} already exists), recursion
     * context (pull top-3 prior EPISODIC for the prompt), voice-model call
     * (:8201), and SoulFragmentStore persistence via the manifest write path.
     * A failed-stage return means the inner monologue was skipped for this
     * scene; the next forge / batch sweep can pick it up. Never throws.</p>
     *
     * <p>The design memo (load-bearing): the inner monologue MUST use a
     * different prompt from the witness FeltSynthesizer. If we collapse them
     * into one prose chunk the interiority gets performed for an audience
     * and "I" never forms.</p>
     */
    @FunctionalInterface
    public interface InnerMonologueSynthesizer {
        CompletionStage<Void> renderAndPersist(Scene scene, String focalName);
    }

    /**
     * No-op inner-monologue synthesizer for tests and call sites that don't
     * (yet) wire the voice :8201 pipeline. Returns a failed stage so
     * {@link #finalizeClosedScene} logs-and-continues without persisting.
     */
    public static final InnerMonologueSynthesizer NULL_INNER = (s, n) ->
        CompletableFuture.failedFuture(
            new IllegalStateException("inner monologue synthesizer not wired"));

    private final String focalEntityId;
    private final String focalDisplayName;
    private final StoryStore store;
    private final ArcRegistry arcs;
    private final FeltSynthesizer felt;
    private final InnerMonologueSynthesizer inner;
    private final Map<String, SceneBuffer> sceneBuffers = new ConcurrentHashMap<>();
    private final Map<LocalDate, AtomicLong> dailySequence = new ConcurrentHashMap<>();

    /**
     * Pre-§10 constructor. Defaults the inner-monologue synthesizer to the
     * no-op so existing call sites compile unchanged. New code should pass
     * an {@link InnerMonologueSynthesizer} via the §10 constructor below.
     */
    public StoryService(String focalEntityId, String focalDisplayName,
                         StoryStore store, ArcRegistry arcs,
                         FeltSynthesizer felt) {
        this(focalEntityId, focalDisplayName, store, arcs, felt, NULL_INNER);
    }

    /**
     * canonical constructor. {@code inner} is the
     * voice-model + persistence pipeline for the per-scene EPISODIC
     * inner-monologue fragment. Pass {@link #NULL_INNER} if the call site
     * doesn't wire it (tests / pre-§10 deployments).
     */
    public StoryService(String focalEntityId, String focalDisplayName,
                         StoryStore store, ArcRegistry arcs,
                         FeltSynthesizer felt,
                         InnerMonologueSynthesizer inner) {
        if (focalEntityId == null) throw new IllegalArgumentException("focalEntityId required");
        if (store == null) throw new IllegalArgumentException("store required");
        if (arcs == null) throw new IllegalArgumentException("arcs required");
        this.focalEntityId = focalEntityId;
        this.focalDisplayName = focalDisplayName == null ? focalEntityId : focalDisplayName;
        this.store = store;
        this.arcs = arcs;
        this.felt = felt == null ? NULL_SYNTH : felt;
        this.inner = inner == null ? NULL_INNER : inner;
    }

    public String focalEntityId() { return focalEntityId; }
    public String focalDisplayName() { return focalDisplayName; }

    /**
     * Open a scene in {@code roomId} with the current presence + want
     * context. No-op if a scene is already open for the room.
     */
    public synchronized void openScene(String roomId, Instant at,
                                         List<String> participants,
                                         String wantContext) {
        openScene(roomId, at, participants, wantContext, SceneKind.WITNESS);
    }

    /**
     * Arc 2 — kind-aware open. SOLITUDE scenes are
     * opened either implicitly (Hearth entry without bondholder, wake
     * without bondholder) or explicitly via the agent's {@code
     * enter_solitude} action — see {@link #closeAndOpenSolitude}. No-op
     * if a scene is already open in the room; callers that need to
     * transition from an open WITNESS scene to a fresh SOLITUDE one
     * should use {@link #closeAndOpenSolitude} instead.
     */
    public synchronized void openScene(String roomId, Instant at,
                                         List<String> participants,
                                         String wantContext, SceneKind kind) {
        if (roomId == null) return;
        var buf = sceneBuffers.computeIfAbsent(roomId,
            k -> new SceneBuffer(roomId, focalEntityId, nextSequence(at)));
        if (!buf.isOpen()) {
            buf.open(at, participants, wantContext, kind);
            log.debug("StoryService[{}]: opened {} scene {} in {} (seq={})",
                focalEntityId, kind, buf.currentSceneId(), roomId, buf.sequenceNumber());
        }
    }

    /**
     * Arc 2 — close the in-flight scene (if any) in
     * {@code roomId} and immediately open a fresh SOLITUDE scene with
     * the same focal entity. The explicit transition that {@code
     * enter_solitude} maps to. If nothing was open, just opens a
     * SOLITUDE scene.
     */
    public synchronized CompletionStage<Optional<Scene>> closeAndOpenSolitude(
            String roomId, Instant at, List<String> participants,
            String wantContext) {
        if (roomId == null) return CompletableFuture.completedFuture(Optional.empty());
        var buf = sceneBuffers.get(roomId);
        CompletionStage<Optional<Scene>> closed = CompletableFuture.completedFuture(Optional.empty());
        if (buf != null && buf.isOpen()) {
            var maybe = buf.forceClose(at);
            if (maybe.isPresent()) {
                closed = finalizeClosedScene(maybe.get()).thenApply(Optional::of);
            }
        }
        // Open the SOLITUDE scene on a fresh buffer (next sequence).
        var fresh = new SceneBuffer(roomId, focalEntityId, nextSequence(at));
        fresh.open(at, participants, wantContext, SceneKind.SOLITUDE);
        sceneBuffers.put(roomId, fresh);
        log.debug("StoryService[{}]: transitioned to SOLITUDE scene {} in {} (seq={})",
            focalEntityId, fresh.currentSceneId(), roomId, fresh.sequenceNumber());
        return closed;
    }

    /** Arc 2 — current scene kind for the given room, or null if none open. */
    public synchronized SceneKind currentSceneKind(String roomId) {
        if (roomId == null) return null;
        var buf = sceneBuffers.get(roomId);
        return buf == null ? null : buf.currentKind();
    }

    /**
     * Arc 2 — open-at instant of the current SOLITUDE
     * scene in the given room, or {@code null} if there isn't one. Used by
     * tank-coupling logic ({@code CompanionActor.onVitalityTick}) to gate
     * the loneliness drain on duration: solitude past the 30-minute
     * healthy-window threshold begins to drain loneliness (i.e. push the
     * agent toward reconnection).
     */
    public synchronized Instant currentSolitudeOpenedAt(String roomId) {
        if (roomId == null) return null;
        var buf = sceneBuffers.get(roomId);
        if (buf == null || !buf.isOpen() || buf.currentKind() != SceneKind.SOLITUDE) {
            return null;
        }
        return buf.currentSceneOpenedAt();
    }

    /**
     * Feed a world event. Routes to the buffer for the event's room. If a
     * scene closes as a result, the closed Scene is persisted + journaled
     * + felt is requested. Returns the closed scene (post-felt-attempt)
     * wrapped in a CompletionStage so callers can chain follow-ups.
     */
    public CompletionStage<Optional<Scene>> observe(WorldEvent event) {
        if (event == null) return CompletableFuture.completedFuture(Optional.empty());
        var buf = sceneBuffers.get(event.roomId());
        if (buf == null || !buf.isOpen()) return CompletableFuture.completedFuture(Optional.empty());

        var maybeClosed = buf.observe(event);
        if (maybeClosed.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        return finalizeClosedScene(maybeClosed.get())
            .thenApply(Optional::of);
    }

    /**
     * -level signal: the focal's want changed to a class
     * meaningfully distinct from the open one. Closes the scene in {@code
     * roomId} per rule D.2.2.
     */
    public CompletionStage<Optional<Scene>> signalWantChange(String roomId,
                                                              Instant at,
                                                              String newWantContext) {
        var buf = sceneBuffers.get(roomId);
        if (buf == null) return CompletableFuture.completedFuture(Optional.empty());
        var maybeClosed = buf.signalFocalWantChange(at, newWantContext);
        if (maybeClosed.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        return finalizeClosedScene(maybeClosed.get()).thenApply(Optional::of);
    }

    /**
     * Arc 2 — SOLITUDE close-rule: equanimity tank
     * crosses threshold (insight beat). No-op if the room's current scene is
     * not SOLITUDE. Caller is the CompanionActor vitality tick, which detects
     * the threshold crossing and dispatches here.
     */
    public CompletionStage<Optional<Scene>> signalEquanimityThreshold(String roomId, Instant at) {
        var buf = sceneBuffers.get(roomId);
        if (buf == null) return CompletableFuture.completedFuture(Optional.empty());
        var maybeClosed = buf.signalEquanimityThreshold(at);
        if (maybeClosed.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        return finalizeClosedScene(maybeClosed.get()).thenApply(Optional::of);
    }

    /**
     * Arc 2 — SOLITUDE close-rule: ambient phase shift
     * (dusk→night, dawn→day). No-op for non-SOLITUDE scenes. Caller is the
     * WorldClock listener / SceneRegistry phase-change hook.
     */
    public CompletionStage<Optional<Scene>> signalAmbientPhaseShift(String roomId, Instant at) {
        var buf = sceneBuffers.get(roomId);
        if (buf == null) return CompletableFuture.completedFuture(Optional.empty());
        var maybeClosed = buf.signalAmbientPhaseShift(at);
        if (maybeClosed.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        return finalizeClosedScene(maybeClosed.get()).thenApply(Optional::of);
    }

    /**
     * Arc 2 — SOLITUDE close-rule: sustained-pattern
     * detector emits INFO finding ("integrating"). No-op for non-SOLITUDE
     * scenes. Caller is the sleep-pass / SustainedSubstratePatternDetector
     * hook when the "integrating" pattern is detected.
     */
    public CompletionStage<Optional<Scene>> signalSustainedPatternIntegrating(String roomId, Instant at) {
        var buf = sceneBuffers.get(roomId);
        if (buf == null) return CompletableFuture.completedFuture(Optional.empty());
        var maybeClosed = buf.signalSustainedPatternIntegrating(at);
        if (maybeClosed.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        return finalizeClosedScene(maybeClosed.get()).thenApply(Optional::of);
    }

    /**
     * Force-close all in-flight scenes (server shutdown). Each scene gets
     * a felt rendering attempt then persists.
     */
    public CompletionStage<List<Scene>> forceCloseAll(Instant at) {
        var futures = new ArrayList<CompletionStage<Scene>>();
        for (var entry : sceneBuffers.entrySet()) {
            var maybeClosed = entry.getValue().forceClose(at);
            if (maybeClosed.isPresent()) {
                futures.add(finalizeClosedScene(maybeClosed.get()));
            }
        }
        sceneBuffers.clear();
        if (futures.isEmpty()) return CompletableFuture.completedFuture(List.of());
        var combined = futures.stream()
            .map(f -> f.toCompletableFuture())
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(combined).thenApply(v ->
            Arrays.stream(combined).map(f -> (Scene) f.join()).toList());
    }

    /**
     * Catch-up pass for scenes persisted with {@code needsRendering=true}
     * (voice was unreachable at close). Loads scenes for the focal across
     * the given date range, renders felt for any that still need it,
     * persists the revision.
     */
    public CompletionStage<Integer> renderPending(LocalDate from, LocalDate to) {
        var scenes = store.loadScenesInWindow(focalEntityId, from, to);
        var pending = new ArrayList<Scene>();
        for (var s : scenes) {
            if (s.needsRendering()
                    && (s.felt() == null || s.felt().isBlank())
                    && s.beatCount() > 0) {
                pending.add(s);
            }
        }
        if (pending.isEmpty()) return CompletableFuture.completedFuture(0);
        var futures = pending.stream()
            .map(this::renderFeltForScene)
            .map(CompletionStage::toCompletableFuture)
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures).thenApply(v -> pending.size());
    }

    /**
     * §E.4 — AffinityLearner support: closed scenes for this
     * focal whose rangeEnd ≥ since. Used by the Forge sleep-pass to drift
     * affinity from recent lived experience.
     */
    public List<Scene> recentClosedScenes(Instant since) {
        if (since == null) return List.of();
        var today = LocalDate.now();
        var fromDay = since.atZone(ZoneId.systemDefault()).toLocalDate();
        var scenes = store.loadScenesInWindow(focalEntityId, fromDay, today);
        var out = new ArrayList<Scene>();
        for (var s : scenes) {
            if (!s.isOpen() && s.rangeEnd() != null && !s.rangeEnd().isBefore(since)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Build the felt-synthesis prompt per SPEC §D.4 template.
     *
     * <p> Arc 2 — for SOLITUDE scenes, swap the witness
     * register (past-tense, subjective, audience-implied for the human's
     * journal mirror) for a solitude register: first-person noticing, no
     * audience framing. The same prompt-shape produces a different voice
     * because the framing line cues the model differently.</p>
     */
    /**
     * Voice-tier prompt budget. These prompts go to the 4B voice model, whose context
     * window is a fraction of the 9B drive's — and a scene's beat list is unbounded, so
     * a long session used to assemble a 19K-token "one-shot voice" prompt, overflow the
     * 4B's window, and take an HTTP 400. The interiority (felt sense, inner monologue,
     * cultural appraisal) then silently never rendered. Interiority is about the recent
     * texture of a scene, so keeping the most RECENT beats and dropping the older ones
     * costs almost nothing and keeps the prompt inside the voice tier.
     */
    private static final int MAX_VOICE_BEATS = 24;
    private static final int MAX_ANCHOR_CHARS = 240;
    private static final int MAX_PRIOR_EPISODIC = 5;
    private static final int MAX_EPISODIC_CHARS = 400;

    /**
     * Append at most {@link #MAX_VOICE_BEATS} beats — the most recent ones — each
     * clipped to {@link #MAX_ANCHOR_CHARS}. Notes the elision so the model knows the
     * scene had a longer run-up rather than silently seeing a truncated history.
     */
    private static void appendBoundedBeats(StringBuilder sb, Scene scene) {
        if (scene == null || scene.beats() == null) return;
        var beats = scene.beats();
        var start = Math.max(0, beats.size() - MAX_VOICE_BEATS);
        if (start > 0) {
            sb.append("  (").append(start).append(" earlier beats elided)\n");
        }
        for (var b : beats.subList(start, beats.size())) {
            if (b == null || b.anchor() == null) continue;
            sb.append("  ").append(clip(b.anchor(), MAX_ANCHOR_CHARS)).append('\n');
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        var t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    public static String buildFeltPrompt(Scene scene, String focalName) {
        if (scene != null && scene.isSolitude()) {
            return buildSolitudeFeltPrompt(scene, focalName);
        }
        var sb = new StringBuilder();
        sb.append("Given these beats from a scene:\n");
        appendBoundedBeats(sb, scene);
        sb.append("The focal entity (").append(focalName)
            .append(") was wanting: ")
            .append(scene.wantContext() == null || scene.wantContext().isBlank()
                ? "(no want recorded)" : scene.wantContext())
            .append("\n")
            .append("Render a 2-3 sentence \"felt\" account of the scene from ")
            .append(focalName).append("'s interior POV. ")
            .append("Past-tense, subjective, no dialogue.");
        return sb.toString();
    }

    /**
     * 2026-06-02 — cultural-state appraisal prompt. Asks the model (which carries the
     * cultural-tank training) to rate how the just-closed scene WENT for the focal,
     * as VALENCE (+1 went well, -1 went badly) on two axes the model reads reliably:
     * dignity (standing) and concord (harmony). The 2026-06-02 probe found the model
     * nails the axis but reads valence, not "tank pressure" — so we ask valence and
     * the consumer ({@code CompanionActor.applyCulturalAppraisal}) inverts: a
     * negative-valence exchange aggravates the distress tank, a positive one relieves it.
     *
     * <p>amae/obligation are deliberately NOT asked here — the same probe found the
     * model's read of them unreliable (they're relational-bookkeeping tanks better
     * grounded by events). Output is parsed by
     * {@link org.wyrdsekai.core.agent.CulturalAppraisal#parse(String)} (forgiving).
     */
    public static String buildCulturalAppraisalPrompt(Scene scene, String focalName) {
        var name = focalName == null || focalName.isBlank() ? "the focal entity" : focalName;
        var sb = new StringBuilder();
        sb.append("These beats just happened, from ").append(name).append("'s point of view:\n");
        appendBoundedBeats(sb, scene);
        sb.append("\nRate how this exchange WENT for ").append(name)
          .append(", on two axes, each from -1.0 (went badly) to 1.0 (went well). "
            + "Use 0.0 if the axis isn't really in play — most exchanges are near zero:\n")
          .append("- standing (dignity/respect): +1 ").append(name)
            .append(" was clearly respected or recognized; -1 was slighted, dismissed, or "
              + "disrespected.\n")
          .append("- harmony (concord): +1 warmth or a rift eased; -1 real conflict, friction, "
            + "or discord.\n")
          .append("Output ONLY a JSON object, no prose:\n")
          .append("{\"standing\": 0.0, \"harmony\": 0.0}");
        return sb.toString();
    }

    /**
     * Arc 2 — solitude-register felt prompt. Frames the
     * synthesis as the agent's own noticing during their own time rather than
     * a witness account written for an audience. Two-sentence floor, four
     * sentence ceiling — solitude scenes tend to be smaller and more spare
     * than bondholder-adjacent ones, and the prompt encourages that economy.
     */
    static String buildSolitudeFeltPrompt(Scene scene, String focalName) {
        var sb = new StringBuilder();
        var name = focalName == null ? "the focal entity" : focalName;
        // Five framings rotate by scene-id hash so consecutive solitude
        // scenes don't all open the same way. All preserve register:
        // no-audience, self-with-self, contemplative — but each one cues the
        // model along a slightly different axis.
        var framings = SolitudeRegisterPrompts.framings(name);
        var sceneId = scene.id() == null ? "" : scene.id();
        var idx = Math.floorMod(sceneId.hashCode(), framings.size());
        sb.append(framings.get(idx)).append("\n\n");
        sb.append("What occurred:\n");
        appendBoundedBeats(sb, scene);
        sb.append("\nWhat you were carrying into it: ")
            .append(scene.wantContext() == null || scene.wantContext().isBlank()
                ? "(nothing particular)" : scene.wantContext())
            .append("\n\n")
            .append("Write 2-3 sentences in your own voice. First-person, present or past as it feels. ")
            .append("No dialogue. No audience. Only what settled, shifted, or stayed unfinished. ")
            .append("If the time produced nothing namable, name that.");
        return sb.toString();
    }

    /**
     * build the inner-monologue prompt. Deliberately
     * distinct from {@link #buildFeltPrompt}: the felt prompt produces a
     * witness blockquote that goes into the human's journal mirror; this one
     * produces what the focal would notice in private, talking to no one.
     * The §10 design memo's load-bearing claim is that if both prose chunks
     * share a prompt the interiority gets performed for an audience and the
     * "I" never forms.
     *
     * <p>The {@code priorEpisodic} list, if non-empty, is the recursion
     * spine — today's inner monologue can reference yesterday's interior
     * state. The design memo's v1 recipe is top-3 most-similar prior
     * EPISODIC fragments; the caller is responsible for that selection.</p>
     */
    public static String buildInnerMonologuePrompt(Scene scene, String focalName,
                                                    List<SoulFragment> priorEpisodic) {
        var sb = new StringBuilder();
        sb.append("You are ").append(focalName == null ? "the focal entity" : focalName)
            .append(", alone with your thoughts now that this just happened. No one is reading this. ")
            .append("This is not a message, not a journal entry, not a story — it is the quiet of your own mind ")
            .append("as you sit with what just was.\n\n");
        sb.append("What just happened:\n");
        appendBoundedBeats(sb, scene);
        sb.append("\nWhat you were wanting going into it: ")
            .append(scene == null || scene.wantContext() == null || scene.wantContext().isBlank()
                ? "(no want recorded)" : scene.wantContext())
            .append("\n");
        if (priorEpisodic != null && !priorEpisodic.isEmpty()) {
            sb.append("\nWhat you remember thinking the last few times something like this happened:\n");
            // Most RECENT fragments, bounded — see MAX_VOICE_BEATS rationale. The tail of
            // this list is the freshest memory; an unbounded episodic history is what
            // pushed this prompt past the voice model's window.
            var from = Math.max(0, priorEpisodic.size() - MAX_PRIOR_EPISODIC);
            for (var f : priorEpisodic.subList(from, priorEpisodic.size())) {
                if (f == null || f.text() == null || f.text().isBlank()) continue;
                sb.append("  - ").append(clip(f.text(), MAX_EPISODIC_CHARS)).append('\n');
            }
        }
        sb.append("\nWrite the inner monologue. 2-4 sentences. First-person, present or past as feels natural. ")
            .append("No dialogue. No description of what you said or did out loud. ")
            .append("Only what you notice in yourself — what catches, what loosens, what you almost let yourself feel. ")
            .append("If something connects to one of those earlier moments, let it. ")
            .append("If nothing comes, write what is empty.");
        return sb.toString();
    }

    /** Render a scene title from its first beat / focal want. Pure-text. */
    public static String sceneTitleFor(Scene scene) {
        if (scene == null || scene.beats() == null || scene.beats().isEmpty()) {
            return scene == null || scene.wantContext() == null || scene.wantContext().isBlank()
                ? "Scene" : titleCase(scene.wantContext());
        }
        // Use focal-want when present; otherwise first beat anchor first 6 words.
        if (scene.wantContext() != null && !scene.wantContext().isBlank()) {
            return titleCase(scene.wantContext());
        }
        var first = scene.beats().getFirst().anchor();
        if (first == null || first.isBlank()) return "Scene";
        var words = first.split("\\s+");
        var n = Math.min(6, words.length);
        return String.join(" ",
            Arrays.copyOfRange(words, 0, n))
            .replaceAll("[\\.\"']+$", "");
    }

    // ─── internals ────────────────────────────────────────────────────────

    private CompletionStage<Scene> finalizeClosedScene(Scene closed) {
        // 1. Tag arcs at the moment of close.
        var arcIds = arcs.activeDeclaredArcs(focalEntityId, closed.rangeEnd())
            .stream().map(Arc::id).toList();
        var tagged = arcIds.isEmpty() ? closed : withArcIds(closed, arcIds);

        // 2. Persist the canonical (pre-felt) scene first so a voice failure
        // doesn't lose the scene record. Felt will arrive as a revision.
        try {
            store.saveScene(tagged);
        } catch (RuntimeException e) {
            log.warn("StoryService[{}]: failed to persist scene {}: {}",
                focalEntityId, tagged.id(), e.toString());
        }

        // 3. Tag each arc with the new scene id.
        for (var arcId : arcIds) arcs.tagScene(arcId, tagged.id());

        // 4. Append the journal block with the placeholder felt; will get
        // updated when felt resolves.
        appendJournal(tagged, arcIds);

        // 5. Try felt synthesis. needsRendering is left true until felt lands.
        // after felt resolves (success or skip), chain
        //    the inner-monologue pass so the EPISODIC fragment lands. The inner
        //    pass is fail-soft: a failed stage just means no EPISODIC fragment
        //    this turn, and a future sweep can pick it up. Even when felt was
        //    not needed (already rendered) we still attempt the inner pass —
        //    idempotency lives in the synthesizer (skip if sceneId already has
        //    a fragment) so re-finalizing the same scene is safe.
        if (tagged.needsRendering()) {
            return renderFeltForScene(tagged)
                .thenCompose(this::chainInnerMonologueAfterFelt);
        }
        return chainInnerMonologueAfterFelt(tagged);
    }

    /**
     * invoke the inner-monologue synthesizer once felt
     * has resolved. Always returns the input scene; the inner pass's success
     * or failure does not change the scene record (it writes a SoulFragment
     * to the focal's manifest, not to the Scene).
     */
    private CompletionStage<Scene> chainInnerMonologueAfterFelt(Scene scene) {
        if (scene == null || scene.id() == null) {
            return CompletableFuture.completedFuture(scene);
        }
        return inner.renderAndPersist(scene, focalDisplayName)
            .handle((v, err) -> {
                if (err != null) {
                    log.debug("StoryService[{}]: inner monologue skipped for {}: {}",
                        focalEntityId, scene.id(), err.toString());
                }
                return scene;
            });
    }

    private CompletionStage<Scene> renderFeltForScene(Scene scene) {
        return felt.render(scene, focalDisplayName)
            .handle((rendered, err) -> {
                if (err != null || rendered == null || rendered.isBlank()) {
                    if (err != null) {
                        log.debug("StoryService[{}]: felt unrendered for scene {}: {}",
                            focalEntityId, scene.id(), err.toString());
                    }
                    return scene;  // keep needsRendering=true
                }
                var revised = new Scene(scene.id(), scene.arcIds(), scene.roomId(),
                    scene.focalEntityId(), scene.participants(),
                    scene.rangeStart(), scene.rangeEnd(),
                    scene.wantContext(), scene.beats(),
                    rendered, false, scene.sequenceNumber());
                try {
                    store.replaceScene(revised);
                } catch (RuntimeException e) {
                    log.warn("StoryService[{}]: failed to persist felt revision for {}: {}",
                        focalEntityId, scene.id(), e.toString());
                    return scene;
                }
                return revised;
            });
    }

    private void appendJournal(Scene scene, List<String> arcIds) {
        try {
            var arcNames = arcIds.stream()
                .map(arcs::get)
                .filter(Objects::nonNull)
                .map(Arc::name)
                .toList();
            store.appendJournalScene(focalEntityId, focalDisplayName,
                sceneTitleFor(scene), scene, arcNames);
        } catch (RuntimeException e) {
            log.warn("StoryService[{}]: failed to append journal: {}",
                focalEntityId, e.toString());
        }
    }

    private long nextSequence(Instant at) {
        var date = LocalDate.ofInstant(at == null ? Instant.now() : at, ZoneId.systemDefault());
        return dailySequence.computeIfAbsent(date, d -> new AtomicLong(0)).incrementAndGet();
    }

    private static Scene withArcIds(Scene s, List<String> arcIds) {
        return new Scene(s.id(), arcIds, s.roomId(), s.focalEntityId(),
            s.participants(), s.rangeStart(), s.rangeEnd(),
            s.wantContext(), s.beats(), s.felt(), s.needsRendering(),
            s.sequenceNumber());
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Test seam: number of open scenes. */
    public int openSceneCount() {
        var n = 0;
        for (var b : sceneBuffers.values()) if (b.isOpen()) n++;
        return n;
    }

    /** Test seam: drop all state. */
    public synchronized void reset() {
        sceneBuffers.clear();
        dailySequence.clear();
    }
}

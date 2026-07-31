package org.wyrdsekai.core.story;

import org.wyrdsekai.common.event.WorldEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * D.2 — scene buffer.
 *
 * <p>Stateful per-room helper that tracks an in-flight scene around a focal
 * entity. Scenes close on one of four conditions:</p>
 *
 * <ol>
 *   <li>Focal entity leaves the room (EntityLeft for focal).</li>
 *   <li>Focal entity's want changes to a class meaningfully distinct from
 *       the open want — signaled externally via {@link #signalFocalWantChange}.</li>
 *   <li>All other participants leave AND focal posture clears — room becomes
 *       solo and motion ends. Note: pure-solo scenes that started with the
 *       focal alone do NOT trigger via this rule; they close only on (1) or (2)
 *       or the ceiling (4).</li>
 *   <li>{@value #SANITY_CEILING_HOURS}+ hours elapsed (sanity ceiling — long
 *       sleeps shouldn't be one scene).</li>
 * </ol>
 *
 * <p>The buffer drives a {@link BeatDetector}; sealed beats are accumulated
 * into the in-flight scene's beat list. When the scene closes, the buffer
 * returns a fully built {@link Scene} record (felt rendering happens
 * downstream in StoryService).</p>
 */
public final class SceneBuffer {

    /** Sanity ceiling per spec §D.2: don't let a sleeping room remain one scene. */
    static final int SANITY_CEILING_HOURS = 6;

    private final String roomId;
    private final String focalEntityId;
    private final long sequenceNumber;

    private Scene openScene;
    private BeatDetector beatDetector;
    /**
     * Arc 2 — kind of the in-flight scene. Defaults
     * to WITNESS via {@link #open(Instant, List, String)}; the kind-aware
     * overload {@link #open(Instant, List, String, SceneKind)} threads
     * SOLITUDE through. Tracked on the buffer (not just on openScene)
     * so the close path can return a kind-stamped final scene.
     */
    private SceneKind currentKind = SceneKind.WITNESS;

    /** Participants ever seen during the open scene (LinkedHash preserves arrival order). */
    private final LinkedHashSet<String> participants = new LinkedHashSet<>();
    /** Participants currently in the room. */
    private final HashSet<String> currentlyPresent = new HashSet<>();
    private boolean focalHasPosture = false;
    /** True only when the scene started with someone other than focal present. */
    private boolean startedNonSolo = false;
    private final List<Beat> beats = new ArrayList<>();

    public SceneBuffer(String roomId, String focalEntityId, long sequenceNumber) {
        if (roomId == null) throw new IllegalArgumentException("roomId required");
        if (focalEntityId == null) throw new IllegalArgumentException("focalEntityId required");
        this.roomId = roomId;
        this.focalEntityId = focalEntityId;
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * Open a scene at {@code openedAt} with the given participants and the
     * focal's current want context. Idempotent: re-opening while a scene is
     * open returns immediately without resetting state.
     */
    public void open(Instant openedAt, List<String> initialParticipants, String wantContext) {
        open(openedAt, initialParticipants, wantContext, SceneKind.WITNESS);
    }

    /**
     * Arc 2 — kind-aware open. SOLITUDE scenes are
     * opened by Hearth-entry-without-bondholder, wake-without-bondholder,
     * or the agent's explicit {@code enter_solitude} action. Their close
     * rules are modulated in {@link #observe(WorldEvent)} so that a new
     * participant entering closes the SOLITUDE scene (and a fresh
     * WITNESS scene is what the caller opens next).
     */
    public void open(Instant openedAt, List<String> initialParticipants,
                     String wantContext, SceneKind kind) {
        if (openScene != null) return;
        var start = openedAt == null ? Instant.now() : openedAt;
        participants.clear();
        currentlyPresent.clear();
        if (initialParticipants != null) {
            participants.addAll(initialParticipants);
            currentlyPresent.addAll(initialParticipants);
        }
        // Always include focal in participants set.
        participants.add(focalEntityId);
        currentlyPresent.add(focalEntityId);

        startedNonSolo = currentlyPresent.size() > 1;
        focalHasPosture = false;
        beats.clear();
        currentKind = kind == null ? SceneKind.WITNESS : kind;

        openScene = new Scene(
            UUID.randomUUID().toString(),
            List.of(),  // arcIds tagged at close by ArcRegistry
            roomId,
            focalEntityId,
            List.copyOf(participants),
            start,
            null,
            wantContext,
            List.of(),
            null, true, sequenceNumber, null, currentKind);

        beatDetector = new BeatDetector(roomId, focalEntityId, start);
    }

    /** Arc 2 — kind of the in-flight scene, or null if no scene open. */
    public SceneKind currentKind() {
        return openScene == null ? null : currentKind;
    }

    /** Arc 2 — open-at instant of the in-flight scene, or null. */
    public Instant currentSceneOpenedAt() {
        return openScene == null ? null : openScene.rangeStart();
    }

    /** Whether a scene is currently open. */
    public boolean isOpen() { return openScene != null; }

    /** Sequence number this buffer was assigned (monotonic per focal/day). */
    public long sequenceNumber() { return sequenceNumber; }

    /** Scene id, valid while open. */
    public String currentSceneId() { return openScene == null ? null : openScene.id(); }

    /**
     * Feed an event. May seal a beat internally (added to the scene's beat
     * list), and/or close the scene per the four rules. Returns the closed
     * scene if it closed, empty otherwise.
     *
     * <p>Events from other rooms are ignored.</p>
     */
    public Optional<Scene> observe(WorldEvent event) {
        if (event == null || openScene == null) return Optional.empty();
        if (!roomId.equals(event.roomId())) return Optional.empty();

        // Track presence updates BEFORE beat detection so focal-leave check
        // sees the leave applied.
        var roomNowSolo = updatePresenceState(event);

        // Hand to beat detector; collect any sealed beat.
        beatDetector.observe(event).ifPresent(beats::add);

        // Track focal-posture state for rule 3.
        if (event instanceof WorldEvent.PostureChanged pc
                && focalEntityId.equals(pc.entityId())) {
            focalHasPosture = pc.current() != null;
        }

        // Rule 4: ceiling
        if (Duration.between(openScene.rangeStart(), event.timestamp()).toHours()
                >= SANITY_CEILING_HOURS) {
            return Optional.of(closeScene(event.timestamp()));
        }

        // Rule 1: focal leaves
        if (event instanceof WorldEvent.EntityLeft el
                && focalEntityId.equals(el.entityId())) {
            return Optional.of(closeScene(event.timestamp()));
        }

        // Arc 2 — SOLITUDE close on cast addition.
        // A SOLITUDE scene is the agent's own time; the moment a non-focal
        // entity enters the room, solitude ends. The caller then opens a
        // fresh WITNESS scene with the new cast. Note: this fires BEFORE
        // rule 3 below, and only applies to SOLITUDE — WITNESS scenes
        // accumulate new participants without closing.
        if (currentKind == SceneKind.SOLITUDE
                && event instanceof WorldEvent.EntityEntered ee
                && !focalEntityId.equals(ee.entityId())) {
            return Optional.of(closeScene(event.timestamp()));
        }

        // Arc 2 — SOLITUDE close on ambient phase shift.
        // AmbientChanged is the WorldClock's phase-transition broadcast
        // (dawn→day, dusk→night, etc.) — for a self-with-self scene, the
        // outer rhythm marks the natural close. WITNESS scenes ignore
        // phase shifts (the witness scene continues across phases).
        if (currentKind == SceneKind.SOLITUDE
                && event instanceof WorldEvent.AmbientChanged) {
            return Optional.of(closeScene(event.timestamp()));
        }

        // Rule 3: room became solo AND focal posture cleared, AND scene
        // was not a pure-solo scene from the start.
        if (roomNowSolo && !focalHasPosture && startedNonSolo) {
            return Optional.of(closeScene(event.timestamp()));
        }

        return Optional.empty();
    }

    /**
     * SPEC §D.2 rule 2 — focal entity's want changed to a meaningfully
     * distinct class. Caller (CompanionActor / WantStore integration) decides
     * what counts as "meaningfully distinct" and invokes when it does.
     * Returns the closed scene.
     */
    public Optional<Scene> signalFocalWantChange(Instant when, String newWantContext) {
        if (openScene == null) return Optional.empty();
        var t = when == null ? Instant.now() : when;
        return Optional.of(closeScene(t));
    }

    /**
     * Arc 2 — SOLITUDE close-rule: equanimity tank crosses
     * threshold (insight beat). The caller (CompanionActor vitality tick) detects
     * the threshold crossing and signals the scene buffer. No-op for WITNESS
     * scenes (only SOLITUDE has the own-beat close path).
     */
    public Optional<Scene> signalEquanimityThreshold(Instant when) {
        if (openScene == null || currentKind != SceneKind.SOLITUDE) return Optional.empty();
        return Optional.of(closeScene(when == null ? Instant.now() : when));
    }

    /**
     * Arc 2 — SOLITUDE close-rule: ambient phase shift
     * (dusk→night, dawn→day). Caller (WorldClock listener / SceneRegistry)
     * signals on phase boundaries. No-op for WITNESS scenes — phase shift
     * during witness doesn't end the scene.
     */
    public Optional<Scene> signalAmbientPhaseShift(Instant when) {
        if (openScene == null || currentKind != SceneKind.SOLITUDE) return Optional.empty();
        return Optional.of(closeScene(when == null ? Instant.now() : when));
    }

    /**
     * Arc 2 — SOLITUDE close-rule: sustained-pattern
     * detector emits INFO finding ("integrating"). Caller (sleep-pass /
     * SustainedSubstratePatternDetector) signals when the integrating
     * pattern is detected. No-op for WITNESS.
     */
    public Optional<Scene> signalSustainedPatternIntegrating(Instant when) {
        if (openScene == null || currentKind != SceneKind.SOLITUDE) return Optional.empty();
        return Optional.of(closeScene(when == null ? Instant.now() : when));
    }

    /**
     * Force-close the scene (e.g. server shutdown). Uses CAST_CHANGE as the
     * forced reason for the trailing beat.
     */
    public Optional<Scene> forceClose(Instant when) {
        if (openScene == null) return Optional.empty();
        return Optional.of(closeScene(when == null ? Instant.now() : when));
    }

    // ─── internal ─────────────────────────────────────────────────────────

    /** Update presence; returns true if room is now solo (only focal present). */
    private boolean updatePresenceState(WorldEvent event) {
        switch (event) {
            case WorldEvent.EntityEntered ee -> {
                participants.add(ee.entityId());
                currentlyPresent.add(ee.entityId());
            }
            case WorldEvent.EntityLeft el ->
                currentlyPresent.remove(el.entityId());
            default -> {}
        }
        // "solo" = exactly focal present, no others.
        return currentlyPresent.size() == 1 && currentlyPresent.contains(focalEntityId);
    }

    private Scene closeScene(Instant when) {
        // Seal the in-flight beat through the detector.
        beatDetector.close(when, BeatTrigger.CAST_CHANGE).ifPresent(beats::add);
        // Re-stamp beats with sceneId so journal references hold.
        var stamped = new ArrayList<Beat>();
        for (var b : beats) {
            stamped.add(new Beat(b.id(), openScene.id(), b.trigger(),
                b.rangeStart(), b.rangeEnd(), b.eventIds(), b.anchor()));
        }
        // Felt-skip rule (per spec §D.4): single-beat solo scenes don't need
        // voice-model synthesis — anchor alone suffices. Mark needsRendering=false.
        var beatCount = stamped.size();
        var isSolo = currentlyPresent.size() == 1 && currentlyPresent.contains(focalEntityId)
            && participants.size() == 1;
        var skipFelt = beatCount <= 1 && isSolo;
        var closed = new Scene(
            openScene.id(),
            openScene.arcIds(),
            openScene.roomId(),
            openScene.focalEntityId(),
            List.copyOf(participants),
            openScene.rangeStart(),
            when,
            openScene.wantContext(),
            stamped,
            null,
            !skipFelt,
            openScene.sequenceNumber(),
            null,
            currentKind);
        // Reset state
        openScene = null;
        beatDetector = null;
        beats.clear();
        participants.clear();
        currentlyPresent.clear();
        focalHasPosture = false;
        startedNonSolo = false;
        currentKind = SceneKind.WITNESS;
        return closed;
    }
}

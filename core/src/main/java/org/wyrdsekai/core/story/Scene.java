package org.wyrdsekai.core.story;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * D.0 — a scene is the bounded dramatic unit holding beats.
 *
 * <p>Scenes close on one of four conditions (see {@link SceneBuffer}):</p>
 * <ol>
 *   <li>Focal entity leaves the room (EntityLeft for focal).</li>
 *   <li>Focal entity's want changes to a class meaningfully distinct from
 *       the open want.</li>
 *   <li>All other participants leave AND focal posture clears (room becomes
 *       solo and settled motion ends).</li>
 *   <li>6+ hours elapsed (sanity ceiling).</li>
 * </ol>
 *
 * <p>{@code felt} is null at close until the voice model renders it; if the
 * voice backend was unreachable, {@code needsRendering} stays true and a
 * batch sweep catches up later. Single-beat solo scenes skip felt (the
 * anchor alone suffices).</p>
 *
 * @param id              UUID
 * @param arcIds          arc memberships (zero or more; multiple OK)
 * @param roomId          the room the scene played in
 * @param focalEntityId   who the scene is "about"
 * @param participants    everyone present at any point during the scene
 * @param rangeStart      inclusive start
 * @param rangeEnd        inclusive end (null while open)
 * @param wantContext     focal entity's open want at scene-open (per
 * want taxonomy)
 * @param beats           ordered list of beats
 * @param felt            voice-model synthesis at close (null until rendered)
 * @param needsRendering  true if voice model unreachable at close
 * @param sequenceNumber  monotonic per (focalEntity, day) for journal stable IDs
 */
public record Scene(
    String id,
    List<String> arcIds,
    String roomId,
    String focalEntityId,
    List<String> participants,
    Instant rangeStart,
    Instant rangeEnd,
    String wantContext,
    List<Beat> beats,
    String felt,
    boolean needsRendering,
    long sequenceNumber,
    // §F.2 — append-only at the data layer. Revisions create
    // a new row with replacesId pointing at the original; readers filter to
    // the latest revision per sceneId. Null on the canonical (un-replaced)
    // version. A new scene starts with replacesId=null.
    String replacesId,
    // Arc 2 — solitude tagging. Defaults to WITNESS
    // when null on the canonical ctor; old persisted JSON files without
    // the field round-trip cleanly. See {@link SceneKind}.
    SceneKind kind
) {
    public Scene {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Scene id required");
        if (roomId == null) throw new IllegalArgumentException("roomId required");
        if (focalEntityId == null) throw new IllegalArgumentException("focalEntityId required");
        if (rangeStart == null) throw new IllegalArgumentException("rangeStart required");
        arcIds = arcIds == null ? List.of() : List.copyOf(arcIds);
        participants = participants == null ? List.of() : List.copyOf(participants);
        beats = beats == null ? List.of() : List.copyOf(beats);
        if (kind == null) kind = SceneKind.WITNESS;
    }

    /** Back-compat constructor with replacesId only — pre-Arc-2 callers. */
    public Scene(String id, List<String> arcIds, String roomId, String focalEntityId,
                 List<String> participants, Instant rangeStart, Instant rangeEnd,
                 String wantContext, List<Beat> beats, String felt,
                 boolean needsRendering, long sequenceNumber, String replacesId) {
        this(id, arcIds, roomId, focalEntityId, participants, rangeStart, rangeEnd,
             wantContext, beats, felt, needsRendering, sequenceNumber, replacesId,
             SceneKind.WITNESS);
    }

    /** Back-compat constructor without replacesId — used by initial scene creation. */
    public Scene(String id, List<String> arcIds, String roomId, String focalEntityId,
                 List<String> participants, Instant rangeStart, Instant rangeEnd,
                 String wantContext, List<Beat> beats, String felt,
                 boolean needsRendering, long sequenceNumber) {
        this(id, arcIds, roomId, focalEntityId, participants, rangeStart, rangeEnd,
             wantContext, beats, felt, needsRendering, sequenceNumber, null,
             SceneKind.WITNESS);
    }

    /**
     * §F.2 — produce a revision of this scene with a new id
     * pointing back at this one via {@code replacesId}. Used when a later
     * sweep needs to amend a scene (e.g. fill in felt that was pending).
     * Preserves kind across the revision chain.
     */
    public Scene asRevision(String newId, String felt, boolean needsRendering) {
        return new Scene(newId, arcIds, roomId, focalEntityId, participants,
            rangeStart, rangeEnd, wantContext, beats, felt, needsRendering,
            sequenceNumber, this.id, kind);
    }

    /**
     * §F.2 — given a list of scenes for one day, return only
     * the latest revision per scene-chain. A revision chain links by
     * replacesId; readers filter to leaves (scenes whose id is not anyone's
     * replacesId).
     */
    public static List<Scene> latestRevisions(List<Scene> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        var replaced = new HashSet<String>();
        for (var s : raw) {
            if (s.replacesId() != null) replaced.add(s.replacesId());
        }
        return raw.stream().filter(s -> !replaced.contains(s.id())).toList();
    }

    /** Whether the scene is still in-flight. */
    @JsonIgnore
    public boolean isOpen() {
        return rangeEnd == null;
    }

    /** Total beat count. */
    @JsonIgnore
    public int beatCount() {
        return beats.size();
    }

    /** True if scene is solo (focal is the only participant). */
    @JsonIgnore
    public boolean isSolo() {
        return participants.size() == 1 && participants.contains(focalEntityId);
    }

    /** Arc 2 — convenience predicate. */
    @JsonIgnore
    public boolean isSolitude() {
        return kind == SceneKind.SOLITUDE;
    }
}

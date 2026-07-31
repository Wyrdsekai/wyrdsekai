package org.wyrdsekai.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * a body in a place. Optional on {@link Entity}.
 *
 * <p>The free-form triple (verb, atObject?, descriptor) plus a timestamp and an optional
 * inner-state imprint. The verb is text — not an enum. The engine treats unknown verbs
 * the same as conventional ones (sit, stand, kneel, lie, lean, hold, gaze) because this
 * is a story substrate, not a physics substrate: the engine's job is to give narration
 * a place to land and to make moments findable by perception and soul, not to enforce
 * body mechanics.</p>
 *
 * <p>The descriptor is captured at the moment of posture entry and stays as written;
 * time-aging is computed on read, not on tick. At T+0 "settles into the leather chair";
 * at T+10min the reader interprets "has been sitting a while now." No scheduler;
 * no state churn.</p>
 *
 * <p>One posture per entity at a time. Composite states ("kneeling and holding the cup")
 * live inside the descriptor, not by stacking records.</p>
 *
 * @param verb          free-form posture verb ("sat", "perched", "knelt", "leaned", "stood");
 *                      no enum, no vocabulary check. Conventional verbs are documented;
 *                      unknown verbs render through the descriptor like known ones.
 * @param atObject      optional RoomObject id when the posture has a clean target
 *                      ("study-chair", "hearth"); null when it stands alone
 *                      (kneeling-by-the-window, gazing-into-space).
 * @param descriptor    the felt text captured at entry. Flows into Present rendering,
 *                      examine output, prompt context, and soul fragments.
 * @param setAt         when this posture began. Used for time-elapsed interpretation by
 *                      readers and as the scene-boundary anchor for the scene-detector.
 * @param innerImprint  optional {@link InnerImprint} — per-tick vitality/drive effects
 *                      while this posture is held, plus optional one-shot trigger event.
 * v1 minimum coupling; v2 expands to
 *                      continuous-time forcing functions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Posture(
    @JsonProperty("verb") String verb,
    @JsonProperty("atObject") String atObject,
    @JsonProperty("descriptor") String descriptor,
    @JsonProperty("setAt") Instant setAt,
    @JsonProperty("innerImprint") InnerImprint innerImprint
) {

    public Posture {
        if (verb == null || verb.isBlank()) {
            throw new IllegalArgumentException("Posture.verb must not be blank");
        }
        if (descriptor == null || descriptor.isBlank()) {
            throw new IllegalArgumentException("Posture.descriptor must not be blank");
        }
        if (setAt == null) {
            throw new IllegalArgumentException("Posture.setAt must not be null");
        }
    }

    /** No inner imprint. */
    public Posture(String verb, String atObject, String descriptor, Instant setAt) {
        this(verb, atObject, descriptor, setAt, null);
    }

    /** No atObject, no inner imprint — for postures with no clean target. */
    public Posture(String verb, String descriptor, Instant setAt) {
        this(verb, null, descriptor, setAt, null);
    }

    /** Convenience: no inner imprint, setAt defaults to now. */
    public Posture(String verb, String atObject, String descriptor) {
        this(verb, atObject, descriptor, Instant.now(), null);
    }

    /** Convenience: no atObject, no inner imprint, setAt defaults to now. */
    public Posture(String verb, String descriptor) {
        this(verb, null, descriptor, Instant.now(), null);
    }

    @JsonCreator
    public static Posture create(
            @JsonProperty("verb") String verb,
            @JsonProperty("atObject") String atObject,
            @JsonProperty("descriptor") String descriptor,
            @JsonProperty("setAt") Instant setAt,
            @JsonProperty("innerImprint") InnerImprint innerImprint) {
        return new Posture(verb, atObject, descriptor, setAt, innerImprint);
    }

    /** True if this posture has an inner-state imprint that will affect the entity's vitality. */
    public boolean hasImprint() {
        return innerImprint != null && !innerImprint.isEmpty();
    }
}

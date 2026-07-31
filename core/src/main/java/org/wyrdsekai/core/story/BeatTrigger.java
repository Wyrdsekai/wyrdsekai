package org.wyrdsekai.core.story;

/**
 * (Phase D) — the five canonical beat triggers.
 *
 * <p>A beat is the smallest dramatic unit inside a scene. It closes when one
 * of these five conditions fires:</p>
 *
 * <ul>
 *   <li>{@link #CAST_CHANGE} — an entity entered or left the room. Always
 *       evaluated for scene-close too (see {@link SceneBuffer}).</li>
 *   <li>{@link #TOPIC_SHIFT} — speech cohesion breaks. {@code >3} cohesive
 *       turns followed by a Said that doesn't share noun-cluster overlap
 *       with the prior cluster.</li>
 *   <li>{@link #DISCOVERY} — an ObjectUsed, a LookedAt with non-null manner,
 *       or a Said matching learning-pattern regex ("I didn't know", "it turns
 *       out"). Marks recognition.</li>
 *   <li>{@link #INTRUSION} — external pressure broke the scene: cross-zone
 *       tell delivered to focal entity, AmbientChanged event, or notification
 *       from outside the room.</li>
 *   <li>{@link #TACTIC_CHANGE} — the focal entity's want changed (per
 * WantStore), OR a PostureChanged event fires with
 *       both previous and current non-null (someone shifted body posture
 *       deliberately, like standing from sitting).</li>
 * </ul>
 *
 * <p>Plan reference: §D.1.</p>
 */
public enum BeatTrigger {
    CAST_CHANGE,
    TOPIC_SHIFT,
    DISCOVERY,
    INTRUSION,
    TACTIC_CHANGE
}

package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.event.WorldEvent;

/**
 * (Phase C) — significance mapping for body events.
 *
 * <p>Pure function over (event, observerContext) → significance level. The
 * companion calls this when a same-room body event arrives so it can decide
 * how prominently to surface the observation in working memory and whether
 * the moment warrants a reactive response.</p>
 *
 * <p>The plan's mapping:</p>
 * <pre>
 *   PostureChanged of other in room   → LOW (MEDIUM if bondholder)
 *   LookedAt where target == self     → MEDIUM  (someone watched you with intent)
 *   AmbientChanged                    → LOW    (background atmosphere)
 *   Emoted body-language flavor       → LOW_MEDIUM (distinct from speech)
 * </pre>
 *
 * <p>Self-originated events (the observer is the actor) return {@link Level#SELF_SKIP}
 * so the caller filters them out — agents don't observe their own posture
 * change as external perception.</p>
 */
public final class EmbodimentSignificance {

    private EmbodimentSignificance() {}

    /**
     * Significance buckets. Used by the companion to decide whether to add a
     * working-memory line, whether to elevate the line into the conversation
     * history, and whether the moment is significant enough to consider a
     * reactive response (e.g. greeting an entering bondholder).
     *
     * <p>{@link #SELF_SKIP} is a sentinel — the event is the observer's own
     * action and should not be perceived as external.</p>
     */
    public enum Level {
        SELF_SKIP,    // observer == actor; don't observe own action
        LOW,          // note it; not worth surfacing
        LOW_MEDIUM,   // body-language; surface as observation line
        MEDIUM        // significant; surface + potentially elevate
    }

    /**
     * Map a same-room body event to its significance from the observer's POV.
     *
     * @param event              the world event (PostureChanged / LookedAt /
     *                           AmbientChanged / Emoted). Other event types
     *                           return {@link Level#LOW} as a safe default —
     *                           callers should not pass non-body events here.
     * @param observerEntityId   the observing agent's entityId
     * @param bondholderEntityId the observing agent's primary bondholder DID
     *                           (or {@code null} if no bondholder is set)
     * @return significance level
     */
    public static Level levelFor(WorldEvent event,
                                  String observerEntityId,
                                  String bondholderEntityId) {
        if (event == null || observerEntityId == null) return Level.LOW;
        return switch (event) {
            case WorldEvent.PostureChanged pc -> {
                if (observerEntityId.equals(pc.entityId())) yield Level.SELF_SKIP;
                yield (bondholderEntityId != null && bondholderEntityId.equals(pc.entityId()))
                    ? Level.MEDIUM
                    : Level.LOW;
            }
            case WorldEvent.LookedAt la -> {
                if (observerEntityId.equals(la.actorId())) yield Level.SELF_SKIP;
                // Only surfaces as LOOK_RECEIVED when the look targets THIS observer.
                // A glance between two other entities is noise to me.
                yield observerEntityId.equals(la.targetId()) ? Level.MEDIUM : Level.LOW;
            }
            case WorldEvent.AmbientChanged ignored -> Level.LOW;
            case WorldEvent.Emoted em -> {
                if (observerEntityId.equals(em.entityId())) yield Level.SELF_SKIP;
                yield Level.LOW_MEDIUM;
            }
            default -> Level.LOW;
        };
    }

    /**
     * Map an event to the ActivityType used by the observation-line marker.
     * Returns {@code null} if the event is not a body event (caller should
     * skip).
     */
    public static AgentEvent.ActivityType activityTypeFor(WorldEvent event) {
        if (event == null) return null;
        return switch (event) {
            case WorldEvent.PostureChanged ignored -> AgentEvent.ActivityType.POSTURE_CHANGE;
            case WorldEvent.LookedAt ignored      -> AgentEvent.ActivityType.LOOK_RECEIVED;
            case WorldEvent.AmbientChanged ignored -> AgentEvent.ActivityType.AMBIENT_SHIFT;
            case WorldEvent.Emoted ignored        -> AgentEvent.ActivityType.BODY_LANGUAGE;
            default -> null;
        };
    }

    /**
     * Render the event as a single observation-line for the working-memory
     * deque. Past-tense, neutral. Returns {@code null} for non-body events.
     */
    public static String renderObservation(WorldEvent event) {
        if (event == null) return null;
        return switch (event) {
            case WorldEvent.PostureChanged pc -> {
                if (pc.current() != null && pc.current().descriptor() != null
                        && !pc.current().descriptor().isBlank()) {
                    var desc = pc.current().descriptor();
                    // Strip leading actor name if the descriptor already includes it
                    // so we don't render "Alice Alice sat at chair".
                    var prefix = pc.entityName() + " ";
                    if (desc.startsWith(prefix)) desc = desc.substring(prefix.length());
                    yield pc.entityName() + " " + desc;
                }
                if (pc.current() == null) {
                    yield pc.entityName() + " stood / cleared posture.";
                }
                var verb = pc.current().verb() == null ? "moved" : pc.current().verb();
                yield pc.entityName() + " " + verb + ".";
            }
            case WorldEvent.LookedAt la -> {
                var manner = la.manner() == null || la.manner().isBlank()
                    ? "looked at" : la.manner();
                yield la.actorName() + " " + manner + " " + la.targetName() + ".";
            }
            case WorldEvent.AmbientChanged ac -> {
                if (ac.descriptor() != null && !ac.descriptor().isBlank()) {
                    yield ac.descriptor();
                }
                var k = ac.key() == null ? "atmosphere" : ac.key();
                yield "The room's " + k + " shifted.";
            }
            case WorldEvent.Emoted em -> em.text() == null || em.text().isBlank()
                ? em.entityName() + " emoted."
                : em.text();
            default -> null;
        };
    }
}

package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.soul.GenomeProfile;

/**
 * Scores the salience (urgency/importance) of external events for an agent.
 * Returns a value in [0.0, 1.0].
 *
 * Salience is a function of the event itself and the agent's current state:
 * vitality determines the attention threshold, genome personality (curiosity)
 * boosts ambient event scores.
 *
 * Used by the AutonomyCheck in CompanionActor to decide which accumulated
 * events are worth attending to.
 *
 * @see AgentEvent
 * @see CompanionActor
 */
public class SalienceScorer {

    private SalienceScorer() {} // utility class

    /**
     * Score a single event's salience given the agent's current vitality and genome.
     *
     * @param event    the external event
     * @param vitality current vitality state (energy, focus affect attention)
     * @param genome   agent's genome profile (nullable; curiosity baseline boosts ambient)
     * @return salience score in [0.0, 1.0]
     */
    public static double score(AgentEvent event, VitalityState vitality, GenomeProfile genome) {
        double raw = switch (event) {
            case AgentEvent.ZoneBroadcast zb -> scoreZoneBroadcast(zb);
            case AgentEvent.SystemEvent se -> scoreSystemEvent(se);
            case AgentEvent.AdjacentActivity aa -> scoreAdjacentActivity(aa);
            case AgentEvent.AgentMessage _ -> 0.8; // Direct messages are high-salience
            case AgentEvent.LocationUpdate lu -> scoreLocationUpdate(lu);
            case AgentEvent.OraclePredictionsArrived op -> op.hasActionable() ? 0.9 : 0.6 * op.maxConfidence();
            case AgentEvent.AbortSignal _ -> 1.0; // Abort signals are maximum salience
        };

        // Vitality modulation: curiosity boosts ambient scores
        double curiosity = 0.5;
        if (genome != null && genome.baselines() != null) {
            curiosity = genome.baselines().getOrDefault("curiosity", 0.5);
        }
        if (curiosity > 0.5 && raw < 0.5) {
            // High curiosity boosts ambient (sub-0.5) scores by up to 0.2
            raw += (curiosity - 0.5) * 0.4; // at curiosity=1.0: +0.2
        }

        return Math.max(0.0, Math.min(1.0, raw));
    }

    /**
     * Calculate the attention threshold based on vitality state.
     * Events must score at or above this threshold to be considered "salient."
     *
     * <ul>
     *   <li>Base threshold: 0.5</li>
     *   <li>Low energy (&lt; 0.3): threshold rises to 0.7 (only urgent stuff)</li>
     *   <li>High focus (&gt; 0.7): threshold drops to 0.4 (catches more)</li>
     *   <li>Low focus (&lt; 0.3): threshold rises to 0.6 (misses ambient)</li>
     * </ul>
     *
     * @param vitality current vitality state
     * @return attention threshold in [0.3, 0.8]
     */
    public static double calculateAttentionThreshold(VitalityState vitality) {
        double threshold = 0.5;

        // Low energy: only urgent events break through
        if (vitality.energy() < 0.3) {
            threshold = 0.7;
        }
        // High focus: wider attention net
        else if (vitality.focus() > 0.7) {
            threshold = 0.4;
        }
        // Low focus: narrower attention
        else if (vitality.focus() < 0.3) {
            threshold = 0.6;
        }

        return threshold;
    }

    /**
     * Calculate the attention threshold accounting for meeting status.
     * If the human is in a meeting, threshold is raised by 0.2 (don't interrupt).
     *
     * @param vitality   current vitality state
     * @param isInMeeting true if the human is currently in a calendar meeting
     * @return attention threshold in [0.3, 1.0]
     */
    public static double calculateAttentionThreshold(VitalityState vitality, boolean isInMeeting) {
        double threshold = calculateAttentionThreshold(vitality);
        if (isInMeeting) {
            threshold += 0.2;
        }
        return Math.min(1.0, threshold);
    }

    // ── Location Update scoring ───────────────────────────────────────

    /**
     * Score a location update event.
     * Location state changes (HOME -> AWAY, AWAY -> HOME) are moderate-salience
     * events the agent might want to react to.
     */
    private static double scoreLocationUpdate(AgentEvent.LocationUpdate lu) {
        // Any location update with an explicit state is moderate-salience.
        // The CompanionActor uses LocationContext.hasStateChanged() for the actual
        // "you're heading out" logic; here we ensure the event passes the threshold.
        if (lu.state() != null && lu.state() != LocationContext.LocationState.UNKNOWN) {
            return 0.6; // moderate -- agent may want to react
        }
        return 0.3; // routine GPS ping
    }

    // ── Zone Broadcast scoring ────────────────────────────────────────

    private static double scoreZoneBroadcast(AgentEvent.ZoneBroadcast zb) {
        // Extract text content for keyword matching
        String text = extractBroadcastText(zb);
        if (text == null) text = "";
        String lower = text.toLowerCase();

        // Urgent keywords
        if (lower.contains("approval") || lower.contains("critical")
                || lower.contains("urgent") || lower.contains("emergency")) {
            return 0.9;
        }

        // Important keywords
        if (lower.contains("completed") || lower.contains("failed")
                || lower.contains("error") || lower.contains("finished")) {
            return 0.7;
        }

        // Routine
        return 0.3;
    }

    private static String extractBroadcastText(AgentEvent.ZoneBroadcast zb) {
        if (zb.message() instanceof S2CMessage.Prose p) {
            return p.text();
        }
        // Fall back to class name for non-prose messages
        return zb.message() != null ? zb.message().getClass().getSimpleName() : null;
    }

    // ── System Event scoring ──────────────────────────────────────────

    private static double scoreSystemEvent(AgentEvent.SystemEvent se) {
        return switch (se.type()) {
            case HEALTH_ALERT -> 0.9;
            case INFERENCE_BACKEND_DOWN -> 0.8;
            case NODE_LEFT -> 0.7;
            case ZONE_SERVICE_DISCONNECTED -> 0.6;
            case INFERENCE_BACKEND_UP -> 0.5;
            // New reading on the shelves — worth noticing, not an alarm.
            case LIBRARY_PACK_INSTALLED -> 0.5;
            case NODE_JOINED -> 0.4;
            case ZONE_SERVICE_REGISTERED -> 0.4;
        };
    }

    // ── Adjacent Activity scoring ─────────────────────────────────────

    private static double scoreAdjacentActivity(AgentEvent.AdjacentActivity aa) {
        return switch (aa.type()) {
            case SPEECH -> 0.3;
            case ENTITY_ENTERED -> 0.2;
            case ENTITY_LEFT -> 0.15;
            case OBJECT_INTERACTION -> 0.1;
            case SCRIPT_TRIGGERED -> 0.1;
            // (Phase C) — adjacent-room body events.
            // Same-room significance is handled by EmbodimentSignificance;
            // through-walls attenuation gives them low salience here.
            case POSTURE_CHANGE -> 0.1;
            case LOOK_RECEIVED -> 0.0;   // can't receive a directed look from another room
            case AMBIENT_SHIFT -> 0.1;
            case BODY_LANGUAGE -> 0.15;
        };
    }
}

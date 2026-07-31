package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.protocol.S2CMessage;

/**
 * Maps external events to emotional vitality modulation.
 *
 * <p>Rather than simple "error happened -> errorPressure up", this provides
 * richer emotional mapping: zone successes feel satisfying (momentum + confidence),
 * failures feel concerning (errorPressure + alertness/focus), system health events
 * modulate confidence and error pressure, and adjacent activity provides ambient
 * aliveness (gentle momentum).
 *
 * <p>All modulation values are small deltas (0.01-0.08) designed to accumulate
 * over time rather than cause dramatic swings. VitalityState.withXxx() methods
 * clamp to [0.0, 1.0] automatically.
 *
 * @see VitalityState
 * @see AgentEvent
 */
public final class EnvironmentalMood {

    private EnvironmentalMood() {} // utility class

    /**
     * Apply emotional modulation from a zone broadcast.
     * Scans the broadcast text for sentiment keywords and adjusts vitality accordingly.
     *
     * @param current current vitality state
     * @param zb      the zone broadcast event
     * @return modulated vitality state (clamped)
     */
    public static VitalityState applyZoneBroadcast(VitalityState current, AgentEvent.ZoneBroadcast zb) {
        var text = extractText(zb.message()).toLowerCase();

        if (text.contains("completed") || text.contains("success")) {
            // Satisfaction: momentum up, confidence up
            return current.withMomentum(current.momentum() + 0.05)
                         .withConfidence(current.confidence() + 0.03);
        }
        if (text.contains("failed") || text.contains("error")) {
            // Concern: errorPressure up, focus up (alertness)
            return current.withErrorPressure(current.errorPressure() + 0.05)
                         .withFocus(current.focus() + 0.03);
        }
        if (text.contains("approval") || text.contains("critical")) {
            // Urgency: focus up, energy slight drain
            return current.withFocus(current.focus() + 0.08)
                         .withEnergy(current.energy() - 0.001);
        }
        // Routine: slight momentum from activity in the zone
        return current.withMomentum(current.momentum() + 0.01);
    }

    /**
     * Apply emotional modulation from system events.
     * Each system event type maps to a specific vitality response.
     *
     * @param current current vitality state
     * @param se      the system event
     * @return modulated vitality state (clamped)
     */
    public static VitalityState applySystemEvent(VitalityState current, AgentEvent.SystemEvent se) {
        return switch (se.type()) {
            case INFERENCE_BACKEND_DOWN -> current
                .withErrorPressure(current.errorPressure() + 0.05)
                .withConfidence(current.confidence() - 0.03);
            case INFERENCE_BACKEND_UP -> current
                .withErrorPressure(Math.max(0, current.errorPressure() - 0.03))
                .withConfidence(current.confidence() + 0.02);
            case NODE_LEFT -> current
                .withErrorPressure(current.errorPressure() + 0.03);
            case NODE_JOINED -> current
                .withMomentum(current.momentum() + 0.02);
            case HEALTH_ALERT -> current
                .withErrorPressure(current.errorPressure() + 0.08)
                .withFocus(current.focus() + 0.05);
            case ZONE_SERVICE_REGISTERED -> current
                .withMomentum(current.momentum() + 0.02);
            case ZONE_SERVICE_DISCONNECTED -> current
                .withErrorPressure(current.errorPressure() + 0.02);
        };
    }

    /**
     * Apply ambient mood from adjacent room activity.
     * Adjacent activity provides a gentle sense of aliveness in nearby rooms.
     *
     * @param current current vitality state
     * @param aa      the adjacent activity event
     * @return modulated vitality state (clamped)
     */
    public static VitalityState applyAdjacentActivity(VitalityState current, AgentEvent.AdjacentActivity aa) {
        return switch (aa.type()) {
            case SPEECH -> current.withMomentum(current.momentum() + 0.01);       // life nearby
            case ENTITY_ENTERED -> current.withMomentum(current.momentum() + 0.01);
            case ENTITY_LEFT -> current;                                           // neutral
            case OBJECT_INTERACTION -> current.withMomentum(current.momentum() + 0.005);
            case SCRIPT_TRIGGERED -> current.withFocus(current.focus() + 0.01);
            // (Phase C) — adjacent-room body events are
            // attenuated awareness ("muffled through walls"). All four register
            // as a thin sense of nearby aliveness; no significance elevation
            // here (significance applies only to same-room observation lines).
            case POSTURE_CHANGE -> current.withMomentum(current.momentum() + 0.005);
            case LOOK_RECEIVED -> current;        // can't receive a look from another room
            case AMBIENT_SHIFT -> current.withFocus(current.focus() + 0.005);
            case BODY_LANGUAGE -> current.withMomentum(current.momentum() + 0.005);
        };
    }

    /**
     * Apply mood from a direct agent message.
     * Direct messages are socially significant: rapport and focus both increase.
     *
     * @param current current vitality state
     * @param msg     the agent message event
     * @return modulated vitality state (clamped)
     */
    public static VitalityState applyAgentMessage(VitalityState current, AgentEvent.AgentMessage msg) {
        return current.withRapport(current.rapport() + 0.04)
                     .withFocus(current.focus() + 0.05);
    }

    /**
     * Extract human-readable text from an S2CMessage.
     * Returns empty string for message types that don't carry user-facing text.
     */
    static String extractText(S2CMessage msg) {
        if (msg instanceof S2CMessage.Prose p) return p.text();
        if (msg instanceof S2CMessage.ZoneResponse zr) return zr.text();
        if (msg instanceof S2CMessage.Notification n) return n.message();
        return "";
    }
}

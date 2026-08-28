package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.protocol.S2CMessage;

import java.time.Instant;

/**
 * External events delivered to agents via {@link AgentEventStream}.
 *
 * These are events from outside the agent's current room -- zone broadcasts,
 * system events, and adjacent room activity. Agents perceive these as
 * attenuated awareness of the wider world (like hearing muffled sounds
 * through walls).
 *
 * <ul>
 *   <li>{@link ZoneBroadcast} -- zone service broadcast (CodeZaiku board event, IoT status, etc.)</li>
 *   <li>{@link SystemEvent} -- system-level event (node joined/left, inference status, health alert)</li>
 *   <li>{@link AdjacentActivity} -- activity in a neighboring room (attenuated: type and count only, no content)</li>
 * </ul>
 */
public sealed interface AgentEvent {

    /** Zone service broadcast (CodeZaiku board event, IoT status, etc.) */
    record ZoneBroadcast(String namespace, String roomId,
                         S2CMessage message,
                         Instant timestamp) implements AgentEvent {}

    /** System-level event (node joined/left, inference status, health alert). */
    record SystemEvent(SystemEventType type, String source, String detail,
                       Instant timestamp) implements AgentEvent {}

    /** Activity in a neighboring room (attenuated -- type and count only, no content). */
    record AdjacentActivity(String sourceRoomId, String sourceRoomName,
                            ActivityType type, int entityCount,
                            Instant timestamp) implements AgentEvent {}

    enum SystemEventType {
        NODE_JOINED, NODE_LEFT,
        INFERENCE_BACKEND_UP, INFERENCE_BACKEND_DOWN,
        HEALTH_ALERT,
        ZONE_SERVICE_REGISTERED, ZONE_SERVICE_DISCONNECTED
    }

    /**
     * A direct message from one agent to another (cross-room tell).
     * Delivered only to the target agent, not broadcast to all subscribers.
     *
     * <p>{@code senderLocale} carries the sender's UI locale (BCP 47, e.g. "ja",
     * "es"). Used by the recipient companion to populate {@code Said.locale} on
     * the synthesized event so the translate-route-translate hop sees the
     * correct source language. Pass null when locale is unknown — the
     * companion will fall back to its own session locale or content-sniff.</p>
     */
    record AgentMessage(String fromAgentId, String fromAgentName,
                        String toAgentId, String message,
                        String senderLocale,
                        Instant timestamp) implements AgentEvent {

        /** Backward-compatible constructor — locale unknown. */
        public AgentMessage(String fromAgentId, String fromAgentName,
                            String toAgentId, String message,
                            Instant timestamp) {
            this(fromAgentId, fromAgentName, toAgentId, message, null, timestamp);
        }
    }

    /**
     * Location update from the phone node's GPS.
     * Published by the phone bridge; CompanionActor processes it to update
     * {@link LocationContext} and optionally react to state changes.
     */
    record LocationUpdate(double latitude, double longitude, String locationName,
                          LocationContext.LocationState state,
                          Instant timestamp) implements AgentEvent {}

    /**
     * Oracle predictions arrived (from ForgeHook or Between sync).
     * Agents use this to spike their Alertness drive and consider proactive narration.
     */
    record OraclePredictionsArrived(String userId, int count, double maxConfidence,
                                     boolean hasActionable,
                                     Instant timestamp) implements AgentEvent {}

    /**
     * Abort signal from a human player. Agents in the specified room
     * should cancel current inference and abandon active plans.
     */
    record AbortSignal(String fromPlayerId, String fromPlayerName,
                       String roomId, Instant timestamp) implements AgentEvent {}

    enum ActivityType {
        SPEECH, ENTITY_ENTERED, ENTITY_LEFT,
        OBJECT_INTERACTION, SCRIPT_TRIGGERED,
        // (Phase C) — perception of body events.
        // Same-room PostureChanged → POSTURE_CHANGE.
        // LookedAt where target == self → LOOK_RECEIVED (someone watched you with intent).
        // AmbientChanged → AMBIENT_SHIFT (background atmosphere).
        // Emoted (body-language) → BODY_LANGUAGE (distinct from speech).
        POSTURE_CHANGE, LOOK_RECEIVED, AMBIENT_SHIFT, BODY_LANGUAGE
    }
}

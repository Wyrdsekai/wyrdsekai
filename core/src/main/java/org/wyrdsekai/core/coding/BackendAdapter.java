package org.wyrdsekai.core.coding;

import org.wyrdsekai.core.agent.AgentEvent;

/**
 * Translates between the generic coding-task abstraction and a specific
 * backend's wire / event format.
 *
 * <p>. Sits between the generic
 * {@link CodingTaskItemBridge} (which subscribes to
 * {@link org.wyrdsekai.core.agent.AgentEventStream} for all backends) and the
 * concrete backend's event shape.</p>
 *
 * <p>Phase 1a ships exactly one adapter — {@link CodePlaneEventAdapter} —
 * which lifts the legacy {@link
 * org.wyrdsekai.core.codeplane.CodePlaneItemBridge} translation logic into
 * the new shape. Aider, OpenHands, and the paid backends bring their own
 * adapters in later phases.</p>
 */
public interface BackendAdapter {

    /**
     * Namespace string this adapter handles. Matches both the backend's
     * {@link CodingTaskBackend#name()} and the
     * {@link AgentEvent.ZoneBroadcast#namespace()} value used to route
     * inbound events.
     */
    String namespace();

    /**
     * Translate a {@link AgentEvent.ZoneBroadcast} from this backend into
     * a generic {@link CodingArtifact}, or return {@code null} if the
     * event is not artifact-bearing (e.g. progress pings, status updates).
     */
    CodingArtifact translateEvent(AgentEvent.ZoneBroadcast event);

    /**
     * Parse a free-form player command (typed in the Workshop room) into
     * a {@link TaskSpec} that this backend understands.
     *
     * <p>Returns {@code null} if the command is not for this backend.</p>
     */
    TaskSpec parsePlayerCommand(String command, String args);
}

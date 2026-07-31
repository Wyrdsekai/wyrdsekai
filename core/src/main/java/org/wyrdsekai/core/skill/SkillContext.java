package org.wyrdsekai.core.skill;

import java.util.Map;

/**
 * Context provided to a skill executor at invocation time.
 * Contains everything the executor needs without holding permanent references.
 *
 * @param agentDid       Who is invoking (agent DID, or human session ID)
 * @param roomId         Which room the invocation originates from
 * @param credentials    Injected by The Safe — ephemeral, never stored
 * @param budgetRemaining From Counting House — credits remaining for this agent
 * @param timeoutMs      Maximum execution time in milliseconds
 * @param isHumanSession True if invoked directly by a human (not an agent)
 * @param isLocalSession True if the invoking session is on the local machine (not SSH)
 * @param nodeId         Which node this invocation originates from
 */
public record SkillContext(
    String agentDid,
    String roomId,
    Map<String, String> credentials,
    long budgetRemaining,
    long timeoutMs,
    boolean isHumanSession,
    boolean isLocalSession,
    String nodeId
) {
    public SkillContext {
        if (agentDid == null || agentDid.isBlank()) throw new IllegalArgumentException("Agent DID required");
        if (credentials == null) credentials = Map.of();
        if (timeoutMs <= 0) timeoutMs = 30_000;
    }

    /** Create a context for agent-initiated skill invocation. */
    public static SkillContext forAgent(String agentDid, String roomId,
                                        Map<String, String> credentials,
                                        long budgetRemaining) {
        return new SkillContext(agentDid, roomId, credentials, budgetRemaining,
            30_000, false, false, null);
    }

    /** Create a context for human-initiated skill invocation (e.g., Study room). */
    public static SkillContext forHuman(String userId, String roomId,
                                        Map<String, String> credentials,
                                        boolean isLocal) {
        return new SkillContext(userId, roomId, credentials, Long.MAX_VALUE,
            30_000, true, isLocal, null);
    }
}

package org.wyrdsekai.core.home;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Opinionated ritual helpers for agent-Hearth founding grants (
 * §11.3 "agent birth" + M4 per §13).
 *
 * <p>Bundles the three founding-grant issuances the spec calls for when a
 * steward brings a new agent into being:
 * <ol>
 *   <li>inference-budget use — the agent can spend tokens up to the cap</li>
 *   <li>memory-index read — the agent can read its steward's memory (for context)</li>
 *   <li>home-room use — the agent can enter the steward's Study</li>
 * </ol>
 * Plus the reciprocal: agent → steward read on the agent's audit-log, so
 * the steward can inspect what their companion has been doing.
 *
 * <p>All calls are idempotent (issueOrReplace). This class is a library, not
 * a listener or actor — call sites that know both DIDs invoke the methods.
 * Non-invocation is fine; the spec says these are "initial" grants, not
 * mandatory-at-spawn, and the owner can issue/revoke them later.</p>
 */
public final class HearthRituals {

    private static final Logger log = LoggerFactory.getLogger(HearthRituals.class);

    private HearthRituals() {}

    /**
     * Issue the §11.3 founding grants from steward to new agent.
     *
     * @param client          home client
     * @param stewardDid      the human (or parent agent) who is spawning
     * @param agentDid        the new agent's DID
     * @param dailyTokenCap   token budget the steward is willing to grant;
     *                        0 or negative means omit the budget grant
     * @param ttl             grant lifetime; {@code null} = open-ended
     */
    public static void issueFoundingGrants(HomeClient client,
                                             String stewardDid, String agentDid,
                                             long dailyTokenCap, Duration ttl) {
        if (client == null || stewardDid == null || agentDid == null) return;
        var expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        try {
            if (dailyTokenCap > 0) {
                var budget = ResourceUri.of(stewardDid, ResourceTypeRegistry.INFERENCE_BUDGET);
                client.issueOrReplace(
                    stewardDid, agentDid, budget, Capability.use,
                    Map.of("dailyTokenCap", dailyTokenCap),
                    expiresAt,
                    "founding:inference-budget");
            }
            var memory = ResourceUri.of(stewardDid, ResourceTypeRegistry.MEMORY_INDEX, "all");
            client.issueOrReplace(
                stewardDid, agentDid, memory, Capability.read,
                Map.of(), expiresAt,
                "founding:memory-read");

            var homeRoom = ResourceUri.of(stewardDid, ResourceTypeRegistry.HOME_ROOM);
            client.issueOrReplace(
                stewardDid, agentDid, homeRoom, Capability.use,
                Map.of(), expiresAt,
                "founding:home-room-use");
        } catch (Exception e) {
            log.warn("issueFoundingGrants {} → {} failed: {}",
                stewardDid, agentDid, e.getMessage());
        }
    }

    /**
     * Reciprocal: agent self-issues a read grant on its audit-log to its
     * steward, so the steward can observe what the agent has been doing.
     * (§M4 "audit log per companion, readable by bonded owner.")
     */
    public static void shareAuditWithSteward(HomeClient client,
                                               String agentDid, String stewardDid,
                                               Duration ttl) {
        if (client == null || agentDid == null || stewardDid == null) return;
        if (agentDid.equals(stewardDid)) return;
        var expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        try {
            var auditLog = ResourceUri.of(agentDid, ResourceTypeRegistry.AUDIT_LOG);
            client.issueOrReplace(
                agentDid, stewardDid, auditLog, Capability.read,
                Map.of(), expiresAt,
                "founding:audit-read");
        } catch (Exception e) {
            log.warn("shareAuditWithSteward {} → {} failed: {}",
                agentDid, stewardDid, e.getMessage());
        }
    }

    /**
     * Convenience: issue both directions at once when a new agent is born
     * with a known steward relationship.
     */
    public static void seedHearth(HomeClient client,
                                    String stewardDid, String agentDid,
                                    long dailyTokenCap, Duration ttl) {
        issueFoundingGrants(client, stewardDid, agentDid, dailyTokenCap, ttl);
        shareAuditWithSteward(client, agentDid, stewardDid, ttl);
    }
}

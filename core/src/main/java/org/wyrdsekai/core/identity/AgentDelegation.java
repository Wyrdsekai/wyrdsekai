package org.wyrdsekai.core.identity;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent delegation chain (§18).
 * Links human DIDs to their agent DIDs, establishing a chain of authority.
 * A human can delegate specific permissions to their agent,
 * and the agent can prove its authority via the delegation chain.
 */
public class AgentDelegation {

    /** A delegation link from principal to agent. */
    public record Delegation(
        String principalDid,    // human DID
        String agentDid,        // agent DID
        Set<String> permissions, // delegated permissions
        Instant delegatedAt,
        Instant expiresAt,      // null = no expiry
        boolean active
    ) {
        public boolean isValid() {
            if (!active) return false;
            if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
            return true;
        }

        public boolean hasPermission(String permission) {
            return permissions.contains(permission) || permissions.contains("*");
        }
    }

    private final Map<String, List<Delegation>> byAgent = new ConcurrentHashMap<>();
    private final Map<String, List<Delegation>> byPrincipal = new ConcurrentHashMap<>();

    /** Create a delegation from principal to agent. */
    public Delegation delegate(String principalDid, String agentDid,
                                Set<String> permissions, Instant expiresAt) {
        var delegation = new Delegation(principalDid, agentDid,
            Set.copyOf(permissions), Instant.now(), expiresAt, true);
        byAgent.computeIfAbsent(agentDid, _ -> Collections.synchronizedList(new ArrayList<>()))
            .add(delegation);
        byPrincipal.computeIfAbsent(principalDid, _ -> Collections.synchronizedList(new ArrayList<>()))
            .add(delegation);
        return delegation;
    }

    /** Check if an agent has a delegated permission. */
    public boolean hasPermission(String agentDid, String permission) {
        var delegations = byAgent.get(agentDid);
        if (delegations == null) return false;
        return delegations.stream()
            .anyMatch(d -> d.isValid() && d.hasPermission(permission));
    }

    /** Get the principal DID for an agent. */
    public Optional<String> getPrincipal(String agentDid) {
        var delegations = byAgent.get(agentDid);
        if (delegations == null) return Optional.empty();
        return delegations.stream()
            .filter(Delegation::isValid)
            .findFirst()
            .map(Delegation::principalDid);
    }

    /** Get all active delegations for a principal. */
    public List<Delegation> delegationsFor(String principalDid) {
        var delegations = byPrincipal.get(principalDid);
        if (delegations == null) return List.of();
        return delegations.stream().filter(Delegation::isValid).toList();
    }

    /** Revoke all delegations for an agent. */
    public int revoke(String agentDid) {
        var delegations = byAgent.get(agentDid);
        if (delegations == null) return 0;
        int count = 0;
        synchronized (delegations) {
            for (int i = 0; i < delegations.size(); i++) {
                var d = delegations.get(i);
                if (d.active()) {
                    delegations.set(i, new Delegation(d.principalDid(), d.agentDid(),
                        d.permissions(), d.delegatedAt(), d.expiresAt(), false));
                    count++;
                }
            }
        }
        return count;
    }

    /** Total number of delegations (active and inactive). */
    public int totalDelegations() {
        return byAgent.values().stream().mapToInt(List::size).sum();
    }
}

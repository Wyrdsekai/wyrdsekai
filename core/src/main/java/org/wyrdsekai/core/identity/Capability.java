package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * A structured capability following UCAN-inspired command path conventions (§85.1, §85.6).
 * Command paths use slash-delimited namespaces: /room/enter, /room/read, /zone/transit, /soul/inspect.
 * Policy is a map of constraints that narrow the capability.
 *
 * <p>Phase 1: stub with basic path matching.
 * Phase 6: full isSubsetOf() with policy constraint narrowing.</p>
 *
 * @param command  Slash-delimited command path (e.g., "/room/read", "/zone/transit")
 * @param policy   Constraint map narrowing the capability (e.g., {"roomId": "room-1"})
 */
public record Capability(
    @JsonProperty("command") String command,
    @JsonProperty("policy") Map<String, Object> policy
) {
    @JsonCreator
    public Capability {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Capability command must not be blank");
        }
        if (!command.startsWith("/")) {
            throw new IllegalArgumentException("Capability command must start with '/'");
        }
        if (policy == null) {
            policy = Map.of();
        }
    }

    /** Convenience constructor with no policy constraints. */
    public Capability(String command) {
        this(command, Map.of());
    }

    /**
     * Check if this capability is a subset of (or equal to) a parent capability.
     * A child is a subset if its command path is equal to or more specific than the parent's,
     * and all child policy constraints are present in (and consistent with) the parent's policy.
     *
     * <p>Phase 1: basic path prefix matching + policy subset check.
     * Phase 6: full predicate logic for policy constraints.</p>
     */
    public boolean isSubsetOf(Capability parent) {
        // Wildcard parent grants everything
        if ("/*".equals(parent.command())) return true;

        // Command path: child must match or be more specific
        if (!command.equals(parent.command()) && !command.startsWith(parent.command() + "/")) {
            return false;
        }

        // Policy: child must not contradict parent's constraints
        // (child can add constraints but not remove parent's)
        for (var entry : parent.policy().entrySet()) {
            var childValue = policy.get(entry.getKey());
            if (childValue == null) {
                // Parent has a constraint the child doesn't — child is broader, not narrower
                return false;
            }
            if (!entry.getValue().equals(childValue)) {
                // Conflicting constraint values
                return false;
            }
        }

        return true;
    }
}

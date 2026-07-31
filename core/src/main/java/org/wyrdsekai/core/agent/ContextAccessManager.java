package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages per-agent context permissions (active_window, calendar, location, voice, etc.).
 * Singleton pattern — initialized at startup, accessed via {@link #get()}.
 *
 * <p>Permissions follow the access request lifecycle:
 * <ol>
 *   <li>Agent requests access via {@code request_access} action</li>
 *   <li>Human grants or denies</li>
 *   <li>Decision is stored and checked by context providers</li>
 *   <li>Denials carry a 30-day cooldown before the agent can re-ask</li>
 * </ol>
 *
 * @see ContextPermission
 * @see DesktopContextProvider
 */
public class ContextAccessManager {

    /** Cooldown period after a denial: 30 days. */
    static final Duration DENIAL_COOLDOWN = Duration.ofDays(30);

    /** Per-agent permissions: agentId → list of permissions (grants + denials). */
    private final Map<String, CopyOnWriteArrayList<ContextPermission>> permissions =
        new ConcurrentHashMap<>();

    /** Global singleton instance. */
    private static volatile ContextAccessManager instance;

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() { instance = new ContextAccessManager(); }

    /** Get the global instance. May be null if not initialized. */
    public static ContextAccessManager get() { return instance; }

    /** Reset for testing. */
    static void reset() { instance = null; }

    /**
     * Grant context access to an agent.
     *
     * @param agentId   Agent entity ID
     * @param source    Context source (e.g. "active_window")
     * @param scope     Scope within the source (e.g. "vscode,terminal")
     * @param grantedBy Human's DID
     */
    public void grant(String agentId, String source, String scope, String grantedBy) {
        var list = permissions.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>());
        // Remove any existing permission for this source (grant or denial)
        list.removeIf(p -> p.source().equals(source));
        list.add(new ContextPermission(source, scope, true, Instant.now(), grantedBy, null));
    }

    /**
     * Deny context access to an agent. Stores denial with 30-day cooldown.
     *
     * @param agentId  Agent entity ID
     * @param source   Context source
     * @param deniedBy Human's DID
     */
    public void deny(String agentId, String source, String deniedBy) {
        var list = permissions.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>());
        // Remove any existing permission for this source
        list.removeIf(p -> p.source().equals(source));
        list.add(new ContextPermission(source, "", false, Instant.now(), deniedBy, null));
    }

    /**
     * Revoke a previously granted permission. Removes the permission entirely.
     *
     * @param agentId Agent entity ID
     * @param source  Context source to revoke
     */
    public void revoke(String agentId, String source) {
        var list = permissions.get(agentId);
        if (list != null) {
            list.removeIf(p -> p.source().equals(source));
        }
    }

    /**
     * Check if an agent has granted access to a context source.
     * Returns false if denied, expired, revoked, or never granted.
     */
    public boolean isGranted(String agentId, String source) {
        var list = permissions.get(agentId);
        if (list == null) return false;
        return list.stream()
            .filter(p -> p.source().equals(source))
            .findFirst()
            .map(p -> p.granted() && !p.isExpired())
            .orElse(false);
    }

    /**
     * Get the granted scope for a context source (e.g. which apps are allowed).
     * Returns empty optional if not granted.
     */
    public Optional<String> getScope(String agentId, String source) {
        var list = permissions.get(agentId);
        if (list == null) return Optional.empty();
        return list.stream()
            .filter(p -> p.source().equals(source) && p.granted() && !p.isExpired())
            .map(ContextPermission::scope)
            .findFirst();
    }

    /**
     * Check if the agent can ask for a context source (30-day cooldown check).
     * Returns true if no denial exists or the cooldown has passed.
     */
    public boolean canAskFor(String agentId, String source) {
        var list = permissions.get(agentId);
        if (list == null) return true;
        var denial = list.stream()
            .filter(p -> p.source().equals(source) && !p.granted())
            .findFirst();
        if (denial.isEmpty()) return true;
        // Check if cooldown has passed
        return !denial.get().isInCooldown();
    }

    /**
     * List all permissions for an agent.
     *
     * @param agentId Agent entity ID
     * @return Immutable list of permissions (both grants and denials)
     */
    public List<ContextPermission> listPermissions(String agentId) {
        var list = permissions.get(agentId);
        if (list == null) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * Build prompt context showing what the agent can and can't access.
     * Included in the agent's system prompt so the LLM knows what data is available.
     *
     * @param agentId Agent entity ID
     * @return Context string for prompt assembly, or null if no permissions exist
     */
    public String buildContext(String agentId) {
        var list = permissions.get(agentId);
        if (list == null || list.isEmpty()) return null;

        var sb = new StringBuilder("## Context Access\n");
        var grants = list.stream().filter(p -> p.granted() && !p.isExpired()).toList();
        var denials = list.stream().filter(p -> !p.granted()).toList();

        if (!grants.isEmpty()) {
            sb.append("You have access to:\n");
            for (var g : grants) {
                sb.append("- ").append(g.source());
                if (g.scope() != null && !g.scope().isBlank()) {
                    sb.append(" (scope: ").append(g.scope()).append(")");
                }
                sb.append("\n");
            }
        }
        if (!denials.isEmpty()) {
            sb.append("Access denied (do not re-ask):\n");
            for (var d : denials) {
                if (d.isInCooldown()) {
                    sb.append("- ").append(d.source()).append(" (denied, cooldown active)\n");
                } else {
                    sb.append("- ").append(d.source()).append(" (denied, cooldown expired — may ask again)\n");
                }
            }
        }
        return sb.toString();
    }
}

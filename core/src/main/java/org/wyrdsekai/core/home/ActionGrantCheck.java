package org.wyrdsekai.core.home;

import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;

import java.util.Map;

/**
 * Owner-level per-action authorization for companions ( ACTION
 * §22.2 ActionPolicy reduction).
 *
 * <p>The owner (human or parent agent) issues
 * {@code Grant(resource=home://owner/action/{name}, capability=use, subject=companion)}
 * for each action the companion is allowed to take on the owner's behalf.
 * Tier-gating in {@code ActionPolicy.requiredTier} still runs first (Grants
 * do not elevate tier); Grants add a second axis of owner consent on top.</p>
 *
 * <p>Default is {@code strict=false} — absence of grants means "allowed".
 * Setting {@code strict=true} inverts the default (opt-in per-action).</p>
 */
@FunctionalInterface
public interface ActionGrantCheck {

    /** Does {@code companionDid} hold an owner-issued use-grant for {@code actionType}? */
    boolean canPerform(String companionDid, String ownerDid, String actionType);

    /** Always-allow — useful for dev/testing. */
    static ActionGrantCheck allowAll() {
        return (companion, owner, action) -> true;
    }

    /** HomeClient-backed check. */
    static ActionGrantCheck homeClientBacked(HomeClient homeClient, boolean strict) {
        return (companion, owner, action) -> {
            if (!strict) return true;
            if (owner == null || companion == null || action == null) return false;
            var resource = ResourceUri.of(owner, ResourceTypeRegistry.ACTION, action);
            try {
                return homeClient.check(companion, resource, Capability.use, Map.of());
            } catch (Exception e) {
                return false;
            }
        };
    }
}

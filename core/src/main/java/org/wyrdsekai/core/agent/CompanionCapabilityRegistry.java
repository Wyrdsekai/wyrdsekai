package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide map of {@code agentDid → live CompanionCapabilities}, populated
 * by each {@link CompanionActor} on construction and cleared on {@code PostStop}.
 *
 * <p>The purpose is simple: SkillMaterialization (
 * §6) needs the per-companion {@link org.wyrdsekai.core.soul.FamilyLocker} and
 * {@link org.wyrdsekai.core.skill.WorkbenchSkillExecutor} to seat an approved
 * draft as a soul-item, but the REST handler at {@code POST /api/skill/drafts/
 * {id}/approve} runs outside the actor. Previously the host wired a no-op
 * lambda for this and TODO'd the real wiring. This registry closes that gap.
 *
 * <p>Lifetime: a registration is valid while the {@link CompanionActor}
 * instance lives. Cross-zone relocate stops + starts the actor (transit token
 * path), so the registry naturally tracks "where does this agent live right
 * now" — entries for relocated companions disappear from this node and appear
 * on the destination node.
 *
 * <p>Foreign companions visiting via cross-zone follow do not register here:
 * their canonical FamilyLocker is on their home node, and any materialization
 * for them belongs there too.
 */
public final class CompanionCapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompanionCapabilityRegistry.class);
    private static final CompanionCapabilityRegistry INSTANCE = new CompanionCapabilityRegistry();

    private final ConcurrentHashMap<String, CompanionCapabilities> byDid = new ConcurrentHashMap<>();

    private CompanionCapabilityRegistry() {}

    public static CompanionCapabilityRegistry get() { return INSTANCE; }

    /**
     * Register live capabilities for a companion. Idempotent: re-registering
     * for the same DID replaces the prior bundle (useful when a companion is
     * re-hydrated after a soul migration).
     *
     * @return the previously-registered bundle, or null if this is fresh
     */
    public CompanionCapabilities register(String agentDid, CompanionCapabilities caps) {
        if (agentDid == null || agentDid.isBlank() || caps == null) return null;
        var prev = byDid.put(agentDid, caps);
        if (prev == null) {
            log.debug("CompanionCapabilityRegistry: registered {} (familyLocker={}, workbench={})",
                agentDid,
                caps.familyLocker() != null,
                caps.workbenchExecutor() != null);
        }
        return prev;
    }

    /** Drop the bundle for {@code agentDid}. No-op if absent. */
    public void unregister(String agentDid) {
        if (agentDid == null) return;
        if (byDid.remove(agentDid) != null) {
            log.debug("CompanionCapabilityRegistry: unregistered {}", agentDid);
        }
    }

    /** Returns the live bundle, or null when the companion isn't currently hosted here. */
    public CompanionCapabilities lookup(String agentDid) {
        if (agentDid == null) return null;
        return byDid.get(agentDid);
    }

    /** Diagnostic: how many companions are currently registered. */
    public int size() { return byDid.size(); }

    /** Test seam — drop all registrations. */
    public void clearForTests() {
        byDid.clear();
    }
}

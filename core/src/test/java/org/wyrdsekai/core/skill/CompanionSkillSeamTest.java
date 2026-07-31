package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 — the ZoneGuardian capability seam. Before this, every companion was
 * spawned with skillRegistry=null (ZoneGuardian:906/1023/1359), so the native
 * skills were unreachable. This proves the wiring the seam now performs:
 *   1. SkillBootstrap.create(...) yields a populated registry, installable + shared.
 *   2. A companion granted the tiered {@link SkillPermission#companionDefault()}
 *      can use LOW-consequence native skills but not the CONSEQUENTIAL ones —
 *      and emergency call is always allowed (safety floor).
 */
class CompanionSkillSeamTest {

    private static final String AGENT = "did:key:ma";

    @Test
    void shared_registry_installs_and_is_populated() {
        var registry = SkillBootstrap.create(Map.of());
        assertThat(registry.allSkills()).isNotEmpty();     // always-on executors registered
        SkillBootstrap.installShared(registry);
        assertThat(SkillBootstrap.shared()).isSameAs(registry);
    }

    @Test
    void companion_default_permits_low_consequence_gates_consequential() {
        var registry = SkillBootstrap.create(Map.of());
        registry.setPermissions(AGENT, SkillPermission.companionDefault());

        // low-consequence: open out of the box
        assertThat(registry.isAllowed(AGENT, "scrying.weather.current")).isTrue();
        assertThat(registry.isAllowed(AGENT, "hearth.grocery.add")).isTrue();

        // consequential: gated (comms as the household)
        assertThat(registry.isAllowed(AGENT, "herald.call.dial")).isFalse();
        assertThat(registry.isAllowed(AGENT, "herald.call.status")).isFalse();

        // safety floor: emergency call always allowed (exact match beats the herald.* deny)
        assertThat(registry.isAllowed(AGENT, "herald.call.emergency")).isTrue();

        // and the agent's visible skill set reflects it
        var visible = registry.skillsForAgent(AGENT);
        assertThat(visible).isNotEmpty();
        assertThat(visible).anyMatch(s -> s.id().equals("scrying.weather.current"));
        assertThat(visible).noneMatch(s -> s.id().equals("herald.call.dial"));
    }

    @Test
    void ungranted_agent_is_denied_everything_default_deny() {
        var registry = SkillBootstrap.create(Map.of());
        // no setPermissions call → default deny
        assertThat(registry.isAllowed("did:key:stranger", "scrying.weather.current")).isFalse();
        assertThat(registry.skillsForAgent("did:key:stranger")).isEmpty();
    }
}

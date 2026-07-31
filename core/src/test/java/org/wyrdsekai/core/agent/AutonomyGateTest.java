package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.home.ActionGrantCheck;
import org.wyrdsekai.core.security.DenialCatalog;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit matrix for the autonomy-consent axis (
 * ACTION) wired 2026-07-21. The gate only ever sees AUTONOMOUS
 * actions — human-directed flows bypass in {@code enforceActionPolicy}.
 */
class AutonomyGateTest {

    private static final String COMPANION = "companion-ember";
    private static final String OWNER = "did:key:owner";

    /** Grant check that says yes only for the given verbs. */
    private static ActionGrantCheck grantsFor(String... verbs) {
        var granted = Set.of(verbs);
        return (companion, owner, action) -> granted.contains(action);
    }

    private static boolean allowed(AutonomyGate.Decision d) {
        return d instanceof AutonomyGate.Decision.Allow;
    }

    @Test
    void ambient_and_visible_verbs_are_always_allowed() {
        // library_search=AMBIENT; no grants, strict on — still allowed.
        var d = AutonomyGate.evaluate("library_search", "search the library",
            grantsFor(), true, COMPANION, OWNER);
        assertThat(allowed(d)).isTrue();
    }

    @Test
    void consent_verb_open_by_default() {
        var d = AutonomyGate.evaluate("teleport_to", "teleport somewhere",
            grantsFor(), false, COMPANION, OWNER);
        assertThat(allowed(d)).isTrue();
    }

    @Test
    void consent_verb_denied_under_strict_without_grant() {
        var d = AutonomyGate.evaluate("teleport_to", "teleport somewhere",
            grantsFor(), true, COMPANION, OWNER);
        assertThat(d).isInstanceOf(AutonomyGate.Decision.Deny.class);
        var denial = ((AutonomyGate.Decision.Deny) d).denial();
        assertThat(denial.code()).isEqualTo(DenialCatalog.CODE_AUTONOMY_GATED);
        // The remediation template routes through the live GrantRequest
        // pipeline: home://{owner}/action/{verb}.
        assertThat(denial.inWorldResolution().source())
            .isEqualTo("home://" + OWNER + "/action/teleport_to");
        assertThat(denial.inWorldResolution().scope()).isEqualTo("use");
    }

    @Test
    void consent_verb_allowed_under_strict_with_grant() {
        var d = AutonomyGate.evaluate("teleport_to", "teleport somewhere",
            grantsFor("teleport_to"), true, COMPANION, OWNER);
        assertThat(allowed(d)).isTrue();
    }

    @Test
    void forbidden_verb_denied_even_in_default_mode() {
        var d = AutonomyGate.evaluate("release_bond", "release the bond",
            grantsFor(), false, COMPANION, OWNER);
        assertThat(d).isInstanceOf(AutonomyGate.Decision.Deny.class);
        var denial = ((AutonomyGate.Decision.Deny) d).denial();
        assertThat(denial.code()).isEqualTo(DenialCatalog.CODE_AUTONOMY_GATED);
        assertThat(denial.reason()).contains("explicit ok");
    }

    @Test
    void forbidden_verb_allowed_with_explicit_owner_grant() {
        // The grant IS the ladder upgrade.
        var d = AutonomyGate.evaluate("release_bond", "release the bond",
            grantsFor("release_bond"), false, COMPANION, OWNER);
        assertThat(allowed(d)).isTrue();
    }

    @Test
    void emergency_call_is_safety_floored_never_blocked() {
        // emergency_call is CONSENT in AUTONOMY_TIERS, but the gate floors it:
        // a strict-grants household must not consent-lock the phone while the
        // bondholder is unresponsive (fall detection fires autonomously).
        var d = AutonomyGate.evaluate("emergency_call", "place an emergency call",
            grantsFor(), true, COMPANION, OWNER);
        assertThat(allowed(d)).isTrue();
    }

    @Test
    void unknown_verbs_default_to_consent() {
        // ActionPolicy.autonomyTierFor defaults unlisted verbs to CONSENT.
        assertThat(allowed(AutonomyGate.evaluate("mystery_verb", "do a mystery",
            grantsFor(), false, COMPANION, OWNER))).isTrue();
        assertThat(AutonomyGate.evaluate("mystery_verb", "do a mystery",
            grantsFor(), true, COMPANION, OWNER))
            .isInstanceOf(AutonomyGate.Decision.Deny.class);
    }

    @Test
    void missing_owner_or_check_means_no_grant() {
        // FORBIDDEN with nobody to ask: denied, template falls back to wyrd:action/.
        var d = AutonomyGate.evaluate("release_bond", "release the bond",
            null, false, COMPANION, null);
        assertThat(d).isInstanceOf(AutonomyGate.Decision.Deny.class);
        assertThat(((AutonomyGate.Decision.Deny) d).denial().inWorldResolution().source())
            .isEqualTo("wyrd:action/release_bond");
        // CONSENT under strict with no owner: also denied (no way to consent).
        assertThat(AutonomyGate.evaluate("teleport_to", "teleport",
            grantsFor("teleport_to"), true, COMPANION, null))
            .isInstanceOf(AutonomyGate.Decision.Deny.class);
    }
}

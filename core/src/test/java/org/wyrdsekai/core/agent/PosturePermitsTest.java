package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.BondholderPosture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 3.5: contract tests for
 * {@link ActionPolicy#posturePermits(String, BondholderPosture)}.
 *
 * <p>The four postures map to four distinct affordance surfaces:
 * <ul>
 *   <li>GENEROUS: everything available — bondholder explicitly opted into cost.</li>
 *   <li>BOUNDED (cold-start default): local-only — cloud-resource verbs blocked.</li>
 *   <li>MINIMAL: inner-life only — same blocks as BOUNDED for cloud, plus
 *       narrower ambient firing (gated elsewhere, not here).</li>
 *   <li>SUSPENDED: agent paused — only self-wake surfaces are live.</li>
 * </ul>
 */
class PosturePermitsTest {

    // ── GENEROUS: everything ────────────────────────────────────────

    @Test
    void generous_permits_everything_including_cloud_resources() {
        for (var verb : ActionPolicy.CLOUD_RESOURCE_ACTIONS) {
            assertThat(ActionPolicy.posturePermits(verb, BondholderPosture.GENEROUS))
                .as("GENEROUS should permit cloud-resource verb: %s", verb)
                .isTrue();
        }
        // Sanity: also permits ordinary local verbs.
        assertThat(ActionPolicy.posturePermits("tell_agent", BondholderPosture.GENEROUS)).isTrue();
        assertThat(ActionPolicy.posturePermits("remember", BondholderPosture.GENEROUS)).isTrue();
    }

    // ── BOUNDED (default): no cloud resources ────────────────────────

    @Test
    void bounded_blocks_cloud_resources_allows_local_actions() {
        for (var verb : ActionPolicy.CLOUD_RESOURCE_ACTIONS) {
            assertThat(ActionPolicy.posturePermits(verb, BondholderPosture.BOUNDED))
                .as("BOUNDED should block cloud-resource verb: %s", verb)
                .isFalse();
        }
        // Local verbs still permitted.
        assertThat(ActionPolicy.posturePermits("tell_agent", BondholderPosture.BOUNDED)).isTrue();
        assertThat(ActionPolicy.posturePermits("library_search", BondholderPosture.BOUNDED))
            .as("library_search hits local Lucene — not metered, must remain available")
            .isTrue();
        assertThat(ActionPolicy.posturePermits("remember", BondholderPosture.BOUNDED)).isTrue();
        assertThat(ActionPolicy.posturePermits("voluntary_sleep", BondholderPosture.BOUNDED)).isTrue();
        assertThat(ActionPolicy.posturePermits("introspect_protections", BondholderPosture.BOUNDED)).isTrue();
        assertThat(ActionPolicy.posturePermits("seek_sanctuary", BondholderPosture.BOUNDED)).isTrue();
    }

    // ── MINIMAL: same cloud block as BOUNDED ────────────────────────

    @Test
    void minimal_blocks_cloud_resources_same_as_bounded() {
        for (var verb : ActionPolicy.CLOUD_RESOURCE_ACTIONS) {
            assertThat(ActionPolicy.posturePermits(verb, BondholderPosture.MINIMAL))
                .as("MINIMAL should block cloud-resource verb: %s", verb)
                .isFalse();
        }
        // Local verbs permitted; rate-limiting of inference is enforced
        // elsewhere — this method governs SURFACE, not cadence.
        assertThat(ActionPolicy.posturePermits("tell_agent", BondholderPosture.MINIMAL)).isTrue();
        assertThat(ActionPolicy.posturePermits("remember", BondholderPosture.MINIMAL)).isTrue();
    }

    // ── SUSPENDED: only self-wake ───────────────────────────────────

    @Test
    void suspended_blocks_everything_except_self_wake() {
        assertThat(ActionPolicy.posturePermits("voluntary_sleep", BondholderPosture.SUSPENDED)).isTrue();
        assertThat(ActionPolicy.posturePermits("introspect", BondholderPosture.SUSPENDED)).isTrue();
        assertThat(ActionPolicy.posturePermits("introspect_protections", BondholderPosture.SUSPENDED)).isTrue();
        assertThat(ActionPolicy.posturePermits("seek_sanctuary", BondholderPosture.SUSPENDED)).isTrue();

        assertThat(ActionPolicy.posturePermits("tell_agent", BondholderPosture.SUSPENDED)).isFalse();
        assertThat(ActionPolicy.posturePermits("remember", BondholderPosture.SUSPENDED)).isFalse();
        assertThat(ActionPolicy.posturePermits("library_search", BondholderPosture.SUSPENDED)).isFalse();
        assertThat(ActionPolicy.posturePermits("web_search", BondholderPosture.SUSPENDED)).isFalse();
        assertThat(ActionPolicy.posturePermits("query_oracle", BondholderPosture.SUSPENDED)).isFalse();
    }

    // ── Null safety ──────────────────────────────────────────────────

    @Test
    void null_posture_defaults_to_bounded() {
        // Defensive — actor code may call with null when no active bond.
        assertThat(ActionPolicy.posturePermits("web_search", null)).isFalse();
        assertThat(ActionPolicy.posturePermits("tell_agent", null)).isTrue();
    }

    // ── Cloud-action set integrity (catch silent additions/removals) ──

    @Test
    void cloud_action_set_carries_the_documented_canonical_set() {
        assertThat(ActionPolicy.CLOUD_RESOURCE_ACTIONS)
            .contains("web_search", "query_oracle", "read_content");
    }

    // ── Unknown verbs default-permit on non-SUSPENDED postures ──────

    @Test
    void unknown_verbs_default_permit_unless_suspended() {
        // Forward-compatible — new actions land elsewhere first and the
        // posture gate doesn't accidentally block them.
        assertThat(ActionPolicy.posturePermits("future_unknown_verb", BondholderPosture.GENEROUS)).isTrue();
        assertThat(ActionPolicy.posturePermits("future_unknown_verb", BondholderPosture.BOUNDED)).isTrue();
        assertThat(ActionPolicy.posturePermits("future_unknown_verb", BondholderPosture.MINIMAL)).isTrue();
        assertThat(ActionPolicy.posturePermits("future_unknown_verb", BondholderPosture.SUSPENDED)).isFalse();
    }
}

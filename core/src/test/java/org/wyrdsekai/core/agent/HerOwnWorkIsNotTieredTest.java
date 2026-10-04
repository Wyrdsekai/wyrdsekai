package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Her own work carries no tier; acting on what is shared still does.
 *
 * <p>operator, 2026-10-03: "wait why are we tiering these in the household at all" and "drop the tier
 * for self-directed work - yes we need this now". The tier was a judgment-earned-per-domain ladder
 * for agents deciding on a household's behalf; applied to her own making and learning it protected
 * nothing the consent axis, the sandbox and the budget did not already bound.
 */
class HerOwnWorkIsNotTieredTest {

    @Test
    void the_workshop_crafting_scripts_skills_and_rooms_are_hers_from_the_first_day() {
        for (var verb : ActionPolicy.SELF_DIRECTED) {
            assertThat(ActionPolicy.forAction(verb).requiredTier()).as(verb).isZero();
        }
        assertThat(ActionPolicy.SELF_DIRECTED).contains("dispatch_bunshin", "workbench_submit", "craft_from_template",
            "run_script", "skill_execute", "create_room", "shape_recipe", "request_access");
    }

    @Test
    void acting_on_what_is_shared_keeps_its_tier() {
        for (var verb : ActionPolicy.ACTS_ON_THE_SHARED) {
            assertThat(ActionPolicy.forAction(verb).requiredTier()).as(verb).isGreaterThanOrEqualTo(2);
        }
        assertThat(ActionPolicy.SELF_DIRECTED).doesNotContainAnyElementsOf(ActionPolicy.ACTS_ON_THE_SHARED);
    }

    @Test
    void what_she_may_do_without_checking_in_is_still_the_consent_axis() {
        // The tier is gone; the other limits are not.
        assertThat(ActionPolicy.autonomyTierFor("dispatch_bunshin")).isEqualTo(ActionPolicy.AutonomyTier.CONSENT);
        assertThat(ActionPolicy.autonomyTierFor("create_room")).isEqualTo(ActionPolicy.AutonomyTier.CONSENT);
        assertThat(ActionPolicy.forAction("dispatch_bunshin").budgetCost()).isGreaterThan(0.0);
    }
}

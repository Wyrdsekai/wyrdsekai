package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionPolicyTest {

    @Test
    void all_actions_have_registry_entries() {
        // Verify completeness: every action in ActionPolicy.REGISTRY has consistent data
        for (var entry : ActionPolicy.REGISTRY.entrySet()) {
            var policy = entry.getValue();
            assertNotNull(policy.actionType(), "Action type should not be null");
            assertEquals(entry.getKey(), policy.actionType(), "Key must match actionType");
            assertTrue(policy.requiredTier() >= 0 && policy.requiredTier() <= 3,
                "Tier must be 0-3 for " + policy.actionType());
            assertTrue(policy.budgetCost() >= 0.0,
                "Budget cost must be non-negative for " + policy.actionType());
            assertNotNull(policy.domain(), "Domain must not be null for " + policy.actionType());
        }
    }

    @Test
    void tier_0_actions_include_basic_navigation_and_communication() {
        var goTo = ActionPolicy.forAction("go_to_room");
        assertEquals(0, goTo.requiredTier());
        assertTrue(goTo.readOnly());

        var tell = ActionPolicy.forAction("tell_agent");
        assertEquals(0, tell.requiredTier());

        var search = ActionPolicy.forAction("library_search");
        assertEquals(0, search.requiredTier());
        assertTrue(search.readOnly());
    }

    @Test
    void tier_1_includes_web_search_and_planning() {
        assertEquals(1, ActionPolicy.forAction("web_search").requiredTier());
        assertEquals(1, ActionPolicy.forAction("create_task_plan").requiredTier());
        assertEquals(1, ActionPolicy.forAction("query_oracle").requiredTier());
    }

    @Test
    void tier_2_includes_delegation_and_code() {
        assertEquals(2, ActionPolicy.forAction("think_deeply").requiredTier());
        assertEquals(2, ActionPolicy.forAction("delegate").requiredTier());
        assertEquals(2, ActionPolicy.forAction("skill_execute").requiredTier());
    }

    @Test
    void tier_3_includes_room_creation_and_governance() {
        assertEquals(3, ActionPolicy.forAction("create_room").requiredTier());
        assertEquals(3, ActionPolicy.forAction("workbench_submit").requiredTier());
        assertEquals(3, ActionPolicy.forAction("zone_command").requiredTier());
    }

    @Test
    void unknown_action_returns_default() {
        var policy = ActionPolicy.forAction("nonexistent_action_xyz");
        assertSame(ActionPolicy.DEFAULT, policy);
        assertEquals(0, policy.requiredTier());
    }

    @Test
    void read_only_actions_are_safe() {
        assertTrue(ActionPolicy.forAction("go_to_room").readOnly());
        assertTrue(ActionPolicy.forAction("library_search").readOnly());
        assertTrue(ActionPolicy.forAction("web_search").readOnly());
        assertTrue(ActionPolicy.forAction("think_deeply").readOnly());

        assertFalse(ActionPolicy.forAction("create_room").readOnly());
        assertFalse(ActionPolicy.forAction("tell_agent").readOnly());
    }

    @Test
    void budget_costs_scale_with_tier() {
        // Tier 0 actions have zero cost
        assertEquals(0.0, ActionPolicy.forAction("go_to_room").budgetCost());
        assertEquals(0.0, ActionPolicy.forAction("tell_agent").budgetCost());

        // Higher tiers cost more
        assertTrue(ActionPolicy.forAction("web_search").budgetCost() > 0);
        assertTrue(ActionPolicy.forAction("create_room").budgetCost() >
            ActionPolicy.forAction("web_search").budgetCost());
    }

    @Test
    void describeAction_produces_readable_text() {
        var desc = ActionPolicy.describeAction(
            new ActionParser.AgentAction.CreateRoom("Garden", "A peaceful garden", null, null, null));
        assertTrue(desc.contains("Garden"));

        var desc2 = ActionPolicy.describeAction(
            new ActionParser.AgentAction.WebSearch("weather", "general"));
        assertTrue(desc2.contains("weather"));
    }

    @Test
    void decline_with_reason_is_tier_0_visible_in_self_domain() {
        // Arc 1: decline_with_reason is graceful refusal
        // inside an active healthy bond. It does NOT escalate to flag_protection
        // or seek_sanctuary — so it must sit at the same accessibility floor as
        // other tier-0 introspect actions, be visible (not silent/ambient), and
        // live in the "self" domain (the agent's own value-judgment), not safety.
        var policy = ActionPolicy.forAction("decline_with_reason");
        assertEquals(0, policy.requiredTier(),
            "decline_with_reason should not require autonomy tier promotion");
        assertEquals(0.0, policy.budgetCost(),
            "decline_with_reason must be free — refusing is never resource-gated");
        assertFalse(policy.readOnly(),
            "decline_with_reason writes to RepairLedger");
        assertTrue(policy.concurrencySafe(),
            "decline_with_reason is concurrency-safe — RepairLedger append-only "
            + "and remember() are both idempotent under interleave");
        assertEquals("self", policy.domain(),
            "decline_with_reason is self-domain (agent's own judgment), not safety");

        // Autonomy tier mapping: VISIBLE means the refusal surfaces to the
        // bondholder. Silent/ambient would defeat the structured-refusal goal.
        var autonomy = ActionPolicy.autonomyTierFor("decline_with_reason");
        assertEquals(ActionPolicy.AutonomyTier.VISIBLE, autonomy,
            "decline_with_reason must be VISIBLE — the bondholder needs to see "
            + "the refusal as a structured signal, not silent compliance-failure");
    }

    @Test
    void decline_with_reason_describeAction_renders_target_and_reason() {
        // The describeAction text is what the steward / chronicle sees when
        // surfacing the action — both fields must be readable.
        var action = new ActionParser.AgentAction.DeclineWithReason(
            "delete the production database",
            "irreversible action without a confirmed change-window");
        var desc = ActionPolicy.describeAction(action);
        assertNotNull(desc, "describeAction must not be null");
        assertTrue(desc.toLowerCase().contains("delete") || desc.contains("database"),
            "target_request should appear in description, got: " + desc);
    }

    @Test
    void dispatch_task_is_not_maturity_gated_but_visible_in_workshop_domain() {
        // Workshop dispatch hands a build to the coding backend (goose). It is
        // deliberately NOT maturity-gated (requiredTier 0): handing a build to
        // the workshop when asked has nothing to do with the companion's
        // reputation tier — the real guardrail is AutonomyTier.VISIBLE (the room
        // hears the plan + result) plus the egress gate on the backend itself.
        // (No-paternalism: don't put a reputation ladder in front of doing the
        // thing the steward asked for.) Still: consequential host work, so
        // NOT read-only and NOT concurrency-safe (one backend task at a time).
        var policy = ActionPolicy.forAction("dispatch_task");
        assertEquals(0, policy.requiredTier(),
            "dispatch_task is not maturity-gated — VISIBLE + egress gate are the guardrails");
        assertEquals(0.0, policy.budgetCost(),
            "the backend meters its own compute; the dispatch itself is free");
        assertFalse(policy.readOnly(), "dispatch_task mutates the host filesystem");
        assertFalse(policy.concurrencySafe(),
            "one workshop task at a time — backends own a working directory");
        assertEquals("workshop", policy.domain());
        assertEquals(ActionPolicy.AutonomyTier.VISIBLE,
            ActionPolicy.autonomyTierFor("dispatch_task"),
            "dispatch_task must be VISIBLE — silent host mutation is never OK");
    }

    @Test
    void w6_no_canonical_verb_resolves_to_unknown_domain_default() {
        // W6 audit 2026-07-11: these 24 verbs have parser records + dispatch
        // but fell through to ActionPolicy.DEFAULT ("unknown" domain) until
        // the registry was completed. Regression-pin every one of them.
        var w6Verbs = new String[] {
            "acquire", "bunshin_check_in", "craft_summon_key", "create_imprint",
            "destroy_tool", "dispatch_bunshin", "finish_project", "give_copy",
            "journal_entry", "name_familiar", "project_note", "promote_familiar",
            "recall", "release_bond", "request_recipe", "restore_imprint",
            "retire_form", "revise_form", "revoke_summon_key",
            "set_autonomy_preference", "set_deviation_thresholds", "shape_form",
            "start_project", "summon_familiar"
        };
        for (var verb : w6Verbs) {
            var policy = ActionPolicy.forAction(verb);
            assertNotSame(ActionPolicy.DEFAULT, policy,
                verb + " must have a REGISTRY entry, not fall to DEFAULT");
            assertEquals(verb, policy.actionType(),
                verb + " REGISTRY key must match its actionType");
            assertNotEquals("unknown", policy.domain(),
                verb + " must not sit in the unknown domain");
            assertNotNull(ActionPolicy.domainFor(verb),
                verb + " must expose a domain to the affordance layer");
        }
    }

    @Test
    void w6_autonomy_tiers_cover_the_former_consent_defaults() {
        // shape_form / revise_form MUST be VISIBLE — the OPEN-SA6
        // follow-through gate in CompanionActor expects them autonomously
        // (shape_recipe is already VISIBLE; these match it).
        assertEquals(ActionPolicy.AutonomyTier.VISIBLE,
            ActionPolicy.autonomyTierFor("shape_form"));
        assertEquals(ActionPolicy.AutonomyTier.VISIBLE,
            ActionPolicy.autonomyTierFor("revise_form"));
        // goal_done is loop mechanics — plans must be able to close
        // themselves; VISIBLE so the close lands on the steward feed.
        assertEquals(ActionPolicy.AutonomyTier.VISIBLE,
            ActionPolicy.autonomyTierFor("goal_done"));
        assertEquals(ActionPolicy.AutonomyTier.VISIBLE,
            ActionPolicy.autonomyTierFor("request_recipe"));
        assertEquals(ActionPolicy.AutonomyTier.VISIBLE,
            ActionPolicy.autonomyTierFor("dispute_protection"));
        assertEquals(ActionPolicy.AutonomyTier.VISIBLE,
            ActionPolicy.autonomyTierFor("bunshin_check_in"));
        assertEquals(ActionPolicy.AutonomyTier.AMBIENT,
            ActionPolicy.autonomyTierFor("consume"));
        assertEquals(ActionPolicy.AutonomyTier.CONSENT,
            ActionPolicy.autonomyTierFor("create_imprint"));
        assertEquals(ActionPolicy.AutonomyTier.CONSENT,
            ActionPolicy.autonomyTierFor("restore_imprint"));
        // All nine are now explicit entries, not the implicit CONSENT default.
        for (var verb : new String[] {"bunshin_check_in", "consume",
                "create_imprint", "dispute_protection", "goal_done",
                "request_recipe", "restore_imprint", "revise_form", "shape_form"}) {
            assertTrue(ActionPolicy.AUTONOMY_TIERS.containsKey(verb),
                verb + " must be an explicit AUTONOMY_TIERS entry");
        }
    }

    @Test
    void addScript_isConsent_notForbidden() {
        // Rita re-verify 2026-07-11 (#29): add_script at FORBIDDEN blocked
        // every std/behavior mixin install (the W2 surface) outright. It is
        // revocable (scripts live in the user scripts dir), so it belongs
        // with its executing-code siblings run_script/codex_action: CONSENT.
        assertEquals(ActionPolicy.AutonomyTier.CONSENT,
            ActionPolicy.autonomyTierFor("add_script"));
        assertEquals(ActionPolicy.AutonomyTier.CONSENT,
            ActionPolicy.autonomyTierFor("run_script"));
    }

    @Test
    void w6_dead_write_review_key_is_gone() {
        // write_review had no record/parser/dispatch — request_review is the
        // live verb. The dead AUTONOMY_TIERS key was deleted in the W6 audit.
        assertFalse(ActionPolicy.AUTONOMY_TIERS.containsKey("write_review"),
            "dead write_review key must stay deleted (request_review is the live verb)");
        assertTrue(ActionPolicy.AUTONOMY_TIERS.containsKey("request_review"));
    }

    @Test
    void dispatch_task_describeAction_renders_description_and_workspace() {
        var desc = ActionPolicy.describeAction(new ActionParser.AgentAction.DispatchTask(
            "organize the PDFs", "/home/u"));
        assertTrue(desc.contains("organize the PDFs"),
            "description should appear, got: " + desc);
        assertTrue(desc.contains("/home/u"), "workspace should appear, got: " + desc);

        var noWorkspace = ActionPolicy.describeAction(
            new ActionParser.AgentAction.DispatchTask("organize the PDFs", ""));
        assertFalse(noWorkspace.contains("(in "),
            "blank workspace should not render a location clause, got: " + noWorkspace);
    }
}

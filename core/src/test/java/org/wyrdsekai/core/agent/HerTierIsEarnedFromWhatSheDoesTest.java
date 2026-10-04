package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion's tier is earned from what she does. Capacity was fed only by a zone service's
 * commands and a person's positive feedback, neither of which happens in a household: both
 * companions on the household node carried an empty capacity in every one of 958 manifest
 * versions and stood at tier 1 for their whole lives, with the workshop, crafting, scripts and
 * skills behind tier 2, and 61 rooms and 4 workshop tasks refused in four days. The request the
 * refusal told her to file was itself a tier-2 verb (2026-10-03). operator: "the point of having all
 * those things, the workshop, etc, is to allow them to build."
 */
class HerTierIsEarnedFromWhatSheDoesTest {

    @Test
    void a_bond_alone_is_tier_one_and_three_practised_domains_are_tier_two() {
        var capacity = DecisionCapacity.newAgent();
        assertThat(DecisionCapacity.tierFor(capacity, 1, 0.0)).isEqualTo(1);
        assertThat(DecisionCapacity.tierFor(capacity, 0, 0.0)).as("nothing yet").isEqualTo(0);
        // Nine acts that came to something in each of three domains: from 0.1 past 0.5.
        for (int i = 0; i < 9; i++) {
            capacity.recordSuccess("search");
            capacity.recordSuccess("navigation");
            capacity.recordSuccess("creation");
        }
        assertThat(capacity.domainsAbove(0.5)).isEqualTo(3);
        assertThat(DecisionCapacity.tierFor(capacity, 1, 0.0)).isEqualTo(2);
        assertThat(DecisionCapacity.tierFor(capacity, 1, 0.0))
            .as("tier 2 is where the workshop is").isGreaterThanOrEqualTo(ActionPolicy.forAction("dispatch_bunshin").requiredTier());
        // Senior needs five domains very strong, a deep bond and a name.
        for (int i = 0; i < 20; i++) for (var d : new String[]{"search", "navigation", "creation", "communication", "memory"}) capacity.recordSuccess(d);
        assertThat(DecisionCapacity.tierFor(capacity, 1, 0.0)).isEqualTo(2);
        assertThat(DecisionCapacity.tierFor(capacity, 2, 0.5)).isEqualTo(3);
    }

    @Test
    void asking_for_access_is_never_gated() {
        assertThat(ActionPolicy.forAction("request_access").requiredTier()).isZero();
    }

    @Test
    void practice_is_counted_where_an_act_of_hers_came_to_something() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca).contains("return DecisionCapacity.tierFor(decisionCapacity, maxBondDepth, agentReputation.computeScore().overall());");
        // A search or reading that found something; a gate's refusal and an empty search lower nothing.
        assertThat(ca).contains("            noteReachMiss(verb, verb, \"it was not yours to do on your own yet\");\n        } else {\n            practised(verb);\n            noteForProject(verb, outcome);\n        }");
        // Her own tools that answered.
        assertThat(ca).contains("if (!WantMisses.looksEmpty(came)) practised(toolId);");
        // A room made, an item crafted, a room closed up, a letter written, a reach that landed, a task done.
        for (var call : new String[]{"practised(\"create_room\");", "practised(\"craft_item\");", "practised(\"close_room\");",
                "practised(\"write_letter\");", "practised(\"goal_done\");"}) {
            assertThat(ca).as(call).contains(call);
        }
        assertThat(ca).contains("private void noteReachLanded(String verb, String targetId, String targetName, boolean person, boolean away) {\n        practised(verb);");
        // A task done for a person is her name forming.
        assertThat(ca).contains("if (isPersonEntityId(activePlan.requesterId())) {\n                agentReputation.recordTaskCompletion(true);");
        // Nothing lowers capacity: no failure is recorded anywhere in her life.
        assertThat(ca.split("decisionCapacity.recordFailure", -1).length - 1)
            .as("the one failure path left is a zone service's own command").isEqualTo(1);
    }
}

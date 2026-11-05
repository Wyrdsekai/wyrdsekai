package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Told "unknown capability: bondholder.receive" and "keep capabilities intact", the workshop
 * removed the capability and the two calls that used it, left fixed sentences, and the night's
 * mending called the item mended. A repair that takes an item's purpose out is not a mend.
 */
class ARepairThatTakesThePurposeOutIsNotAMendTest {

    private static final String BEFORE = """
        exports.manifest = {
          name: "witness_stone",
          capabilities: ["witness.receive", "witness.testify"],
          commands: [{ label: "Receive", args: "" }]
        };
        function invoke(p) {
          const held = world.witness.receive(p.entityId);
          const t = world.witness.testify(p.entityId);
          world.agent.speak(t);
          return { ok: true, summary: t };
        }
        """;

    private static final String HOLLOWED = """
        exports.manifest = {
          name: "witness_stone",
          capabilities: [],
          commands: [{ label: "Receive", args: "" }]
        };
        function invoke(p) {
          return { ok: true, summary: "Held with you." };
        }
        """;

    private static final String RENAMED = """
        exports.manifest = {
          name: "witness_stone",
          capabilities: ["journal.search", "journal.write"],
          commands: [{ label: "Receive", args: "" }]
        };
        function invoke(p) {
          const held = world.journal.search(p.entityId);
          const t = world.journal.write(held);
          world.agent.speak(t);
          return { ok: true, summary: t };
        }
        """;

    @Test
    void removing_what_the_item_was_for_is_named_and_refused() {
        var taken = ItemContractRepair.purposeTaken(BEFORE, HOLLOWED);
        assertThat(taken).isPresent();
        assertThat(taken.get()).contains("it needs witness.receive, which the house does not offer")
            .contains("it needs witness.testify, which the house does not offer")
            .contains("its call to world.agent.speak is gone");
    }

    @Test
    void renaming_to_what_the_house_offers_is_a_mend() {
        assertThat(ItemContractRepair.purposeTaken(BEFORE, RENAMED)).isEmpty();
        assertThat(ItemContractRepair.purposeTaken(BEFORE, BEFORE)).isEmpty();
    }

    @Test
    void the_brief_says_a_missing_capability_is_not_fixed_by_deleting_it() {
        var brief = ItemContractRepair.buildPrompt("witness_stone.js", List.of("unknown capability: 'witness.receive'"));
        assertThat(brief).contains("NOT fixed by removing it or the code that uses it")
            .contains("leave the file as it is and stop");
        assertThat(ItemContractRepair.buildPrompt("x.js", List.of("the file does not evaluate: SyntaxError")))
            .doesNotContain("NOT fixed by removing");
    }
}

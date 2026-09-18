package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An item that is already in her world and broken is repaired on a copy. The placed file
 * changes only when the copy comes out with no problems at all, and the version it replaces
 * is kept. A repair that fails, or makes things worse, leaves her item exactly as it was.
 */
class APlacedItemIsRepairedOnACopyTest {

    private static final String BROKEN = item("""
          var a = (params.args || "").trim();
          if (a === "recall") { return { ok: true, text: String(world.memory.get("bondholder")) }; }
          return { ok: true, text: "The mirror is quiet." };
        """);
    private static final String WHOLE = item("""
          var a = (params.args || "").trim();
          if (a === "recall") { world.memory.add("She looked into the mirror."); return { ok: true, text: "Kept." }; }
          return { ok: true, text: "The mirror is quiet." };
        """);

    private static String item(String body) {
        return """
            exports.manifest = {
              name: "bondholder_mirror",
              version: "1.0.0",
              description: "Shows the bondholder what she remembers of them.",
              author: "did:wyrd:codezaiku",
              capabilities: ["memory.add"],
              rate_limits: { "memory.add": "10/min" },
              embodiment: { silent: false, emits: ["body_language"], descriptor_template: "{actor} looks into the mirror" },
              commands: [ { label: "Look", args: "" }, { label: "Recall", args: "recall" } ]
            };
            function invoke(params) {
            %s
            }
            """.formatted(body);
    }

    @AfterEach
    void tearDown() { ItemContractRepair.setEscalation(null); }

    @Test
    @DisplayName("a copy is repaired; the placed file is replaced only when the copy is whole, and the old version is kept")
    void repairedOnACopy(@TempDir Path items) throws Exception {
        var placed = items.resolve("bondholder_mirror.js");
        Files.writeString(placed, BROKEN);
        assertThat(ItemContractCheck.problems(BROKEN, "bondholder_mirror.js")).isNotEmpty();
        assertThat(ItemContractCheck.problems(WHOLE, "bondholder_mirror.js")).as("the fixture the fake backend writes is itself whole").isEmpty();

        var prompts = new ArrayList<String>();
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            prompts.add(prompt);
            assertThat(workspace).as("the work happens away from her world").isNotEqualTo(items);
            try { Files.writeString(workspace.resolve("bondholder_mirror.js"), WHOLE); } catch (Exception e) { return false; }
            return true;
        });
        var r = ItemContractRepair.repairPlaced(placed);
        assertThat(r.fixed()).as(r.note() + " " + r.after()).isTrue();
        assertThat(prompts.get(0)).contains("world.memory.get does not exist");
        assertThat(Files.readString(placed)).isEqualTo(WHOLE);
        try (var kept = Files.list(items.resolve(".repaired"))) {
            var bak = kept.toList();
            assertThat(bak).hasSize(1);
            assertThat(Files.readString(bak.get(0))).isEqualTo(BROKEN);
        }
    }

    @Test
    @DisplayName("a repair that does not make it whole leaves her item exactly as it was")
    void unchangedWhenNotWhole(@TempDir Path items) throws Exception {
        var placed = items.resolve("bondholder_mirror.js");
        Files.writeString(placed, BROKEN);
        ItemContractRepair.setEscalation((workspace, prompt) -> true);   // the backend "completes" and changes nothing
        var r = ItemContractRepair.repairPlaced(placed);
        assertThat(r.fixed()).isFalse();
        assertThat(r.after()).isNotEmpty();
        assertThat(Files.readString(placed)).isEqualTo(BROKEN);
        assertThat(Files.exists(items.resolve(".repaired"))).isFalse();

        ItemContractRepair.setEscalation(null);
        assertThat(ItemContractRepair.repairPlaced(placed).note()).contains("no coding backend");
        Files.writeString(placed, WHOLE);
        assertThat(ItemContractRepair.repairPlaced(placed).note()).isEqualTo("nothing to repair");
    }
}

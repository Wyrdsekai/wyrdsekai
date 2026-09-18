package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemManifestParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An item is not finished if it calls a part of the world that does not exist. On 2026-09-17
 * the coding backend built a companion a mirror that called {@code world.memory.get} and
 * {@code world.memory.list}; the item memory API has neither. The gate an item passes before
 * it is placed said "compliant", the item was placed as finished, and it failed the first
 * time she used it. The loader's audit and {@code wyrd items check} already knew. The gate now
 * asks the same question, on every path through the script, and its message names what the
 * API does offer, so the repair loop can act on it.
 */
class AnItemThatCallsWhatDoesNotExistIsNotFinishedTest {

    private static String item(String body, String commands) {
        return """
            exports.manifest = {
              name: "bondholder_mirror",
              version: "1.0.0",
              description: "Shows the bondholder what she remembers of them.",
              author: "did:wyrd:codezaiku",
              capabilities: ["memory.add"],
              rate_limits: { "memory.add": "10/min" },
              embodiment: {
                silent: false,
                emits: ["body_language"],
                descriptor_template: "{actor} looks into the mirror"
              },
              commands: %s
            };
            function invoke(params) {
            %s
            }
            """.formatted(commands, body);
    }

    @Test
    @DisplayName("a call to a member the API does not have is a contract problem that names what exists")
    void callsWhatDoesNotExist() {
        var script = item("""
              var a = (params.args || "").trim();
              if (a === "recall") { return { ok: true, text: String(world.memory.get("bondholder")) }; }
              return { ok: true, text: "The mirror is quiet." };
            """, "[ { label: \"Look\", args: \"\" }, { label: \"Recall\", args: \"recall\" } ]");
        var problems = ItemContractCheck.problems(script, "bondholder_mirror");
        assertThat(problems).anySatisfy(p -> assertThat(p)
            .contains("world.memory.get does not exist")
            .contains("world.memory has:")
            .contains("add"));
        assertThat(ItemContractCheck.isCompliant(script, "bondholder_mirror"))
            .as("the smoke takes the first command and never reaches the recall branch; the gate must not depend on it")
            .isFalse();
    }

    @Test
    @DisplayName("the same item using what exists passes the wiring questions")
    void usesWhatExists() {
        var script = item("""
              var a = (params.args || "").trim();
              if (a === "keep") { world.memory.add("She looked into the mirror today."); return { ok: true, text: "Kept." }; }
              return { ok: true, text: "The mirror is quiet." };
            """, "[ { label: \"Look\", args: \"\" }, { label: \"Keep\", args: \"keep\" } ]");
        assertThat(ItemContractCheck.wiringProblems(script, ItemManifestParser.parse(script))).isEmpty();
    }

    @Test
    @DisplayName("commands the manifest declares and invoke() never reads are a contract problem too")
    void commandsNeverHonoured() {
        var script = item("  return { ok: true, text: \"The mirror is quiet.\" };",
            "[ { label: \"Look\", args: \"\" }, { label: \"Recall\", args: \"recall\" } ]");
        assertThat(ItemContractCheck.problems(script, "bondholder_mirror"))
            .anySatisfy(p -> assertThat(p).contains("never reads params.args"));
    }
}

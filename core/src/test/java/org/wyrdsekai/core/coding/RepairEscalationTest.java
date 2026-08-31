package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The escalation round: when a backend's own repair rounds exhaust, one more
 * turn goes through the escalation backend (codezaiku, wired at bootstrap) —
 * under the SAME revert-if-worse guard, and never when the escalation backend
 * is the one whose repair just failed.
 *
 * <p>Measured basis (2026-08-27, home-server, the household 4B): goose's two rounds
 * shipped an invoke()-crash to a person; the codezaiku harness fixed the same
 * file on the same model in one round.
 */
class RepairEscalationTest {

    @TempDir Path workspace;

    private static final Instant START = Instant.now().minusSeconds(5);

    private static final String MISSING_COMMANDS = """
        exports.manifest = {
          name: "teller", version: "1.0.0",
          description: "Tells a short story.", author: "did:wyrd:backend",
          capabilities: [],
          embodiment: { silent: false, emits: ["body_language"],
                        descriptor_template: "{actor} speaks" }
        };
        function invoke(params) { return { ok: true }; }
        """;

    private static final String COMPLIANT = """
        exports.manifest = {
          name: "teller", version: "1.0.0",
          description: "Tells a short story.", author: "did:wyrd:backend",
          capabilities: [],
          embodiment: { silent: false, emits: ["body_language"],
                        descriptor_template: "{actor} speaks" },
          commands: [ { label: "Tell a story", args: "<topic>" } ]
        };
        function invoke(params) { return { ok: true }; }
        """;

    /** Broken in a way the fake backends never fix — the escalation's cue. */
    private Path brokenFile() throws Exception {
        var f = workspace.resolve("teller.js");
        Files.writeString(f, MISSING_COMMANDS);
        return f;
    }

    @AfterEach
    void clearEscalation() {
        ItemContractRepair.setEscalation(null);
    }

    @Test
    void an_exhausted_repair_gets_one_escalation_round() throws Exception {
        var f = brokenFile();
        var escalations = new ArrayList<String>();
        ItemContractRepair.setEscalation((ws, prompt) -> {
            escalations.add(prompt);
            try {
                Files.writeString(f, COMPLIANT);   // the stronger harness fixes it
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        });
        var taskId = UUID.randomUUID().toString();
        ItemContractRepair.repair(workspace, taskId, START, prompt -> true /* backend "runs", fixes nothing */);

        assertThat(escalations).as("exactly one escalation round").hasSize(1);
        assertThat(escalations.getFirst()).contains("commands");
        assertThat(ItemContractCheck.problems(Files.readString(f), "teller.js")).isEmpty();
        assertThat(ItemContractRepair.consumeUnresolved(taskId))
            .as("a fixed file leaves nothing unresolved").isEmpty();
    }

    @Test
    void an_escalation_that_makes_the_file_worse_is_reverted() throws Exception {
        var f = brokenFile();
        var before = Files.readString(f);
        ItemContractRepair.setEscalation((ws, prompt) -> {
            try {
                Files.writeString(f, "this is not an item at all");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        });
        var taskId = UUID.randomUUID().toString();
        ItemContractRepair.repair(workspace, taskId, START, prompt -> true);

        assertThat(Files.readString(f))
            .as("the pre-escalation version is restored").isEqualTo(before);
        assertThat(ItemContractRepair.consumeUnresolved(taskId))
            .as("the original problems are recorded, not the escalation's").isNotEmpty();
    }

    @Test
    void the_escalation_backend_does_not_escalate_to_itself() throws Exception {
        brokenFile();
        var escalations = new ArrayList<String>();
        ItemContractRepair.setEscalation((ws, prompt) -> {
            escalations.add(prompt);
            return true;
        });
        var taskId = UUID.randomUUID().toString();
        ItemContractRepair.withoutEscalation(() ->
            ItemContractRepair.repair(workspace, taskId, START, prompt -> true));

        assertThat(escalations).as("no self-escalation").isEmpty();
        assertThat(ItemContractRepair.consumeUnresolved(taskId)).isNotEmpty();
    }

    @Test
    void unresolved_problems_are_recorded_and_consumed_once() throws Exception {
        brokenFile();
        var taskId = UUID.randomUUID().toString();
        ItemContractRepair.repair(workspace, taskId, START, prompt -> true);

        var first = ItemContractRepair.consumeUnresolved(taskId);
        assertThat(first).isNotEmpty();
        assertThat(first.getFirst()).contains("commands");
        assertThat(ItemContractRepair.consumeUnresolved(taskId))
            .as("consume-once: the narration reads it exactly one time").isEmpty();
    }

    @Test
    void a_capability_message_says_it_is_a_manifest_field() {
        // The 2026-08-27 experiment: with a location-blind message the household 4B
        // added runtime checks to invoke() and made the file WORSE; told where the
        // field goes, it fixed the manifest in one round. The message is the fix.
        var script = """
            exports.manifest = {
              name: "notifier", version: "1.0.0",
              description: "Posts a note.", author: "did:wyrd:backend",
              capabilities: ["web.post"],
              embodiment: { silent: true, reason: "outbound poster" },
              commands: [ { label: "Send a note", args: "" } ]
            };
            function invoke(params) { return { ok: true }; }
            """;
        var problems = ItemContractCheck.problems(script, "notifier.js");
        assertThat(problems).anySatisfy(m -> assertThat(m)
            .contains("rate_limits")
            .contains("exports.manifest")
            .contains("not a runtime check"));
        assertThat(problems).anySatisfy(m -> assertThat(m)
            .contains("external_domains")
            .contains("exports.manifest")
            .contains("not a runtime check"));
    }
}

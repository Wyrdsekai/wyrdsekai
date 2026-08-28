package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repair is the backend's second turn, and it belongs to every backend.
 *
 * <p>Goose is the default today, CodeZaiku is next, and OpenHands, OpenCode, Codex,
 * Continue, Cline, Gemini and the Claude SDK are all behind the same items-as-tools
 * preamble and the same bridge. They fail the contract the same way, so the repair is
 * shared and a backend supplies only the one thing it alone knows — how to re-run itself.
 * These tests use a fake re-prompt for exactly that reason: nothing here may depend on
 * which backend is in play.
 */
class ItemContractRepairTest {

    @TempDir Path workspace;

    /**
     * Every run is bounded to files written at or after it starts. {@code /workspace} is a
     * shared, long-lived directory on a real host — on the build box it held seventeen
     * .js files from months of other work — and an unbounded repair would hand the backend
     * other people's files to "fix". Found by these tests before it could ship.
     */
    private static final Instant START = Instant.now().minusSeconds(5);

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

    @Test
    void a_compliant_file_is_never_reprompted() throws Exception {
        Files.writeString(workspace.resolve("teller.js"), COMPLIANT);
        var prompts = new ArrayList<String>();
        ItemContractRepair.repair(workspace, "t1", START, p -> { prompts.add(p); return true; });
        assertThat(prompts)
            .as("no defect means no extra wallclock spent on the person's behalf")
            .isEmpty();
    }

    @Test
    void a_defective_file_is_handed_back_and_stops_once_it_is_fixed() throws Exception {
        var file = workspace.resolve("teller.js");
        Files.writeString(file, MISSING_COMMANDS);
        var prompts = new ArrayList<String>();

        ItemContractRepair.repair(workspace, "t2", START, p -> {
            prompts.add(p);
            try { Files.writeString(file, COMPLIANT); } catch (Exception e) { return false; }
            return true;   // a backend that actually fixes it
        });

        assertThat(prompts).as("exactly one round when the fix lands").hasSize(1);
        assertThat(ItemContractCheck.isCompliant(Files.readString(file), "teller")).isTrue();
    }

    @Test
    void a_backend_that_never_fixes_it_is_bounded() throws Exception {
        Files.writeString(workspace.resolve("teller.js"), MISSING_COMMANDS);
        var prompts = new ArrayList<String>();
        ItemContractRepair.repair(workspace, "t3", START, p -> { prompts.add(p); return true; });
        assertThat(prompts)
            .as("a model that cannot fix a quoted defect twice will not fix it on the fifth")
            .hasSize(ItemContractRepair.MAX_ROUNDS);
    }

    @Test
    void a_backend_that_fails_to_run_stops_immediately() throws Exception {
        Files.writeString(workspace.resolve("teller.js"), MISSING_COMMANDS);
        var prompts = new ArrayList<String>();
        ItemContractRepair.repair(workspace, "t4", START, p -> { prompts.add(p); return false; });
        assertThat(prompts).as("no point re-asking a backend that did not run").hasSize(1);
    }

    @Test
    void the_declared_file_list_is_repaired_wherever_it_actually_is() throws Exception {
        // The authoritative path is the one the RUN declares, not a directory we guess.
        // Live 2026-08-20, first production run of the whole chain: goose wrote to
        // /opt/wyrdsekai/ — its own cwd, the install root — which is neither the workspace
        // it was handed nor the /workspace the preamble teaches. The scan found nothing,
        // the repair never ran, and the bridge refused the file for a missing embodiment
        // block. The person got a codex he could pick up, examine, and not use.
        var outside = Files.createTempDirectory("declared-elsewhere");
        var file = outside.resolve("library_query.js");
        Files.writeString(file, MISSING_COMMANDS);
        var prompts = new ArrayList<String>();

        ItemContractRepair.repairFiles(
            List.of(file.toString()), /* workspace */ null, "t8",
            p -> { prompts.add(p); try { Files.writeString(file, COMPLIANT); }
                   catch (Exception e) { return false; } return true; });

        assertThat(prompts).as("a declared absolute path must be repaired").hasSize(1);
        assertThat(ItemContractCheck.isCompliant(Files.readString(file), "library_query"))
            .isTrue();
    }

    @Test
    void a_relative_declared_path_resolves_against_the_workspace() throws Exception {
        Files.writeString(workspace.resolve("thing.js"), MISSING_COMMANDS);
        var prompts = new ArrayList<String>();
        ItemContractRepair.repairFiles(
            List.of("thing.js"), workspace, "t9", p -> { prompts.add(p); return true; });
        assertThat(prompts).isNotEmpty();
    }

    @Test
    void a_declared_file_that_is_not_here_is_skipped_quietly() {
        // Some backends run in a container and report paths this host cannot read. That
        // is not an error and must not fail the task.
        ItemContractRepair.repairFiles(
            List.of("/no/such/place/x.js"), null, "t10", p -> true);
    }

    @Test
    void the_prompt_names_every_defect_and_forbids_collateral_edits() {
        // Told only that `commands` was missing, a real backend added it and DELETED the
        // embodiment block in the same edit (live, 2026-08-20). Both halves of this
        // assertion exist because of that.
        var prompt = ItemContractRepair.buildPrompt("teller.js",
            List.of("missing the required `commands` block", "missing `embodiment`"));
        assertThat(prompt)
            .contains("commands")
            .contains("embodiment")
            .contains("IN PLACE")
            .contains("Do NOT remove, rename or alter any other field");
    }

    @Test
    void repair_never_throws_even_on_a_workspace_that_is_not_there() {
        // A repair that goes wrong must never turn completed work into a failed task.
        ItemContractRepair.repair(Path.of("/definitely/not/here"), "t5", START, p -> true);
        ItemContractRepair.repair(null, "t6", START, p -> true);
        ItemContractRepair.repair(workspace, "t7", START, null);
    }
}

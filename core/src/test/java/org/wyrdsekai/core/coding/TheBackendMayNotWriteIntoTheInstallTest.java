package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A coding backend must never be handed the application's own directory to work in.
 *
 * <p>Every CLI backend resolved its working directory the same way — use
 * {@code spec.workspaceHint()} if given, else fall back to the JVM's current directory.
 * On a packaged install the service's current directory is the <b>install root</b>, so a
 * task dispatched with no workspace ran with write access to the jars and scripts the node
 * runs from.
 *
 * <p>Live on the household node, 2026-08-20: the companion dispatched a build with no
 * workspace and goose wrote {@code /opt/wyrdsekai/library_query.js} — into the installed
 * application directory. Nothing stopped it, and nothing would have stopped it writing
 * over something load-bearing.
 */
class TheBackendMayNotWriteIntoTheInstallTest {

    @Test
    void a_task_with_no_workspace_gets_its_own_scratch_not_the_process_directory() {
        var dir = CodingWorkspace.forTask(null, "task-abc");
        assertThat(dir).as("a backend must always be given somewhere safe").isNotNull();
        assertThat(dir.toPath().toAbsolutePath())
            .as("never the JVM's own directory — that is the install root when packaged")
            .isNotEqualTo(Path.of(System.getProperty("user.dir")).toAbsolutePath());
        assertThat(Files.isDirectory(dir.toPath()))
            .as("and it must exist, or the backend falls back to its cwd anyway").isTrue();
        assertThat(dir.getAbsolutePath()).contains(CodingWorkspace.SCRATCH_DIR);
    }

    @Test
    void the_literal_default_placeholder_is_not_treated_as_a_real_workspace() {
        // The live dispatch logged workspace='(default)'. If that string were taken at
        // face value the backend would be pointed at a directory literally named
        // "(default)" — or, as it did, at the process directory.
        var dir = CodingWorkspace.forTask("(default)", "task-def");
        assertThat(dir.getAbsolutePath()).contains(CodingWorkspace.SCRATCH_DIR);
    }

    @Test
    void two_tasks_do_not_share_a_workspace() {
        // One task must not be able to read or clobber another's output.
        assertThat(CodingWorkspace.forTask(null, "task-1").getAbsolutePath())
            .isNotEqualTo(CodingWorkspace.forTask(null, "task-2").getAbsolutePath());
    }

    @Test
    void an_explicit_workspace_is_still_honoured() {
        // A steward naming a project directory is the entire point of the field.
        assertThat(CodingWorkspace.forTask("/srv/projects/thing", "task-x").getAbsolutePath())
            .isEqualTo("/srv/projects/thing");
    }

    @Test
    void the_reported_path_matches_where_the_backend_was_actually_pointed() {
        // Artifacts are resolved against the reported workspace; if the two disagree the
        // bridge looks for the file in the wrong place and silently registers nothing.
        var reported = CodingWorkspace.pathFor(null, "task-same");
        var actual = CodingWorkspace.forTask(null, "task-same").getAbsolutePath();
        assertThat(reported).isEqualTo(actual);
    }

    /**
     * Every backend must actually USE the resolver — not merely be able to.
     *
     * <h2>Why a source scan and not a unit test</h2>
     * The tests above prove {@link CodingWorkspace} does the right thing. They passed all
     * along while <b>only goose called it</b>. {@code CodingWorkspace}'s own javadoc said
     * <i>"Shared deliberately. goose is the default today and CodeZaiku is next; OpenCode,
     * ACP and the Claude SDK all carry the same user.dir fallback. One resolver, so fixing
     * it once fixes it everywhere"</i> — and the code did not have that property. A
     * comment claiming generality is not generality.
     *
     * <p>That mattered on a date: the steward's plan was to install CodeZaiku on the
     * household node and switch the default backend to it. CodeZaiku's
     * {@code resolveWorkdir} returned null with no hint, so the switch would have
     * reintroduced the original bug — a backend writing into {@code /opt/wyrdsekai} —
     * on its first task.
     *
     * <p>So this asserts the property that actually matters and that a unit test cannot
     * see: if a backend source knows about {@code workspaceHint}, it must also route
     * through {@code CodingWorkspace}. A new backend added next year fails here rather
     * than in someone's install directory.
     */
    @Test
    void every_backend_that_resolves_a_workspace_routes_through_the_resolver()
            throws Exception {
        var dir = backendSourceDir();
        var offenders = new java.util.ArrayList<String>();
        try (var files = Files.list(dir)) {
            for (var f : files.filter(p -> p.getFileName().toString().endsWith("Backend.java"))
                    .toList()) {
                var src = Files.readString(f);
                var name = f.getFileName().toString();
                if (!src.contains("workspaceHint")) continue;
                if (!src.contains("CodingWorkspace")) {
                    offenders.add(name + " reads workspaceHint but never calls CodingWorkspace");
                }
                if (src.contains("System.getProperty(\"user.dir\"")) {
                    offenders.add(name + " falls back to the process directory");
                }
            }
        }
        assertThat(offenders)
            .as("a backend that picks its own working directory will pick the install "
                + "root on a packaged node")
            .isEmpty();
    }

    /**
     * Fails loudly rather than skipping: a containment guard that quietly does nothing
     * when it cannot find the sources is worse than no guard, because the suite still
     * reports green.
     */
    private static Path backendSourceDir() {
        for (var candidate : java.util.List.of(
                "src/main/java/org/wyrdsekai/core/coding",
                "core/src/main/java/org/wyrdsekai/core/coding")) {
            var p = Path.of(candidate);
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException(
            "backend sources not found from " + System.getProperty("user.dir")
            + " — this guard must never silently pass");
    }

    /**
     * A backend that authors items must be TOLD the contract, and given the turn to fix
     * a file that breaks it.
     *
     * <h2>What this caught</h2>
     * The repair loop was written inside {@code GooseBackend}, so it existed for the
     * default backend and no other — and the steward's stated plan was to install
     * CodeZaiku and switch to it. Worse, {@code AcpBackend} — the permission-GATED route,
     * whose default agent is literally {@code codezaiku acp} — never prepended the
     * items-as-tools preamble at all. An item authored over ACP had never been told the
     * manifest shape, the embodiment block, the commands block or the invoke()
     * entrypoint, so the bridge would have refused it every time, for reasons the author
     * could not have known.
     *
     * <p>Named exclusions, not silent ones: {@code OpenHandsBackend} owns the preamble
     * text itself, and it and {@code DevinBackend} drive container/HTTP APIs whose
     * re-prompt seam does not exist yet. When one gains it, delete it from this list —
     * which is the point of the list being here rather than in a comment.
     */
    @Test
    void every_item_authoring_backend_gets_the_contract_and_a_repair_turn()
            throws Exception {
        var noPreamble = java.util.Set.of(
            "OpenHandsBackend.java",          // owns the text
            "CodingTaskBackend.java", "TestCodingTaskBackend.java");
        var noRepair = java.util.Set.of(
            "OpenHandsBackend.java",          // container API — no reprompt seam yet
            "DevinBackend.java",              // HTTP session — no reprompt seam yet
            "CodingTaskBackend.java", "TestCodingTaskBackend.java");

        var missingPreamble = new java.util.ArrayList<String>();
        var missingRepair = new java.util.ArrayList<String>();
        try (var files = Files.list(backendSourceDir())) {
            for (var f : files.filter(p -> p.getFileName().toString().endsWith("Backend.java"))
                    .toList()) {
                var name = f.getFileName().toString();
                var src = Files.readString(f);
                // Look for the COMPOSED accessor, not the constant's name. Matching
                // "ITEMS_AS_TOOLS_PREAMBLE" passed for seven backends that only
                // mentioned it in a comment — a guard satisfied by prose is not a guard.
                // The constant is half the contract; the generated surface is the other
                // half, and only itemsAsToolsPreamble(...) carries both.
                if (!noPreamble.contains(name) && !src.contains("itemsAsToolsPreamble")) {
                    missingPreamble.add(name);
                }
                if (!noRepair.contains(name) && !src.contains("ItemContractRepair")) {
                    missingRepair.add(name);
                }
            }
        }
        assertThat(missingPreamble)
            .as("a backend asked to author an item must be told the contract")
            .isEmpty();
        assertThat(missingRepair)
            .as("a file the bridge would refuse must be handed back, on every backend "
                + "— not only the one that happens to be the default today")
            .isEmpty();
    }
}

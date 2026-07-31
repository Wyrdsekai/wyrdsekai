package org.wyrdsekai.core.host;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.coding.BackendRegistry;
import org.wyrdsekai.core.coding.BackendTier;
import org.wyrdsekai.core.coding.CodingArtifact;
import org.wyrdsekai.core.coding.GooseBackend;
import org.wyrdsekai.core.coding.TaskResult;
import org.wyrdsekai.core.coding.TaskSpec;
import org.wyrdsekai.core.coding.TaskStatus;
import org.wyrdsekai.core.coding.TestCodingTaskBackend;
import org.wyrdsekai.core.library.AgentIngestService;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ebooks-concierge scenario — "there's a directory of ebooks somewhere
 * under my home, find it and ingest it" — as a deterministic chain over the
 * three Slice-1/2/3 seams, no live model required:
 *
 * <ol>
 *   <li><b>dispatch_task foreman chain</b> — model-shaped JSON →
 *       {@link ActionParser} → {@link ActionPolicy} gates →
 *       {@link BackendRegistry#backendFor} resolution (goose-first, any
 *       fallback) → {@link TaskSpec} the way
 *       {@code CompanionActor.handleDispatchTask} builds it →
 *       {@link TaskResult} round-trip through a registered backend.</li>
 *   <li><b>find</b> — {@link HostActionService#findFiles} locates the
 *       ebook stash under the granted root and only there. (Lives in this
 *       package to reach the roots-taking seam without widening it to
 *       public — the public overload must stay allowlist-confined.)</li>
 *   <li><b>ingest</b> — {@link AgentIngestService#ingest} pulls the found
 *       directory into the caller's Study and the text becomes searchable.</li>
 * </ol>
 *
 * <p>What's deliberately NOT here: the live 9B deciding to emit
 * dispatch_task from a spoken ask (the same live-emission surface
 * PersonhoodActionsLiveE2ETest exercises), and the real goose subprocess
 * (GooseLiveInvocationE2ETest, env-gated tier-3). This test nails the
 * structural coupling between the surfaces those live tests assume.</p>
 */
class EbooksConciergeE2ETest {

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        BackendRegistry.get().clear();
    }

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
        AgentIngestService.init(null);
    }

    @Test
    void dispatch_task_foreman_chain_parses_gates_and_round_trips_a_backend() throws Exception {
        // ─── The model's emission, verbatim shape ──────────────────────────
        var emission = """
            ```json
            {"action": "dispatch_task",
             "description": "find the ebooks directory under the granted home and report how many epubs it holds",
             "workspace": "%s"}
            ```
            """.formatted(tmp);
        var parsed = ActionParser.parse(emission);
        assertThat(parsed).isInstanceOf(ActionParser.AgentAction.DispatchTask.class);
        var dt = (ActionParser.AgentAction.DispatchTask) parsed;

        // ─── Policy gates the handler relies on ────────────────────────────
        assertThat(ActionPolicy.autonomyTierFor("dispatch_task"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
        assertThat(ActionPolicy.forAction("dispatch_task").domain()).isEqualTo("workshop");

        // ─── Backend resolution exactly as handleDispatchTask does ────────
        var submitted = new AtomicReference<TaskSpec>();
        var fake = new TestCodingTaskBackend() {
            @Override public String name() { return "fake-workshop"; }
            @Override public BackendTier tier() { return BackendTier.LOCAL_FREE; }
            @Override public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
                submitted.set(spec);
                return CompletableFuture.completedFuture(new TaskResult(
                    spec.taskId(), name(), TaskStatus.SUCCEEDED,
                    "found 3 epubs under " + spec.workspaceHint(),
                    List.of(), 0L, 42L));
            }
            @Override public Stream<CodingArtifact> artifactsFor(String taskId) { return Stream.empty(); }
            @Override public CompletableFuture<Boolean> healthCheck() {
                return CompletableFuture.completedFuture(true);
            }
            @Override public long estimatedCu(TaskSpec spec) { return 0L; }
        };
        BackendRegistry.get().register(fake);

        // goose-first, fall back to whatever IS installed — the handler's rule.
        var backend = BackendRegistry.get().backendFor(GooseBackend.NAME)
            .or(() -> BackendRegistry.get().backends().stream().findFirst());
        assertThat(backend).as("fallback must find the registered backend").isPresent();
        assertThat(backend.get().name()).isEqualTo("fake-workshop");

        var spec = new TaskSpec(UUID.randomUUID(), "did:wyrd:concierge", "host_task",
            dt.description(), dt.workspace().isBlank() ? null : dt.workspace(),
            List.of(), 0L, null);
        var result = backend.get().submitTask(spec).get(5, TimeUnit.SECONDS);

        assertThat(submitted.get().description()).contains("ebooks directory");
        assertThat(submitted.get().workspaceHint()).isEqualTo(tmp.toString());
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.summary()).contains("3 epubs");
    }

    /**
     * The find leg of the concierge: locate the stash under the granted
     * root, never see outside it, and recover the directory the ingest
     * call will take. The ingest leg itself (granted dir → async ingest →
     * searchable) lives with its package-private roots seam in
     * {@code AgentIngestServiceTest.ingests_a_granted_directory_async_and_makes_it_searchable}
     * — the handoff between the two is a plain path string, asserted here.
     */
    @Test
    void find_locates_the_ebooks_stash_and_yields_the_ingest_path() throws Exception {
        // ─── Seed the "somewhere in my home dir" stash ─────────────────────
        var home = Files.createDirectories(tmp.resolve("home"));
        var stash = Files.createDirectories(home.resolve("media").resolve("ebooks"));
        Files.writeString(stash.resolve("hobbit.epub"), "x");
        Files.writeString(stash.resolve("dune.epub"), "x");
        var elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("secret.epub"), "x");

        // ─── Find — confined to the granted root ──────────────────────────
        var found = HostActionService.findFiles(List.of(home), "*.epub", 50, "concierge");
        assertThat(found.get("ok")).isEqualTo(true);
        var matches = (List<?>) found.get("matches");
        assertThat(matches).hasSize(2);
        assertThat(matches).allSatisfy(m ->
            assertThat(String.valueOf(m)).doesNotContain("secret"));

        // The companion infers the stash dir from the matches — same parent.
        // This exact string is what world.library.ingest receives.
        var stashDir = Path.of(String.valueOf(matches.getFirst())).getParent();
        assertThat(stashDir).isEqualTo(stash);
    }

    @Test
    void dispatch_spoken_lines_exist_in_all_three_catalogs() {
        // The handler speaks through these keys; a missing key surfaces as the
        // raw key string in the room. Guard all three languages.
        var keys = List.of(
            "dispatch.spoken.missing_description",
            "dispatch.spoken.workspace_refused",
            "dispatch.spoken.no_backend",
            "dispatch.spoken.plan",
            "dispatch.spoken.done",
            "dispatch.spoken.not_done",
            "dispatch.spoken.failed",
            "dispatch.log.dispatched",
            "dispatch.log.completed",
            "dispatch.log.failed");
        for (var lang : List.of("en", "es", "ja")) {
            var catalog = ScriptMessageCatalog.forLang(lang);
            for (var key : keys) {
                assertThat(catalog.get(key, "x", "y"))
                    .as("key %s must resolve in %s", key, lang)
                    .isNotEqualTo(key);
            }
        }
    }
}

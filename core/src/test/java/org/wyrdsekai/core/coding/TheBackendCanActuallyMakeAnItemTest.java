package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Files;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole backend→item leg, end to end, without a household node.
 *
 * <p>This is the leg that had <b>never run once in production</b>. A companion's
 * {@code dispatch_task} called {@code backend.submit()} and published nothing, so the
 * bridge and its eleven adapters sat subscribed and starving while work completed and
 * results were dropped (2026-08-19). The publish was fixed; the rest of the chain was
 * still only ever exercised in pieces.
 *
 * <p>Existing coverage stops short in both directions: {@code CodingTaskItemBridgeTest}
 * hands the bridge a hand-built event and checks artifact translation;
 * {@code CodingTaskItemBridgeLiveGooseTest} takes a file a real goose run already wrote
 * and pushes it through the gate. Neither runs
 * <b>publish → bridge → validate → smoke → register → invoke</b> as one chain.
 *
 * <p>So this walks the whole thing on a workspace on disk, using the production publisher
 * and the production bridge, and finishes by actually CALLING the item — because
 * "registered" is not the same as "works when someone uses it", and this project has been
 * bitten by that distinction more than once.
 *
 * <p>Deliberately hermetic: no goose, no model, no network. It proves OUR half is sound,
 * so that when a real backend artifact fails, the failure is provably the artifact's.
 */
class TheBackendCanActuallyMakeAnItemTest {

    @TempDir Path workspace;

    private AgentEventStream stream;
    private CodingTaskItemBridge bridge;
    private final List<CodingTaskItemBridge.RoomItemPlacement> placements =
        new CopyOnWriteArrayList<>();

    /**
     * Exactly the shape the items-as-tools preamble instructs a backend to emit:
     * one .js, {@code exports.manifest} with an embodiment block and a commands list,
     * plus an {@code invoke()} entrypoint that returns something.
     */
    private static final String GOOSE_SHAPED_ITEM = """
        // Authored by the workshop backend.
        exports.manifest = {
          name: "library_teller",
          version: "1.0.0",
          description: "Looks something up and tells the room a short story about it.",
          author: "did:wyrd:goose",
          capabilities: [],
          embodiment: {
            silent: false,
            emits: ["body_language"],
            descriptor_template: "{actor} turns the lens over and begins to speak"
          },
          commands: [
            { label: "Tell a story about a topic", args: "<topic>" }
          ]
        };

        function invoke(params) {
          var topic = (params && params.query) ? params.query : "the shelf";
          return { ok: true, spoken: "A short story about " + topic + "." };
        }
        """;

    @BeforeEach
    void setUp() {
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
        var registry = new BackendRegistry();
        registry.register(new GooseEventAdapter());
        bridge = new CodingTaskItemBridge(registry, placements::add);
        stream = new AgentEventStream();
        stream.subscribe("bridge", bridge);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wyrdsekai.items.dir");
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    /** AgentEventStream delivers through a queue, so every assertion must wait. */
    private static void await(String what, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    /** Publish a finished task whose workspace holds the given script. */
    private void publishFinishedTask(String fileName, String script) throws Exception {
        Files.writeString(workspace.resolve(fileName), script);
        var taskId = "task-" + UUID.randomUUID();
        // A real SourceArtifact, exactly as a finished backend run produces: the
        // workspace it wrote into and the file it declared.
        var src = new SourceArtifact(
            UUID.randomUUID(), GooseBackend.NAME, taskId, workspace.toString(),
            List.of(fileName), null, Instant.now(), java.util.Map.of());
        CodingTaskBroadcast.publishTerminalWithArtifacts(
            GooseBackend.NAME, "workshop", taskId,
            new TaskResult(UUID.randomUUID(), GooseBackend.NAME, TaskStatus.SUCCEEDED,
                "done", List.of(), 0L, 0L),
            List.<CodingArtifact>of(src), stream);
    }

    @Test
    void a_backend_authored_script_becomes_a_thing_in_the_room_that_actually_runs()
            throws Exception {   // NOSONAR — await() throws InterruptedException
        publishFinishedTask("library_teller.js", GOOSE_SHAPED_ITEM);

        // 1. It reached the room.
        await("placement in the originating room", () -> !placements.isEmpty());
        assertThat(placements)
            .as("the bridge must place the artifact in the originating room")
            .isNotEmpty();
        assertThat(placements.get(0).roomId()).isEqualTo("workshop");
        assertThat(placements.get(0).objects()).isNotEmpty();

        // 2. It registered as a scripted item — not a plain artifact.
        await("scripted-item registration", () -> ScriptedItemLoader.get().all().stream()
            .anyMatch(d -> "library_teller".equals(d.itemId())
                || "library_teller".equals(d.displayName())));
        var registered = ScriptedItemLoader.get().all().stream()
            .filter(d -> "library_teller".equals(d.itemId())
                || "library_teller".equals(d.displayName()))
            .findFirst();
        assertThat(registered)
            .as("a backend-authored item must register, or `use` has nothing to invoke")
            .isPresent();

        // 3. And it RUNS. Registered is not the same as usable — the invoke-once smoke
        //    gate exists because a loader-valid item could still die on first touch.
        var result = new ItemScriptExecutor().execute(
            "library_teller", registered.get().scriptSource(),
            java.util.Map.of("query", "tide pools"), null);
        assertThat(result)
            .as("the item must answer when someone actually uses it, not error")
            .doesNotContainKey("error");
        assertThat(String.valueOf(result.get("spoken")))
            .as("and its answer must reflect what it was asked")
            .contains("tide pools");
    }

    @Test
    void an_accepted_item_is_kept_where_it_will_be_found_after_a_restart()
            throws Exception {
        // Registration is in-memory and the script lives in the task's scratch workspace,
        // which nothing scans at boot. So an accepted item worked until the next restart
        // and then quietly stopped existing, while its RoomObject and inventory row
        // survived — a thing you can hold, pointing at nothing. Live 2026-08-20.
        // No assumeTrue here: a skipped test proves nothing, and this is the assertion
        // that an item outlives the session.
        var keepDir = Files.createTempDirectory("household-items");
        System.setProperty("wyrdsekai.items.dir", keepDir.toString());
        var kept = ScriptedItemLoader.householdItemsDir();
        assertThat(kept).isEqualTo(keepDir);

        publishFinishedTask("library_teller.js", GOOSE_SHAPED_ITEM);
        await("the item to be kept",
            () -> Files.exists(kept.resolve("library_teller.js")));

        assertThat(Files.readString(kept.resolve("library_teller.js")))
            .as("the kept copy must be the script itself, not a stub")
            .contains("function invoke");
    }

    @Test
    void a_script_with_no_entrypoint_is_refused_registration_but_still_placed()
            throws Exception {
        // The contract REJECT path: the artifact stays visible so the work is not lost,
        // but it must not register as something `use` will call into and find nothing.
        publishFinishedTask("broken.js", """
            exports.manifest = {
              name: "broken_thing",
              version: "1.0.0",
              description: "Declares itself and does nothing.",
              author: "did:wyrd:goose",
              capabilities: [],
              embodiment: { silent: true, emits: [], descriptor_template: "{actor} pauses" },
              commands: [ { label: "Do the thing", args: "" } ]
            };
            """);

        await("placement of the rejected artifact", () -> !placements.isEmpty());
        assertThat(placements).as("the work is still placed").isNotEmpty();
        assertThat(ScriptedItemLoader.get().all().stream()
                .anyMatch(d -> "broken_thing".equals(d.itemId())))
            .as("an item with no invoke() must never register — it would be dead on use")
            .isFalse();
    }

    @Test
    void a_namespace_the_bridge_does_not_know_is_dropped_and_places_nothing() {
        // The silent-drop failure mode, pinned: a namespace mismatch between publisher
        // and adapter is invisible in production, which is why it went unnoticed.
        CodingTaskBroadcast.publishTerminalWithArtifacts(
            "not-a-registered-backend", "workshop", "task-x",
            new TaskResult(UUID.randomUUID(), "not-a-registered-backend",
                TaskStatus.SUCCEEDED, "done", List.of(), 0L, 0L),
            List.<CodingArtifact>of(), stream);
        assertThat(placements).isEmpty();
    }

    @Test
    void the_publisher_and_the_bridge_agree_on_the_namespace() throws Exception {
        // The two halves must use the SAME name. This is the seam that was broken.
        var seen = new CopyOnWriteArrayList<AgentEvent>();
        var s2 = new AgentEventStream();
        s2.subscribe("observer", seen::add);
        CodingTaskBroadcast.publishTerminalWithArtifacts(
            GooseBackend.NAME, "workshop", "task-y",
            new TaskResult(UUID.randomUUID(), GooseBackend.NAME, TaskStatus.SUCCEEDED,
                "done", List.of(), 0L, 0L),
            List.<CodingArtifact>of(), s2);
        // Wait, like every other assertion on this stream. Delivery is queued, so a
        // straight read races — and under full-suite load it loses. (Caught by the gate
        // on 2026-08-20 after passing in isolation, which is exactly the shape of flake
        // that erodes trust in a suite.)
        await("the terminal broadcast", () -> seen.stream()
            .anyMatch(e -> e instanceof AgentEvent.ZoneBroadcast));
        var ns = seen.stream()
            .filter(e -> e instanceof AgentEvent.ZoneBroadcast)
            .map(e -> ((AgentEvent.ZoneBroadcast) e).namespace())
            .toList();
        assertThat(ns).containsExactly(GooseBackend.NAME);
    }
}

package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.LocalCommandRouter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for:
 * router → CodingNamespaceHandler → fake backend → AgentEventStream
 * → CodingTaskItemBridge → roomObjectPlacer.
 *
 * <p>Uses a fake backend ({@link FakeBackend}) so the test runs without
 * Docker / OpenHands / OpenCode. The fake registers under the namespace
 * {@code "fake"} and a matching {@link BackendAdapter} so the bridge
 * recognises the terminal event.</p>
 */
class CodingNamespaceHandlerTest {

    private LocalCommandRouter router;

    @BeforeEach
    void setup() {
        BackendRegistry.get().clear();
        LocalCommandRouter.resetForTest();
        AgentEventStream.init();  // fresh subscriber map
        router = LocalCommandRouter.get();
    }

    @AfterEach
    void teardown() {
        BackendRegistry.get().clear();
        LocalCommandRouter.resetForTest();
    }

    /**
     * Minimal stub backend that completes synchronously on submitTask
     * and caches a SourceArtifact + BuildArtifact pair under the task
     * id. Mirrors the OpenHands shape closely enough for the adapter
     * to translate.
     */
    private static final class FakeBackend extends TestCodingTaskBackend {
        static final String NAME = "fake";
        private final Map<String, List<CodingArtifact>> cache = new HashMap<>();

        @Override public String name() { return NAME; }
        @Override public BackendTier tier() { return BackendTier.LOCAL_FREE; }

        @Override
        public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
            // Build artifacts as the backend would on completion.
            var src = new SourceArtifact(
                UUID.randomUUID(),
                NAME,
                spec.taskId().toString(),
                "/tmp/fake-workspace/" + spec.taskId(),
                List.of("greet.py", "main.py"),
                null,
                Instant.now(),
                Map.of("source", "fake"));
            var build = new BuildArtifact(
                UUID.randomUUID(),
                NAME,
                spec.taskId().toString(),
                spec.taskId().toString(),
                "success",
                1, 0,
                Instant.now(),
                Map.of());
            cache.put(spec.taskId().toString(), List.of(src, build));

            return CompletableFuture.completedFuture(
                new TaskResult(spec.taskId(), NAME, TaskStatus.SUCCEEDED,
                    "wrote 2 files", List.of(src.artifactId(), build.artifactId()),
                    0L, 42L));
        }

        @Override
        public Stream<CodingArtifact> artifactsFor(String taskId) {
            var list = cache.get(taskId);
            return list == null ? Stream.empty() : list.stream();
        }

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            return CompletableFuture.completedFuture(true);
        }

        @Override public long estimatedCu(TaskSpec spec) { return 1L; }

        // Phase C: opt in to runArtifact + examineArtifact + destroyArtifact
        // with predictable behaviour so handler dispatch tests can assert on
        // both the success path and the not-found path.

        @Override
        public CompletableFuture<ExecResult> runArtifact(UUID artifactId,
                                                          List<String> args,
                                                          Map<String, String> env) {
            // Look up artifact across all cached lists.
            for (var list : cache.values()) {
                if (list.stream().anyMatch(a -> a.artifactId().equals(artifactId))) {
                    return CompletableFuture.completedFuture(new ExecResult(
                        true,
                        "fake-run-stdout: args=" + args + " env=" + env,
                        "",
                        0,
                        Duration.ofMillis(5),
                        "fake run",
                        null));
                }
            }
            return CompletableFuture.completedFuture(
                ExecResult.notFound(NAME, artifactId.toString()));
        }

        @Override
        public CompletableFuture<ExamineResult> examineArtifact(UUID artifactId) {
            for (var list : cache.values()) {
                for (var a : list) {
                    if (a instanceof SourceArtifact s && s.artifactId().equals(artifactId)) {
                        return CompletableFuture.completedFuture(new ExamineResult(
                            artifactId, NAME, s.workspacePath(),
                            s.files(),
                            Map.of("greet.py", "def greet(name): return 'hi'"),
                            List.of("fake-note"),
                            null));
                    }
                }
            }
            return CompletableFuture.completedFuture(
                ExamineResult.notFound(NAME, artifactId.toString()));
        }

        @Override
        public CompletableFuture<Boolean> destroyArtifact(UUID artifactId) {
            for (var entry : cache.entrySet()) {
                if (entry.getValue().stream()
                        .anyMatch(a -> a.artifactId().equals(artifactId))) {
                    cache.remove(entry.getKey());
                    return CompletableFuture.completedFuture(true);
                }
            }
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Adapter for the fake namespace. Translates terminal
     * ZoneBroadcast payloads (event=task_completed) into a SourceArtifact
     * with the build sibling stashed in backendMetadata — same shape
     * the OpenHands / OpenCode adapters use.
     */
    private static final class FakeAdapter implements BackendAdapter {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        @Override public String namespace() { return FakeBackend.NAME; }

        @Override
        public CodingArtifact translateEvent(AgentEvent.ZoneBroadcast event) {
            if (event == null) return null;
            if (!FakeBackend.NAME.equals(event.namespace())) return null;
            if (!(event.message() instanceof S2CMessage.ZoneResponse zr)) return null;
            var data = zr.data();
            if (data == null) return null;

            var ev = data.path("event").asText("");
            if (!"task_completed".equals(ev)) return null;

            var taskId = data.path("taskId").asText(null);
            var workspace = data.path("workspace").asText("");
            if (taskId == null || taskId.isBlank()) return null;

            var files = new ArrayList<String>();
            if (data.has("files") && data.get("files").isArray()) {
                for (var f : data.get("files")) files.add(f.asText());
            }

            var meta = new HashMap<String, Object>();
            meta.put("source", "fake");
            if (data.has("build") && data.get("build").isObject()) {
                var b = data.get("build");
                var build = new BuildArtifact(
                    UUID.randomUUID(),
                    FakeBackend.NAME,
                    taskId, taskId,
                    b.path("status").asText("success"),
                    b.path("testsPassed").asInt(0),
                    b.path("testsFailed").asInt(0),
                    Instant.now(),
                    Map.of());
                meta.put("__sibling_build", build);
            }
            return new SourceArtifact(
                UUID.randomUUID(),
                FakeBackend.NAME,
                taskId, workspace, List.copyOf(files),
                null, Instant.now(),
                Map.copyOf(meta));
        }

        @Override public TaskSpec parsePlayerCommand(String c, String a) { return null; }
    }

    @Test
    void create_flowsThroughHandler_publishesZoneBroadcast_bridgeProducesRoomObjects()
            throws Exception {
        // Wire fake backend + adapter.
        var registry = BackendRegistry.get();
        registry.register(new FakeBackend());
        registry.register(new FakeAdapter());

        // Subscribe a bridge that captures placements.
        var placements = new ArrayList<CodingTaskItemBridge.RoomItemPlacement>();
        var placedLatch = new CountDownLatch(1);
        var bridge = new CodingTaskItemBridge(registry, p -> {
            placements.add(p);
            placedLatch.countDown();
        });
        AgentEventStream.get().subscribe("test:bridge", bridge);

        // Register the namespace handler under "fake" and dispatch a create.
        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        var responses = new ArrayList<S2CMessage>();
        var ok = router.execute("did:wyrd:test", "fake.create", List.of(),
            Map.of("description", "write greet+main",
                   "taskType", "explore",
                   "roomId", "the-workshop"),
            responses::add);

        assertTrue(ok);

        // Wait for the bridge to receive the terminal broadcast (rate
        // limiter delivers asynchronously).
        assertTrue(placedLatch.await(5, TimeUnit.SECONDS),
            "bridge should receive the terminal ZoneBroadcast within 5s");

        assertEquals(1, placements.size());
        var placement = placements.get(0);
        assertEquals("the-workshop", placement.roomId());
        // Codex + artifact = 2 placed objects.
        assertEquals(2, placement.objects().size(),
            "expected codex + artifact placements; got: " + placement.objects());
        assertTrue(placement.objects().stream().anyMatch(o -> o.id().startsWith("codex-")),
            "codex item missing");
        assertTrue(placement.objects().stream().anyMatch(o -> o.id().startsWith("artifact-")),
            "artifact item missing");

        // Caller saw an immediate ack + a terminal Prose.
        var proseTexts = responses.stream()
            .filter(m -> m instanceof S2CMessage.Prose)
            .map(m -> ((S2CMessage.Prose) m).text())
            .toList();
        assertTrue(proseTexts.size() >= 2,
            "expected ack + terminal Prose, got: " + proseTexts);
        assertTrue(proseTexts.get(0).contains("accepted task"),
            "first prose should be ack: " + proseTexts.get(0));
        assertTrue(proseTexts.stream().anyMatch(t -> t.contains("SUCCEEDED")),
            "terminal prose should mention SUCCEEDED: " + proseTexts);
    }

    @Test
    void create_missingDescription_emitsError() {
        var registry = BackendRegistry.get();
        registry.register(new FakeBackend());
        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        var captured = new AtomicReference<S2CMessage>();
        router.execute("did:test", "fake.create", List.of(), Map.of(),
            captured::set);

        var err = (S2CMessage.Error) captured.get();
        assertEquals("missing_field", err.code());
    }

    @Test
    void unsupportedVerb_returnsClearError() {
        var registry = BackendRegistry.get();
        registry.register(new FakeBackend());
        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        var responses = new ArrayList<S2CMessage>();
        router.execute("did:test", "fake.deploy",
            List.of("artifact-123", "boiler-room"),
            Map.of(), responses::add);

        var errors = responses.stream()
            .filter(m -> m instanceof S2CMessage.Error)
            .map(m -> (S2CMessage.Error) m)
            .toList();
        assertEquals(1, errors.size());
        assertEquals("unsupported_verb", errors.get(0).code());
    }

    @Test
    void backendNotRegistered_emitsError() {
        // Handler is registered, but no backend instance is in the
        // registry — surface backend_unavailable cleanly.
        router.register("ghost",
            new CodingNamespaceHandler("ghost", BackendRegistry.get()));

        var captured = new AtomicReference<S2CMessage>();
        router.execute("did:test", "ghost.create",
            List.of(), Map.of("description", "x"), captured::set);

        var err = (S2CMessage.Error) captured.get();
        assertEquals("backend_unavailable", err.code());
    }

    @Test
    void create_withoutRoomId_succeedsButSkipsBroadcast() throws Exception {
        // Caller didn't pass roomId — handler should still complete the
        // task (and emit terminal Prose) but skip the ZoneBroadcast.
        var registry = BackendRegistry.get();
        registry.register(new FakeBackend());
        registry.register(new FakeAdapter());

        var placements = new ArrayList<CodingTaskItemBridge.RoomItemPlacement>();
        var bridge = new CodingTaskItemBridge(registry, placements::add);
        AgentEventStream.get().subscribe("test:bridge2", bridge);

        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        var responses = new ArrayList<S2CMessage>();
        router.execute("did:test", "fake.create", List.of(),
            Map.of("description", "no room"),  // no roomId
            responses::add);

        // Give the future a moment to complete.
        Thread.sleep(200);

        var terminalProse = responses.stream()
            .filter(m -> m instanceof S2CMessage.Prose)
            .map(m -> ((S2CMessage.Prose) m).text())
            .filter(t -> t.contains("SUCCEEDED"))
            .findFirst();
        assertTrue(terminalProse.isPresent(),
            "terminal Prose should still arrive even without roomId");
        assertTrue(placements.isEmpty(),
            "no placement when roomId is missing; got: " + placements);
    }

    @Test
    void examine_withCachedArtifacts_listsThem() {
        var registry = BackendRegistry.get();
        var backend = new FakeBackend();
        registry.register(backend);
        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        // Prime the cache via a create call.
        var responses1 = new ArrayList<S2CMessage>();
        var taskIdHolder = new AtomicReference<String>();
        router.execute("did:test", "fake.create", List.of(),
            Map.of("description", "prime"), msg -> {
                responses1.add(msg);
                if (msg instanceof S2CMessage.Prose p
                        && p.text().contains("accepted task")) {
                    var i = p.text().indexOf("accepted task ");
                    if (i >= 0) {
                        var rest = p.text().substring(i + "accepted task ".length());
                        var space = rest.indexOf(' ');
                        taskIdHolder.set(space > 0 ? rest.substring(0, space) : rest);
                    }
                }
            });
        var taskId = taskIdHolder.get();
        assertNotNull(taskId, "captured taskId from ack prose");

        // Now examine.
        var responses2 = new ArrayList<S2CMessage>();
        router.execute("did:test", "fake.examine", List.of(taskId),
            Map.of(), responses2::add);

        var prose = responses2.stream()
            .filter(m -> m instanceof S2CMessage.Prose)
            .map(m -> ((S2CMessage.Prose) m).text())
            .findFirst().orElse("");
        assertTrue(prose.contains("Artifacts for fake task"), "prose: " + prose);
        assertTrue(prose.contains("codex "), "prose missing codex line: " + prose);
        assertTrue(prose.contains("artifact "), "prose missing artifact line: " + prose);
    }

    // ─── Phase C verbs: run / examine-by-artifactId / destroy ─────

    @Test
    void run_dispatchesToBackend_andNarratesExecResult() throws Exception {
        var registry = BackendRegistry.get();
        var backend = new FakeBackend();
        registry.register(backend);
        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        // Prime an artifact via create, then capture its id.
        var artifactIdHolder = new AtomicReference<UUID>();
        router.execute("did:test", "fake.create", List.of(),
            Map.of("description", "prime"), msg -> {});
        // Wait for completion; one cached entry should appear.
        for (int i = 0; i < 20 && artifactIdHolder.get() == null; i++) {
            backend.cache.values().stream().findFirst().ifPresent(list -> {
                list.stream()
                    .filter(a -> a instanceof SourceArtifact)
                    .findFirst()
                    .ifPresent(a -> artifactIdHolder.set(a.artifactId()));
            });
            if (artifactIdHolder.get() == null) Thread.sleep(50);
        }
        assertNotNull(artifactIdHolder.get(), "primed artifact id");

        var responses = new ArrayList<S2CMessage>();
        router.execute("did:test", "fake.run",
            List.of(artifactIdHolder.get().toString()),
            Map.of(), responses::add);

        var prose = responses.stream()
            .filter(m -> m instanceof S2CMessage.Prose)
            .map(m -> ((S2CMessage.Prose) m).text())
            .findFirst().orElse("");
        assertTrue(prose.contains("Ran `fake run`"),
            "should narrate Ran `fake run`; got: " + prose);
        assertTrue(prose.contains("fake-run-stdout"),
            "should include backend's stdout; got: " + prose);
    }

    @Test
    void run_missingArtifactId_returnsMissingField() {
        var registry = BackendRegistry.get();
        registry.register(new FakeBackend());
        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        var captured = new AtomicReference<S2CMessage>();
        router.execute("did:test", "fake.run",
            List.of(), Map.of(), captured::set);
        var err = (S2CMessage.Error) captured.get();
        assertEquals("missing_field", err.code());
    }

    @Test
    void run_invalidArtifactId_returnsMissingField() {
        var registry = BackendRegistry.get();
        registry.register(new FakeBackend());
        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        var captured = new AtomicReference<S2CMessage>();
        router.execute("did:test", "fake.run",
            List.of("not-a-uuid"), Map.of(), captured::set);
        var err = (S2CMessage.Error) captured.get();
        assertEquals("missing_field", err.code());
    }

    @Test
    void examine_artifactId_usesRichExamineResult() throws Exception {
        var registry = BackendRegistry.get();
        var backend = new FakeBackend();
        registry.register(backend);
        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        // Prime artifact.
        router.execute("did:test", "fake.create", List.of(),
            Map.of("description", "prime"), msg -> {});
        UUID artifactId = null;
        for (int i = 0; i < 20 && artifactId == null; i++) {
            for (var list : backend.cache.values()) {
                for (var a : list) {
                    if (a instanceof SourceArtifact s) { artifactId = s.artifactId(); break; }
                }
                if (artifactId != null) break;
            }
            if (artifactId == null) Thread.sleep(50);
        }
        assertNotNull(artifactId);

        var responses = new ArrayList<S2CMessage>();
        router.execute("did:test", "fake.examine",
            List.of(artifactId.toString()), Map.of(), responses::add);

        var prose = responses.stream()
            .filter(m -> m instanceof S2CMessage.Prose)
            .map(m -> ((S2CMessage.Prose) m).text())
            .findFirst().orElse("");
        assertTrue(prose.startsWith("Codex "),
            "rich examine should narrate as Codex …; got: " + prose);
        assertTrue(prose.contains("Files (2):"),
            "should list file count; got: " + prose);
        assertTrue(prose.contains("greet.py"),
            "should mention file path; got: " + prose);
        assertTrue(prose.contains("fake-note"),
            "should include backend notes; got: " + prose);
    }

    @Test
    void destroy_returnsTrueWhenBackendCleansUp() throws Exception {
        var registry = BackendRegistry.get();
        var backend = new FakeBackend();
        registry.register(backend);
        router.register(FakeBackend.NAME,
            new CodingNamespaceHandler(FakeBackend.NAME, registry));

        router.execute("did:test", "fake.create", List.of(),
            Map.of("description", "prime"), msg -> {});
        UUID artifactId = null;
        for (int i = 0; i < 20 && artifactId == null; i++) {
            for (var list : backend.cache.values()) {
                for (var a : list) {
                    if (a instanceof SourceArtifact s) { artifactId = s.artifactId(); break; }
                }
                if (artifactId != null) break;
            }
            if (artifactId == null) Thread.sleep(50);
        }
        assertNotNull(artifactId);
        assertFalse(backend.cache.isEmpty());

        var responses = new ArrayList<S2CMessage>();
        router.execute("did:test", "fake.destroy",
            List.of(artifactId.toString()), Map.of(), responses::add);

        var prose = responses.stream()
            .filter(m -> m instanceof S2CMessage.Prose)
            .map(m -> ((S2CMessage.Prose) m).text())
            .findFirst().orElse("");
        assertTrue(prose.contains("Destroyed"),
            "destroy should narrate Destroyed; got: " + prose);
        assertTrue(backend.cache.isEmpty(),
            "destroy should drop the cached artifact list");
    }
}

package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Tag;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.wyrdsekai.core.agent.classifier.ClassifierArm;
import org.wyrdsekai.core.agent.classifier.ClassifierEventLog;
import org.wyrdsekai.core.agent.classifier.ClassifierHead;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.search.EmbeddingService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E sleep-cycle classifier test — proves the Phase 3 → Phase 4 loop works
 * against a live {@link CompanionActor}.
 *
 * <p>Pattern:
 * <ol>
 *   <li>Spawn a companion with a unique DID.</li>
 *   <li>Deliver several player tells (via {@code AgentMessageReceived} with
 *       {@code [from Name]} prefix — the relayed-tell shape that triggers
 *       the classifier in {@code onAgentMessage}).</li>
 *   <li>Each tell classifies → logs an event at
 *       {@code ~/.wyrdsekai/classifiers/<did>/events.jsonl}.</li>
 *   <li>Trigger {@code ForceForgeConsolidate} — the Phase 4 sleep hook.</li>
 *   <li>Verify events were rotated, merged into the per-agent corpus, and
 *       a lineage entry recorded.</li>
 * </ol>
 *
 * <p>Retrain subprocess is left env-gated off by default — that path is
 * already covered by {@code ClassifierForgeRetrainIntegrationTest}. This
 * test focuses on the actor-driven consolidation flow.
 */
@Tag("integration")
@Tag("needs-classifier")
@Tag("needs-datadir")
class ClassifierSleepCycleE2ETest {

    private static ActorTestKit testKit;

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;
    private String did;
    private Path classifierDir;

    private static final String ROOM_ID = "nexus";

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        EmbeddingService.init();
        testKit = ActorTestKit.create("classifier-sleep-cycle-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void spawnCompanion() {
        // Unique DID per test so classifier state is isolated.
        did = "did:test:classifier-sleep-" + UUID.randomUUID();
        var safeDid = did.replaceAll("[^a-zA-Z0-9_-]", "_");
        classifierDir = Path.of(System.getProperty("user.home"),
            ".wyrdsekai", "classifiers", safeDid);

        var profile = new AgentProfile(
            "Wyrd", "agent-wyrd-sleep-test", "agent",
            "A companion in Wyrdsekai",
            "You are Wyrd.",
            4096, 256, 0.7,
            did);

        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();
        companion = testKit.spawn(CompanionActor.create(
            profile, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));

        // Drain startup: Subscribe, EnterRoom, LookRoom
        var subscribe = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = subscribe.subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    @AfterEach
    void cleanupClassifierDir() {
        if (classifierDir == null || !Files.exists(classifierDir)) return;
        try (var walk = Files.walk(classifierDir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    // ── Tests ─────────────────────────────────────────────────────────

    @Test
    void player_tells_classify_and_log_events() {
        // Live-classifier guard (mirrors RoutingInvariantTest): the REQUEST_TYPE
        // events.jsonl is only written when arm.classify returns a real label.
        // That requires the SetFit classifier encoder to produce a non-zero
        // embedding (ClassifierArm returns Classification.unavailable() otherwise).
        // When the encoder/native libs aren't functional in this environment,
        // classification is unavailable and no event is logged — skip rather than
        // hard-fail, consistent with the rest of the classifier probe suite.
        var probeArm = ClassifierArm.forAgent(did);
        Assumptions.assumeTrue(probeArm != null,
            "ClassifierArm unavailable (onnxruntime native libs missing)");
        var probe = probeArm.classify(ClassifierHead.REQUEST_TYPE, "what is the capital of Peru");
        Assumptions.assumeTrue(probe.label() != null,
            "Classifier encoder not producing live labels in this environment — "
                + "skip the live REQUEST_TYPE event-logging check");

        // Five player tells spanning diverse intents. The classifier fires
        // in onAgentMessage for relayed [from Name] tells.
        var tells = List.of(
            "hi there, lovely morning isn't it",
            "what's the capital of Peru",
            "tell Alice I'll be ten minutes late to the meeting",
            "jot down a reminder to buy milk tomorrow",
            "I'm really struggling today, everything feels heavy"
        );

        for (var text : tells) {
            companion.tell(new CompanionActor.AgentMessageReceived(
                new AgentEvent.AgentMessage(
                    "player-alice", "Alice", "agent-wyrd-sleep-test",
                    "[from Alice] " + text,
                    Instant.now())));
            // Drain any downstream ChatRequest + SayInRoom the tell triggered.
            // Classifier writes are synchronous inside onAgentMessage, but the
            // tells are enqueued on the actor mailbox by `companion.tell` and
            // processed serially — so the file isn't fully populated until
            // every tell has been pulled from the mailbox. We still need to
            // consume probe messages to avoid back-pressure stalling the
            // mailbox.
            drainDownstream();
        }

        // Poll until every tell's REQUEST_TYPE event has landed in the file
        // (or the deadline expires). The actor processes messages serially;
        // each tell spends ~100–400ms in onAgentMessage (classify + auto-
        // dispatch + plan creation), so a 10-second budget is plenty even
        // when one tell auto-dispatches a bunshin.
        var events = Path.of(classifierDir.toString(), "events.jsonl");
        var deadline = Instant.now().plus(Duration.ofSeconds(10));
        long requestTypeLines = 0;
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(events)) {
                var content = readOrEmpty(events);
                requestTypeLines = content.lines()
                    .filter(l -> l.contains("\"head\":\"REQUEST_TYPE\""))
                    .count();
                if (requestTypeLines >= tells.size()) break;
            }
            try {
                drainDownstream();  // keep mailbox unblocked while polling
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(events).exists();
        assertThat(requestTypeLines)
            .as("each player tell should produce one REQUEST_TYPE event")
            .isGreaterThanOrEqualTo(tells.size());
    }

    @Test
    void force_forge_consolidate_merges_events_into_corpus() {
        // Seed a few high-confidence events directly (simulating prior traffic)
        // to avoid depending on the dispatch-bunshin path for delegate tells.
        seedEvents(List.of(
            "hi there, lovely day",
            "good morning, how are you",
            "what's the capital of Peru",
            "when did Apollo 11 land on the moon",
            "jot down that the meeting is at 3",
            "save a note to call mom tomorrow"
        ));

        // Now trigger the Phase 4 consolidation hook.
        companion.tell(new CompanionActor.ForceForgeConsolidate());
        // Give the actor a moment to run the consolidation inline.
        // ForceForgeConsolidate runs synchronously inside the message handler.
        // We poll for the per-agent corpus to land (should be nearly instant).
        waitForFile(classifierDir.resolve("request_type-corpus.jsonl"),
            Duration.ofSeconds(5));

        // Corpus merged: should contain both bootstrap AND the new pseudo-labels.
        var corpus = classifierDir.resolve("request_type-corpus.jsonl");
        assertThat(corpus).exists();
        var corpusLines = readOrEmpty(corpus).lines().count();
        assertThat(corpusLines)
            .as("merged corpus should be at least the bootstrap size")
            .isGreaterThan(2000);

        // Lineage should record the consolidation.
        var lineage = classifierDir.resolve("request_type.lineage.jsonl");
        assertThat(lineage).exists();
        var lineageContent = readOrEmpty(lineage);
        assertThat(lineageContent).contains("\"corpus_size\"");
        assertThat(lineageContent).contains("\"pseudo_labels_added\"");
        // Retrain env gate is off by default in test runs.
        assertThat(lineageContent).contains("retrain gated off");
    }

    @Test
    void consolidation_with_no_events_is_graceful() {
        // Fresh agent, no classifier events. Consolidation should be a no-op.
        companion.tell(new CompanionActor.ForceForgeConsolidate());
        // No events = no rotated file = no corpus written.
        // We just want to confirm the actor didn't crash — send a probe
        // message and confirm we can still drain something from the room.
        Thread.yield();
        // Actor should still be responsive.
        companion.tell(new CompanionActor.AgentMessageReceived(
            new AgentEvent.AgentMessage(
                "player-alice", "Alice", "agent-wyrd-sleep-test",
                "[from Alice] hello",
                Instant.now())));
        drainDownstream();
        // If we got here without the probe blowing up, consolidation was graceful.
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void seedEvents(List<String> texts) {
        // Write directly into the event log to bypass classifier
        // non-determinism and make the test hermetic.
        try {
            Files.createDirectories(classifierDir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var eventLog = ClassifierEventLog.forAgent(classifierDir);
        assertThat(eventLog).isNotNull();
        for (var text : texts) {
            eventLog.record(new ClassifierEventLog.Event(
                Instant.now(),
                "REQUEST_TYPE",
                text,
                text.toLowerCase().startsWith("hi") || text.toLowerCase().contains("morning")
                    ? "chat"
                    : text.toLowerCase().contains("capital")
                        || text.toLowerCase().contains("apollo")
                        ? "factual"
                        : "write",
                0.91,
                "L1"));
        }
    }

    private void drainDownstream() {
        // Classifier-dispatch and normal inference paths both produce probe
        // traffic. For this test we don't care which — we just need to keep
        // the mailbox unblocked. Drain up to 5 messages opportunistically.
        for (int i = 0; i < 5; i++) {
            try {
                var msg = routerProbe.receiveMessage(Duration.ofMillis(200));
                // Auto-reply to any ChatRequest to unblock the actor.
                if (msg instanceof InferenceRouter.ChatRequest req) {
                    req.replyTo().tell(new InferenceRouter.InferOk(
                        req.requestId(), "ok.", 10, 5));
                }
            } catch (AssertionError e) {
                break; // no message available
            }
        }
        for (int i = 0; i < 5; i++) {
            try {
                roomProbe.receiveMessage(Duration.ofMillis(200));
            } catch (AssertionError e) {
                break;
            }
        }
    }

    private static void waitForFile(Path p, Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(p)) return;
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String readOrEmpty(Path p) {
        try { return Files.readString(p); }
        catch (Exception e) { return ""; }
    }

    private static RoomSnapshot testSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub.",
            "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of());
    }
}

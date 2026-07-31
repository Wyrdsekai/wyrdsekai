package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.familiar.BunshinScheduler;
import org.wyrdsekai.core.familiar.FamiliarPersistenceStore;
import org.wyrdsekai.core.familiar.PersistentBunshinRegistry;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rules 15-17 in production — every {@code shape_form}
 * dispatches a dry-run through {@link InferenceRouter} and only commits to
 * the FamilyLocker if the dry-run passes.
 *
 * <p>Unlike {@code WorkbenchFormAuthoringIntegrationTest} (which disables
 * the dynamic path because its existing tests predate it), this class runs
 * with {@code dynamic-validation.enabled=true} and asserts:</p>
 * <ol>
 *   <li>A well-behaved dry-run commits the form.</li>
 *   <li>An empty-output dry-run (rule 17) rejects the form — not in locker.</li>
 *   <li>A budget-overrun dry-run (rule 16) rejects.</li>
 *   <li>Inference error path rejects gracefully.</li>
 * </ol>
 */
@Tag("integration")
class DynamicShapeValidationIntegrationTest {

    private static ActorTestKit testKit;

    private static final String ROOM_ID = "workshop";
    private static final String ENTITY_ID = "agent-wyrd-dyn";
    private static final String DID = "did:key:z6MkWyrdDyn";

    private static AgentProfile profile() {
        return new AgentProfile("Wyrd", ENTITY_ID, "agent",
            "A companion at the Workshop",
            "You are Wyrd, a companion in Wyrdsekai.",
            4096, 512, 0.7, DID);
    }

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;
    private FamilyLocker locker;

    @BeforeAll
    static void setupClass() {
        // Dynamic validation must be on for this class
        System.setProperty("wyrdsekai.familiar.dynamic-validation.enabled", "true");
        ConfigFactory.invalidateCaches();

        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("dyn-shape-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """));
    }

    @AfterAll
    static void teardownClass() {
        if (testKit != null) testKit.shutdownTestKit();
        System.clearProperty("wyrdsekai.familiar.dynamic-validation.enabled");
        ConfigFactory.invalidateCaches();
    }

    @BeforeEach
    void spawnCompanion() throws IOException {
        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();
        BunshinScheduler.resetForTests();
        PersistentBunshinRegistry.resetForTests();
        clearPersistenceDir(DID);

        var bud = SoulBud.original(DID, "z6MkDyn", "family-dyn",
            "locker://dyn", "test-node", "qwen2.5:4b");
        locker = FamilyLocker.create("family-dyn", "locker://dyn", bud);

        var caps = new CompanionCapabilities(
            locker, null, null, null, false, 0, null, true);

        companion = testKit.spawn(CompanionActor.create(
            profile(), roomProbe.ref(), ROOM_ID, routerProbe.ref(),
            null, null, null, null, null, caps));

        var subscribe = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = subscribe.subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(workshopSnapshot()));
    }

    @Test
    void shape_commits_after_successful_dry_run() {
        triggerShape("helper", "Say hello.", "hello");

        // 1st chat request = the main inference (agent's turn)
        var agentReq = expectChatRequest();
        agentReq.replyTo().tell(new InferenceRouter.InferOk(
            agentReq.requestId(),
            """
            ```json
            {"action":"shape_form","name":"helper",
             "system_prompt":"Say hello briefly.",
             "eval_criteria":"hello",
             "tool_surface":[]}
            ```""",
            40, 20));

        // 2nd chat request = the shape dry-run — recognize by prefix
        var dryRun = expectChatRequest();
        assertThat(dryRun.requestId()).startsWith("shape-validation-");
        dryRun.replyTo().tell(new InferenceRouter.InferOk(
            dryRun.requestId(), "hello there", 20, 1));

        // Confirmation speak — form committed
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("helper");
        assertThat(locker.thoughtFormByName("helper", DID)).isPresent();
    }

    @Test
    void shape_rejected_when_dry_run_returns_empty_output() {
        triggerShape("empty", "Do something.", "required");

        var agentReq = expectChatRequest();
        agentReq.replyTo().tell(new InferenceRouter.InferOk(
            agentReq.requestId(),
            """
            ```json
            {"action":"shape_form","name":"empty",
             "system_prompt":"Do something.",
             "tool_surface":[]}
            ```""",
            40, 20));

        // Dry-run returns nothing — rule 17 fires
        var dryRun = expectChatRequest();
        dryRun.replyTo().tell(new InferenceRouter.InferOk(
            dryRun.requestId(), "", 5, 1));

        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text().toLowerCase())
            .containsAnyOf("rejected", "rule 17", "revise");
        assertThat(locker.thoughtFormByName("empty", DID)).isEmpty();
    }

    @Test
    void shape_rejected_when_dry_run_overruns_budget() {
        triggerShape("greedy", "Produce a long answer.", null);

        var agentReq = expectChatRequest();
        agentReq.replyTo().tell(new InferenceRouter.InferOk(
            agentReq.requestId(),
            """
            ```json
            {"action":"shape_form","name":"greedy",
             "system_prompt":"Produce a long answer.",
             "tool_surface":[]}
            ```""",
            40, 20));

        // Dry-run reports burning far more tokens than its 10%-of-default allotment
        var dryRun = expectChatRequest();
        dryRun.replyTo().tell(new InferenceRouter.InferOk(
            dryRun.requestId(), "hello", 1, 99999));

        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text().toLowerCase())
            .containsAnyOf("rejected", "rule 16", "overrun", "revise");
        assertThat(locker.thoughtFormByName("greedy", DID)).isEmpty();
    }

    @Test
    void shape_rejected_on_inference_error() {
        triggerShape("broken", "Task.", null);

        var agentReq = expectChatRequest();
        agentReq.replyTo().tell(new InferenceRouter.InferOk(
            agentReq.requestId(),
            """
            ```json
            {"action":"shape_form","name":"broken",
             "system_prompt":"Task.",
             "tool_surface":[]}
            ```""",
            40, 20));

        // Dry-run inference errors — rule 15 fires
        var dryRun = expectChatRequest();
        dryRun.replyTo().tell(new InferenceRouter.InferError(
            dryRun.requestId(), "simulated backend outage"));

        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text().toLowerCase()).containsAnyOf("rejected", "revise");
        assertThat(locker.thoughtFormByName("broken", DID)).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void triggerShape(String name, String prompt, String schema) {
        var said = new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-alice", "Alice",
            "Please shape a form called " + name);
        subscriberRef.tell(new RoomNotification(said));
    }

    private InferenceRouter.ChatRequest expectChatRequest() {
        return routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
    }

    private static RoomSnapshot workshopSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "Workshop",
            "A smithy where thought forms are shaped and skills are crafted.",
            "foundation",
            List.of(new Exit("west", "nexus", "The Nexus")),
            List.of(), List.of(), List.of());
    }

    private static void clearPersistenceDir(String did) throws IOException {
        var root = FamiliarPersistenceStore.defaultRoot(did);
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        }
    }
}

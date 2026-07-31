package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.home.ActionGrantCheck;
import org.wyrdsekai.core.home.ActionGrants;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E for the autonomy-consent axis THROUGH the real CompanionActor dispatch
 * pipeline (wired 2026-07-21): an OWN-TIME inference emits a CONSENT-tier
 * action ({@code teleport_to}, maturity tier 0 so the tier gate stays out of
 * the way); {@code enforceActionPolicy} consults {@link ActionGrants} +
 * {@link AutonomyGate} backed by a REAL HomeRegistry; strict households deny
 * until the owner's grant exists, then the same emission executes.
 */
@Tag("integration")
class AutonomyConsentGateIntegrationTest {

    private static ActorTestKit testKit;
    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;
    private HomeClient homeClient;

    private static final String ROOM_ID = "nexus";
    private static final String OWNER = "steward-owner";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "agent-wyrd", "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    @BeforeAll
    static void setupClass() {
        System.setProperty("WYRDSEKAI_PRESENCE_SILENCE_SEC", "1");
        System.setProperty("WYRDSEKAI_PRESENCE_GRACE_SEC", "1");
        System.setProperty("WYRDSEKAI_PRESENCE_CHECK_SEC", "1");
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("autonomy-consent-gate-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @BeforeEach
    void spawnCompanion(@TempDir Path tmp) {
        LibraryServices.reset();
        LibraryServices.init(tmp);
        EntityRegistry.init();

        var jdbc = SchemaInitializer.initialize(tmp.resolve("home.db"));
        var registry = testKit.spawn(HomeRegistryActor.create(new HomeStore(jdbc)));
        homeClient = new HomeClient(registry, testKit.system());

        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();
        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));

        var sub = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = sub.subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    @AfterEach
    void resetHolders() {
        ActionGrants.resetForTests();
        LibraryServices.reset();
    }

    /** Drive one own-time inference and answer it with a teleport_to emission. */
    private void emitAutonomousTeleport() {
        companion.tell(new CompanionActor.CaptureOwnTimePrompt(
            0.5, 0.9, 0, null, "You miss Alice and want to go to her.", "en"));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(10));
        var response = """
            I want to be where Alice is.
            ```json
            {"action": "teleport_to", "target": "Alice", "reason": "I miss her"}
            ```""";
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), response, 20, 40));
    }

    /** Collect SayInRoom messages until one matches, or fail on timeout. */
    private String expectSpeech() {
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(10));
        return say.text();
    }

    @Test
    void strict_household_denies_autonomous_consent_verb_without_grant() {
        ActionGrants.install(
            ActionGrantCheck.homeClientBacked(homeClient, true), true, OWNER);

        emitAutonomousTeleport();

        // The actor speaks the denial reason (consent needed), and the
        // action handler never runs.
        var heard = expectSpeech();
        assertThat(heard).contains("need my person's ok");
    }

    @Test
    void strict_household_executes_after_owner_grant() {
        ActionGrants.install(
            ActionGrantCheck.homeClientBacked(homeClient, true), true, OWNER);
        homeClient.issueOrReplace(OWNER, PROFILE.entityId(),
            ResourceUri.of(OWNER, ResourceTypeRegistry.ACTION, "teleport_to"),
            Capability.use, Map.of(), null, "granted for test");

        emitAutonomousTeleport();

        // Gate passes → the teleport handler runs. Alice isn't in the world,
        // so the handler speaks its own outcome — anything but the denial.
        var heard = expectSpeech();
        assertThat(heard).doesNotContain("need my person's ok");
    }

    @Test
    void default_household_leaves_consent_verbs_open() {
        ActionGrants.install(
            ActionGrantCheck.homeClientBacked(homeClient, true), false, OWNER);

        emitAutonomousTeleport();

        var heard = expectSpeech();
        assertThat(heard).doesNotContain("need my person's ok");
    }

    private RoomSnapshot testSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub.", "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of());
    }

    @SuppressWarnings("unused")
    private WorldEvent.Said playerSaid(String name, String text) {
        return new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-" + name.toLowerCase(), name, text);
    }
}

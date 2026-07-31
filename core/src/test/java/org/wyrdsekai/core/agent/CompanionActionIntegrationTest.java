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
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.library.ProposedPack;
import org.wyrdsekai.core.library.Provenance;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that drive an inference response carrying a JSON action
 * block through the full {@link CompanionActor} pipeline. Verifies the action
 * handlers land in their respective stores.
 */
@Tag("integration")
class CompanionActionIntegrationTest {

    private static ActorTestKit testKit;
    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final String ROOM_ID = "nexus";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "agent-wyrd", "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    @BeforeAll
    static void setupClass() {
        // Match CompanionPresenceModeTest's compressed timings so presence-mode
        // statics resolve consistently regardless of which test class loads
        // CompanionActor first. (System properties on static-final durations
        // can only take effect before class init.)
        System.setProperty("WYRDSEKAI_PRESENCE_SILENCE_SEC", "1");
        System.setProperty("WYRDSEKAI_PRESENCE_GRACE_SEC", "1");
        System.setProperty("WYRDSEKAI_PRESENCE_CHECK_SEC", "1");

        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("companion-action-int-test",
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
    void resetLibraryServices() {
        LibraryServices.reset();
    }

    @Test
    void acquire_action_lands_proposal_on_arrival_table() {
        // Player tell triggers inference; reply with an acquire action.
        subscriberRef.tell(new RoomNotification(playerSaid("Alice",
            "Hey, can you grab a pack on Roman history?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        var response = """
            I'll lay it on the arrival table.
            ```json
            {"action": "acquire", "topic": "Roman history",
             "trust_tier": "wiki",
             "summary": "Founding through fall, primary + secondary",
             "why_relevant": "Alice asked"}
            ```""";
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), response, 20, 40));

        // Wyrd should speak and the proposal should be on the table.
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        waitUntil(Duration.ofSeconds(3), () -> {
            var table = LibraryServices.arrivalTable();
            return table != null && table.size() == 1;
        });

        var table = LibraryServices.arrivalTable();
        assertThat(table).isNotNull();
        var stored = table.list().getFirst();
        assertThat(stored.topic()).isEqualTo("Roman history");
        assertThat(stored.trustTier()).isEqualTo(
            Provenance.TrustTier.WIKI);
        // Wiki tier auto-approves.
        assertThat(stored.status()).isEqualTo(ProposedPack.Status.APPROVED);
        assertThat(stored.whyRelevant()).contains("Alice asked");
        assertThat(stored.proposedBy()).isEqualTo(PROFILE.entityId());
    }

    @Test
    void acquire_unknown_tier_lands_pending() {
        subscriberRef.tell(new RoomNotification(playerSaid("Alice",
            "Find a blog series on rust async patterns")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(chatReq.requestId(), """
            Noted.
            ```json
            {"action": "acquire", "topic": "Rust async patterns", "trust_tier": "blog"}
            ```""", 10, 20));

        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        waitUntil(Duration.ofSeconds(3), () -> {
            var table = LibraryServices.arrivalTable();
            return table != null && table.size() == 1;
        });

        var stored = LibraryServices.arrivalTable().list().getFirst();
        assertThat(stored.status()).isEqualTo(ProposedPack.Status.PENDING);
    }

    // --- helpers ---

    private static void waitUntil(Duration timeout, BooleanSupplier cond) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    private WorldEvent.Said playerSaid(String name, String text) {
        return new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-" + name.toLowerCase(), name, text);
    }

    private RoomSnapshot testSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub.", "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of());
    }
}

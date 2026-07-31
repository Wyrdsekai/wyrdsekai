package org.wyrdsekai.core.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE e2e for the craft hand-off fix (second-node 2026-07-08: "build me an item and hand it to me"
 * crafted the item but left the requester's inventory empty). Wires a real InventoryService over
 * a temp libsql DB so {@code maybeHandOffCraftedItem} actually fires, drives a build-and-hand-over
 * request against the 9B, and asserts the crafted item lands in the requester's inventory.
 *
 * <p>Self-skips when the 9B isn't reachable, and skips the assertion if the model delegated
 * ({@code dispatch_task}) instead of crafting a template item this run (stochastic — nothing to
 * hand over in that case).
 */
@Tag("integration")
@Tag("needs-llama")
class CraftHandoffLiveE2ETest {

    private static final String DRIVE_URL = "http://localhost:8200";
    private static final String DRIVE_MODEL = "wyrdsekai-3.5-9b-drive-v6-q4km.gguf";
    private static final String ROOM_ID = "nexus";

    private static final AgentProfile PROFILE = new AgentProfile(
        "mia", "agent-mia", "agent", "A companion in Wyrdsekai",
        "You are mia, a companion guide in Wyrdsekai.", 4096, 256, 0.7);

    private static ActorTestKit testKit;
    private static ActorRef<InferenceRouter.Command> router;

    @BeforeAll
    static void setUp() {
        assumeTrue(driveReachable(), "prod 9B not reachable on :8200 — skipping");
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("craft-handoff-e2e",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
        var backend = new InferenceBackend.LlamaServer(
            "prod9b", new InferenceClient(DRIVE_URL), 10, List.of(), null);
        router = testKit.spawn(InferenceRouter.create(
            List.of(backend), DRIVE_MODEL, null, Duration.ofMinutes(5)));
    }

    @AfterAll
    static void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
        System.clearProperty("wyrdsekai.jdbc.url");
    }

    @Test
    void build_and_hand_over_lands_the_item_in_the_requester_inventory(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("inv.db"));
        System.setProperty("wyrdsekai.jdbc.url", jdbc);
        var inventory = new InventoryService(jdbc);

        LibraryServices.reset();
        LibraryServices.init(tmp);
        EntityRegistry.init();

        var actorLogger = (Logger) LoggerFactory.getLogger(CompanionActor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        actorLogger.addAppender(appender);
        actorLogger.setLevel(Level.INFO);

        TestProbe<RoomCommand> roomProbe = testKit.createTestProbe();
        var companion = testKit.spawn(CompanionActor.create(PROFILE, roomProbe.ref(), ROOM_ID, router, null));
        var sub = roomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        var subscriber = sub.subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5))
            .replyTo().tell(new RoomResponse.Ok(testSnapshot()));

        subscriber.tell(new RoomNotification(new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-operator", "operator",
            "Build me a simple item that searches the web, craft it from a template, and hand it to me.")));

        boolean crafted = awaitLog(appender, "Crafted item '", Duration.ofSeconds(150));

        actorLogger.detachAppender(appender);
        appender.stop();
        testKit.stop(companion);

        System.out.println("\n[CraftHandoff] crafted=" + crafted
            + " handedLog=" + logContains(appender, "Handed ")
            + " requesterItems(player-operator)=" + inventory.countItems("player-operator"));

        assumeTrue(crafted, "model delegated instead of crafting a template item this run — nothing to hand over");

        // The hand-off must have fired and put the item in the requester's inventory.
        assertThat(logContains(appender, "Handed "))
            .as("finalizeCraftedItem must hand a template-crafted item to the requester on explicit handoff intent")
            .isTrue();
        assertThat(inventory.countItems("player-operator"))
            .as("crafted item must be in the requester's inventory so they can use it")
            .isGreaterThan(0);
    }

    private boolean awaitLog(ListAppender<ILoggingEvent> appender, String needle, Duration timeout)
            throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (logContains(appender, needle)) return true;
            Thread.sleep(500);
        }
        return false;
    }

    private boolean logContains(ListAppender<ILoggingEvent> appender, String needle) {
        for (var ev : List.copyOf(appender.list)) {
            var m = ev.getFormattedMessage();
            if (m != null && m.contains(needle)) return true;
        }
        return false;
    }

    private RoomSnapshot testSnapshot() {
        return new RoomSnapshot(ROOM_ID, "The Nexus", "A shimmering hub.", "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of());
    }

    private static boolean driveReachable() {
        try {
            var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(DRIVE_URL + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) { return false; }
    }
}

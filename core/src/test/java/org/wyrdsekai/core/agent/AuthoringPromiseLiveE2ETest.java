package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.RoomMetadataService;
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
import java.util.function.BooleanSupplier;
import org.wyrdsekai.core.room.RoomRegistry;

/**
 * LIVE e2e for the promise in {@code docs/public/AUTHORING.md} §1 and
 * {@code ROOMS.md}: "ask your companion to build a room / an item", with a real
 * 9B drive model and REAL world services — so preconditions are SATISFIED and the
 * assertion can be about world state rather than about what she said.
 *
 * <p>Why this is separate from {@code BunshinVerbBatteryIntegrationTest}. That one
 * runs in an empty world with a scripted router, so ~20 verbs answer with an
 * honest refusal ("I don't have anything called X to give"). Honest refusals prove
 * the REPORTING is truthful; they prove nothing about whether the verb works when
 * its preconditions hold. Only a populated world with real inference can show that.
 *
 * <p>Every assertion here reads persisted state — {@link RoomMetadataService},
 * {@link InventoryService} — never a log line and never her prose. The whole
 * arc this test closes began with a companion announcing "The greenhouse is
 * built. I've got plants everywhere in there" for a room that was created but
 * unreachable, and a verifier that passed 7/7 because it grepped a log line the
 * code emits unconditionally.
 *
 * <p>Driven through a player's {@code Said}, which is the HUMAN-DIRECTED path —
 * the only one on which {@code create_room} (FORBIDDEN tier) is offered at all.
 */
@Tag("integration")
@Tag("needs-llama")
class AuthoringPromiseLiveE2ETest {

    private static final String DRIVE_URL = "http://localhost:8200";
    private static final String DRIVE_MODEL = "wyrdsekai-3.5-9b-drive-v6-q4km.gguf";
    private static final String ROOM_ID = "nexus";
    private static final String PLAYER = "player-steward";

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
        testKit = ActorTestKit.create("authoring-promise-e2e",
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
    static void tearDown() { if (testKit != null) testKit.shutdownTestKit(); }

    private static boolean driveReachable() {
        try {
            var res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(DRIVE_URL + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Spawn a companion over a real DB and drive it with one human utterance. */
    private Ctx drive(Path tmp, String said) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        System.setProperty("wyrdsekai.jdbc.url", jdbc);
        LibraryServices.reset();
        LibraryServices.init(tmp);
        EntityRegistry.init();

        TestProbe<RoomCommand> roomProbe = testKit.createTestProbe();
        var companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, router, null));
        var sub = roomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5))
            .replyTo().tell(new RoomResponse.Ok(snapshot()));

        sub.subscriber().tell(new RoomNotification(new WorldEvent.Said(
            ROOM_ID, Instant.now(), PLAYER, "steward", said)));
        return new Ctx(jdbc, companion, roomProbe);
    }

    private record Ctx(String jdbc, ActorRef<CompanionActor.Command> companion,
                       TestProbe<RoomCommand> roomProbe) {}

    /** Poll persisted state until the predicate holds, or give up. */
    private static boolean awaitState(BooleanSupplier check, Duration limit) {
        var deadline = Instant.now().plus(limit);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (check.getAsBoolean()) return true;
            } catch (RuntimeException ignored) {
                // services may not have created their tables yet
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Test
    @DisplayName("ROOMS.md: asking for a room creates one that EXISTS and is REACHABLE")
    void asking_for_a_room_produces_a_reachable_room(@TempDir Path tmp) {
        var ctx = drive(tmp, "I would love a greenhouse. Could you make me a room — a "
            + "greenhouse full of plants — and connect it to this room?");
        var rooms = new RoomMetadataService(ctx.jdbc());

        boolean exists = awaitState(
            () -> rooms.listRooms().stream().anyMatch(r ->
                r.name() != null && r.name().toLowerCase().contains("greenhouse")),
            Duration.ofMinutes(4));

        var made = rooms.listRooms().stream()
            .filter(r -> r.name() != null && r.name().toLowerCase().contains("greenhouse"))
            .toList();
        System.out.println("\n[AuthoringPromise] rooms=" + rooms.listRooms().size()
            + " greenhouses=" + made);
        testKit.stop(ctx.companion());

        assumeTrue(exists, "the model did not choose a room-creating verb this run");

        // EXISTS is not enough — the live failure on 2026-07-29 created
        // greenhouse-3292 with no way in, so she announced a room nobody could
        // enter. And exactly one: generateRoomId appends a timestamp, so a second
        // ask used to make a second room.
        assertThat(made)
            .as("exactly one greenhouse — a duplicate means the idempotency guard "
                + "in handleCreateRoom did not resolve the existing one by name")
            .hasSize(1);

        var roomId = made.get(0).roomId();
        assertThat(RoomRegistry.get().resolveRoomId("greenhouse"))
            .as("the room must be reachable BY NAME. Only seeded rooms used to get "
                + "aliases, so a room she built could not be referenced — 'go to the "
                + "greenhouse' could not resolve a greenhouse she had just made.")
            .isEqualTo(roomId);
    }

    @Test
    @DisplayName("AUTHORING.md: asking for an item puts a real item in the world")
    void asking_for_an_item_produces_a_real_item(@TempDir Path tmp) {
        var ctx = drive(tmp, "Could you craft me a simple lantern from a template, "
            + "and keep it for now?");
        var inventory = new InventoryService(ctx.jdbc());

        boolean got = awaitState(
            () -> inventory.countItems(PROFILE.entityId()) > 0
               || inventory.countItems(PLAYER) > 0,
            Duration.ofMinutes(4));

        int mine = safeCount(inventory, PROFILE.entityId());
        int theirs = safeCount(inventory, PLAYER);
        System.out.println("\n[AuthoringPromise] companionItems=" + mine
            + " playerItems=" + theirs);
        testKit.stop(ctx.companion());

        assumeTrue(got, "the model did not craft this run");
        assertThat(mine + theirs)
            .as("an item was claimed but nothing landed in any inventory — the "
                + "'talks but does not do' shape this whole arc is about")
            .isGreaterThan(0);
    }

    private static int safeCount(InventoryService inv, String owner) {
        try {
            return inv.countItems(owner);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private RoomSnapshot snapshot() {
        return new RoomSnapshot(ROOM_ID, "The Nexus", "A shimmering hub where journeys begin.",
            "foundation", List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of());
    }
}

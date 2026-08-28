package org.wyrdsekai.core.familiar;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.Rooms;
import org.wyrdsekai.core.room.WorkshopProvisioner;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.soul.BondStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * -§3 — end-to-end summon ceremony with the
 * real {@link ZoneGuardian} actor + real {@link BondStore}.
 *
 * <p>{@link CodingFamiliarSummonerTest} unit-covers the ceremony with
 * {@code zoneGuardian = null}. This integration test wires the full
 * pipeline: summon → workshop actor spawned + reachable from registry,
 * bond persisted to JDBC, identity file written. Proves the three
 * ceremony side-effects compose correctly across actor + DB + filesystem.</p>
 */
@Tag("integration")
class CodingFamiliarSummonIntegrationTest {

    private static final String BONDHOLDER = "did:wyrd:user:operator";
    private static final String PARENT = "did:wyrd:companion:wyrd-of-operator";

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        var config = ConfigFactory.parseString("""
            pekko.actor.provider = local
            pekko.persistence.journal.plugin = "pekko.persistence.journal.inmem"
            pekko.persistence.snapshot-store.plugin = "pekko.persistence.snapshot-store.local"
            pekko.persistence.snapshot-store.local.dir = "target/test-snapshots-summon-integ"
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.room.RoomEvent" = jackson-json
              "org.wyrdsekai.core.room.RoomState" = jackson-json
              "org.wyrdsekai.core.room.RoomCommand" = jackson-json
              "org.wyrdsekai.core.room.RoomNotification" = jackson-json
              "org.wyrdsekai.core.room.RoomResponse" = jackson-json
            }
            """);
        testKit = ActorTestKit.create(config);
        RoomRegistry.get().setScheduler(testKit.scheduler());
        Rooms.setScheduler(testKit.scheduler());
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test void full_ceremony_provisions_room_writes_identity_and_records_bond(
            @TempDir Path workspace) throws IOException, InterruptedException {

        // Real ZoneGuardian.
        var guardian = testKit.spawn(
            ZoneGuardian.create(null, List.of(), null, null));
        Thread.sleep(3500); // close the deferred seeding window

        // Real BondStore via SchemaInitializer.
        var jdbc = SchemaInitializer.initialize(workspace.resolve("bonds.db"));
        var bondStore = new BondStore(jdbc);

        // Real CodingFamiliarRegistry against a temp souls root.
        var registry = new CodingFamiliarRegistry(workspace.resolve("souls"));

        var summoner = new CodingFamiliarSummoner(registry, bondStore, guardian);
        var outcome = summoner.firstSummon(BONDHOLDER, "Operator", PARENT, "Coder");

        // Outcome shape — workshop request fired, bond recorded, identity new.
        assertThat(outcome.alreadyExisted()).isFalse();
        assertThat(outcome.workshopRequested()).isTrue();
        assertThat(outcome.bondRecorded()).isTrue();
        assertThat(outcome.identity().name()).isEqualTo("Coder");

        // Allow async room spawn to complete.
        Thread.sleep(800);

        // 1) Workshop room actor reachable from the registry.
        var roomId = WorkshopProvisioner.workshopRoomId(BONDHOLDER);
        var roomRef = RoomRegistry.get().ref(roomId);
        assertThat(roomRef)
            .as("Workshop room actor must be live after summon ceremony")
            .isNotNull();
        var probe = testKit.createTestProbe(RoomSnapshot.class);
        roomRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));
        var snap = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(snap.name()).isEqualTo("Operator's CodeZaiku Workshop");

        // 2) Identity file persisted to disk — fresh registry sees it.
        var freshRegistry = new CodingFamiliarRegistry(workspace.resolve("souls"));
        var loaded = freshRegistry.get(BONDHOLDER);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().name()).isEqualTo("Coder");
        assertThat(loaded.get().parentAgentDid()).isEqualTo(PARENT);

        // 3) Bond recorded in JDBC store with correct shape.
        var bondId = CodingFamiliarSummoner.bondIdFor(BONDHOLDER, outcome.identity().did());
        var savedBond = bondStore.get(bondId);
        assertThat(savedBond).isPresent();
        assertThat(savedBond.get().agentADid()).isEqualTo(BONDHOLDER);
        assertThat(savedBond.get().agentBDid()).isEqualTo(outcome.identity().did());
        assertThat(savedBond.get().active()).isTrue();
        assertThat(savedBond.get().interactionCount()).isEqualTo(1);
    }

    @Test void resummon_is_idempotent_across_all_three_side_effects(
            @TempDir Path workspace) throws IOException, InterruptedException {
        var guardian = testKit.spawn(
            ZoneGuardian.create(null, List.of(), null, null));
        Thread.sleep(3500);

        var jdbc = SchemaInitializer.initialize(workspace.resolve("bonds.db"));
        var bondStore = new BondStore(jdbc);
        var registry = new CodingFamiliarRegistry(workspace.resolve("souls"));
        var summoner = new CodingFamiliarSummoner(registry, bondStore, guardian);

        var first = summoner.firstSummon(BONDHOLDER, "Operator", PARENT, "Coder");
        Thread.sleep(400);
        var second = summoner.firstSummon(BONDHOLDER, "Operator", PARENT, "Coder");
        Thread.sleep(400);

        assertThat(first.alreadyExisted()).isFalse();
        assertThat(second.alreadyExisted()).isTrue();
        assertThat(second.identity().did()).isEqualTo(first.identity().did());

        // Room is still the same actor.
        var roomRef = RoomRegistry.get().ref(
            WorkshopProvisioner.workshopRoomId(BONDHOLDER));
        assertThat(roomRef).isNotNull();

        // Bond interactionCount incremented on the second summon.
        var bondId = CodingFamiliarSummoner.bondIdFor(BONDHOLDER, first.identity().did());
        assertThat(bondStore.get(bondId).orElseThrow().interactionCount())
            .isEqualTo(2);

        // Exactly one identity file on disk.
        var familiarsDir = workspace.resolve("souls").resolve("familiars");
        try (var stream = Files.list(familiarsDir)) {
            assertThat(stream.count()).isEqualTo(1);
        }
    }
}

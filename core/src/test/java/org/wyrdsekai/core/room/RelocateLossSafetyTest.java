package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionTransitState;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loss-safety half of the cross-zone relocate fence (spec/tla/TransitToken.tla, P1
 * Finding 1). Drives the source-side two-phase handoff state machine through the
 * {@link ZoneGuardian} message API with an in-memory relocator:
 *
 * <ul>
 *   <li>DEPART publishes + retains the snapshot (does not release on publish);</li>
 *   <li>an arrival ack ({@link ZoneGuardian.CompanionArrivedAck}) releases ownership
 *       — no retry, no revive;</li>
 *   <li>a never-arriving ack drives bounded re-publish and then a local revive, so a
 *       dropped token never loses the companion.</li>
 * </ul>
 *
 * <p>The ack timeout is shrunk via the {@code wyrdsekai.relocate.*} system properties
 * (read per-{@link ZoneGuardian}-instance at construction) so the machine runs fast.
 * The network-drop timing itself is what the two-zone live mesh test proves; this
 * pins the runtime logic that sits on top of it.</p>
 */
@Tag("integration")
class RelocateLossSafetyTest {

    private static ActorTestKit testKit;

    private static final AgentProfile BASE = new AgentProfile(
        "Wyrd", "wyrd-loss-safety", "agent",
        "A companion in Wyrdsekai", "You are Wyrd.", 4096, 256, 0.7,
        "did:key:z6MkLossSafety");

    @BeforeAll
    static void setup() {
        // Read by ZoneGuardian at construction — set before any guardian spawns.
        System.setProperty("wyrdsekai.relocate.ackTimeoutMs", "400");
        System.setProperty("wyrdsekai.relocate.maxAttempts", "2");
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("relocate-loss-safety",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """));
        RoomRegistry.get().setScheduler(testKit.scheduler());
        Rooms.setScheduler(testKit.scheduler());
    }

    @AfterAll
    static void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
        System.clearProperty("wyrdsekai.relocate.ackTimeoutMs");
        System.clearProperty("wyrdsekai.relocate.maxAttempts");
    }

    private static void awaitUntil(Duration timeout, BooleanSupplier cond) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { return; }
        }
        if (!cond.getAsBoolean()) {
            throw new AssertionError("condition not met within " + timeout);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /** A publish capture so the test can read the minted epoch + count retries. */
    private record Published(String entityId, long epoch) {}

    private record Fixture(ActorRef<ZoneGuardian.Command> guardian,
                           CopyOnWriteArrayList<Published> published) {}

    /** Spawn a guardian + a live companion (via ARRIVE), wiring a capturing relocator. */
    private Fixture freshFixtureFor(String entityId) {
        var published = new CopyOnWriteArrayList<Published>();
        var guardian = testKit.spawn(ZoneGuardian.create(null, List.of(), null, null));
        sleep(300);   // let the no-seed seed-timeout fire

        var router = testKit.<InferenceRouter.Command>createTestProbe();

        ZoneGuardian.CompanionRelocator relocator =
            (targetZoneId, sourceZoneId, state, bondholderDid, targetRoomHint) ->
                published.add(new Published(state.profile().entityId(), state.transitEpoch()));
        guardian.tell(new ZoneGuardian.SetCompanionRelocator(relocator));

        // Populate a live companion at the source via an ARRIVE — this also caches the
        // inference router on the guardian, which the give-up revive path needs.
        var profile = new AgentProfile(BASE.name(), entityId, "agent",
            BASE.description(), BASE.systemPrompt(), 4096, 256, 0.7, BASE.did());
        var state = new CompanionTransitState(profile, "hash-0",
            Map.of("energy", 0.5), Map.of(), "neutral", "PRESENT_WITH_USER",
            List.of(), "study-source", "en", Instant.now());
        guardian.tell(ZoneGuardian.RelocateCompanion.arrive(
            state, "zoneOrigin", "zoneSource", "did:key:bond", "study-source",
            router.ref(), null));

        awaitUntil(Duration.ofSeconds(5),
            () -> ZoneGuardian.getCompanionRef(null, entityId) != null);
        return new Fixture(guardian, published);
    }

    @Test
    void an_arrival_ack_releases_ownership_without_retry_or_revive() {
        var entityId = "wyrd-ack-release";
        var fx = freshFixtureFor(entityId);

        fx.guardian().tell(ZoneGuardian.RelocateCompanion.depart(
            BASE.did(), entityId, "zonePeer", "zoneSource", "did:key:bond", "atrium"));

        // The source publishes exactly once and retains the snapshot — it does NOT
        // release on publish (that is the loss-safety change).
        awaitUntil(Duration.ofSeconds(5), () -> !fx.published().isEmpty());
        long epoch = fx.published().get(0).epoch();
        assertThat(epoch).isGreaterThan(0L);

        // Confirm arrival — releases the pending departure.
        fx.guardian().tell(new ZoneGuardian.CompanionArrivedAck(
            entityId, BASE.did(), epoch, "zonePeer"));

        // Several ack-timeout windows pass; with the release there must be NO retry
        // publish and NO local revive (single owner is now the target).
        sleep(1600);
        assertThat(fx.published()).hasSize(1);
        assertThat(ZoneGuardian.getCompanionRef(null, entityId))
            .as("companion released at source, not revived")
            .isNull();
    }

    @Test
    void a_never_acked_relocate_retries_then_revives_at_source_so_it_is_never_lost() {
        var entityId = "wyrd-no-ack-revive";
        var fx = freshFixtureFor(entityId);

        fx.guardian().tell(ZoneGuardian.RelocateCompanion.depart(
            BASE.did(), entityId, "zonePeer", "zoneSource", "did:key:bond", "atrium"));

        // No ack is ever sent. With maxAttempts=2 the source publishes the initial
        // token then re-publishes once, giving 2 publishes, and finally revives the
        // companion locally — never lost.
        awaitUntil(Duration.ofSeconds(5), () -> fx.published().size() >= 2);
        assertThat(fx.published()).extracting(Published::entityId).containsOnly(entityId);

        awaitUntil(Duration.ofSeconds(5),
            () -> ZoneGuardian.getCompanionRef(null, entityId) != null);
        assertThat(ZoneGuardian.getCompanionRef(null, entityId))
            .as("companion revived at source after the handoff was never confirmed")
            .isNotNull();
    }

    @Test
    void a_stale_epoch_ack_does_not_release_a_newer_pending_departure() {
        var entityId = "wyrd-stale-ack";
        var fx = freshFixtureFor(entityId);

        fx.guardian().tell(ZoneGuardian.RelocateCompanion.depart(
            BASE.did(), entityId, "zonePeer", "zoneSource", "did:key:bond", "atrium"));
        awaitUntil(Duration.ofSeconds(5), () -> !fx.published().isEmpty());
        long epoch = fx.published().get(0).epoch();

        // A stale ack for an older epoch must be ignored — the pending departure stays
        // open, so the loss-safety retry/revive still fires.
        fx.guardian().tell(new ZoneGuardian.CompanionArrivedAck(
            entityId, BASE.did(), epoch - 1, "zonePeer"));

        awaitUntil(Duration.ofSeconds(5),
            () -> ZoneGuardian.getCompanionRef(null, entityId) != null);
        assertThat(ZoneGuardian.getCompanionRef(null, entityId)).isNotNull();
    }
}

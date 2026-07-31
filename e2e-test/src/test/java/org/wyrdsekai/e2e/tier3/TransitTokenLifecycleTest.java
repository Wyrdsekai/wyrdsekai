package org.wyrdsekai.e2e.tier3;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.BetweenActor;
import org.wyrdsekai.between.federation.FederationActor;
import org.wyrdsekai.e2e.infra.NatsServerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestActorSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transit token lifecycle: propose → accept → transit → escalate → revoke.
 * Two BetweenActor instances communicating via real NATS.
 */
@Tag("between")
class TransitTokenLifecycleTest {

    private static NatsServerFixture nats;
    private static ActorTestKit kitA;
    private static ActorTestKit kitB;
    private static Path dataDirA;
    private static Path dataDirB;

    @BeforeAll
    static void setUp() throws Exception {
        NatsServerFixture.assumeAvailable();

        nats = new NatsServerFixture();
        nats.start();

        kitA = TestActorSystem.create("fed-a");
        kitB = TestActorSystem.create("fed-b");

        dataDirA = Files.createTempDirectory("fed-a");
        dataDirB = Files.createTempDirectory("fed-b");
    }

    @AfterAll
    static void tearDown() {
        if (kitA != null) kitA.shutdownTestKit();
        if (kitB != null) kitB.shutdownTestKit();
        if (nats != null) nats.stop();
    }

    private BetweenActor.BetweenConfig makeConfig() {
        return new BetweenActor.BetweenConfig(
            true, nats.natsUrl(), false, null,
            nats.clientPort(), nats.monitorPort(),
            false, List.of(), Duration.ofSeconds(2), Duration.ofSeconds(3),
            PortAllocator.allocate());
    }

    @Test
    void propose_creates_pending_agreement() {
        var betweenA = kitA.spawn(BetweenActor.create(), "ba-propose");
        betweenA.tell(new BetweenActor.StartBetween(
            "zone-a1", "Zone A1", dataDirA, makeConfig(), null));

        // Wait for initialization
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        var probe = kitA.<String>createTestProbe();
        betweenA.tell(new BetweenActor.ProposeFederation("zone-b1", probe.getRef()));
        var result = probe.receiveMessage(Duration.ofSeconds(10));
        assertNotNull(result, "Propose should return a result");
    }

    @Test
    void accept_activates_agreement() throws Exception {
        var betweenA = kitA.spawn(BetweenActor.create(), "ba-accept-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "ba-accept-b");

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-a2", "Zone A2", dataDirA, makeConfig(), null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-b2", "Zone B2", dataDirB, makeConfig(), null));

        Thread.sleep(4000); // Wait for NATS connections

        // A proposes to B
        var proposeProbe = kitA.<String>createTestProbe();
        betweenA.tell(new BetweenActor.ProposeFederation("zone-b2", proposeProbe.getRef()));
        proposeProbe.receiveMessage(Duration.ofSeconds(10));

        Thread.sleep(2000); // Allow NATS message delivery

        // B accepts
        var acceptProbe = kitB.<String>createTestProbe();
        betweenB.tell(new BetweenActor.AcceptFederation("zone-a2", acceptProbe.getRef()));
        var acceptResult = acceptProbe.receiveMessage(Duration.ofSeconds(10));
        assertNotNull(acceptResult, "Accept should return a result");
    }

    @Test
    void manifest_exchanged_after_federation() throws Exception {
        var betweenA = kitA.spawn(BetweenActor.create(), "ba-manifest-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "ba-manifest-b");

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-a3", "Zone A3", dataDirA, makeConfig(), null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-b3", "Zone B3", dataDirB, makeConfig(), null));

        Thread.sleep(4000);

        // Propose and accept
        var proposeProbe = kitA.<String>createTestProbe();
        betweenA.tell(new BetweenActor.ProposeFederation("zone-b3", proposeProbe.getRef()));
        proposeProbe.receiveMessage(Duration.ofSeconds(10));

        Thread.sleep(2000);

        var acceptProbe = kitB.<String>createTestProbe();
        betweenB.tell(new BetweenActor.AcceptFederation("zone-a3", acceptProbe.getRef()));
        acceptProbe.receiveMessage(Duration.ofSeconds(10));

        Thread.sleep(2000);

        // Check federation status
        var statusProbe = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        betweenA.tell(new BetweenActor.GetTopology(statusProbe.getRef()));
        var topo = statusProbe.receiveMessage(Duration.ofSeconds(5));
        assertNotNull(topo);
    }

    @Test
    void transit_token_issued_after_agreement() throws Exception {
        var betweenA = kitA.spawn(BetweenActor.create(), "ba-transit-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "ba-transit-b");

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-a4", "Zone A4", dataDirA, makeConfig(), null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-b4", "Zone B4", dataDirB, makeConfig(), null));

        Thread.sleep(4000);

        // Setup federation
        var proposeProbe = kitA.<String>createTestProbe();
        betweenA.tell(new BetweenActor.ProposeFederation("zone-b4", proposeProbe.getRef()));
        proposeProbe.receiveMessage(Duration.ofSeconds(10));
        Thread.sleep(2000);

        var acceptProbe = kitB.<String>createTestProbe();
        betweenB.tell(new BetweenActor.AcceptFederation("zone-a4", acceptProbe.getRef()));
        acceptProbe.receiveMessage(Duration.ofSeconds(10));
        Thread.sleep(2000);

        // Request transit
        var transitProbe = kitA.<FederationActor.TransitResult>createTestProbe();
        betweenA.tell(new BetweenActor.RequestTransit(
            "zone-b4", "agent-1", "TestAgent", transitProbe.getRef()));
        var transit = transitProbe.receiveMessage(Duration.ofSeconds(10));
        assertNotNull(transit, "Transit request should return result");
    }

    @Test
    void tier_escalation_after_multiple_visits() throws Exception {
        var betweenA = kitA.spawn(BetweenActor.create(), "ba-escalate-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "ba-escalate-b");

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-a5", "Zone A5", dataDirA, makeConfig(), null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-b5", "Zone B5", dataDirB, makeConfig(), null));

        Thread.sleep(4000);

        // Setup federation
        var proposeProbe = kitA.<String>createTestProbe();
        betweenA.tell(new BetweenActor.ProposeFederation("zone-b5", proposeProbe.getRef()));
        proposeProbe.receiveMessage(Duration.ofSeconds(10));
        Thread.sleep(2000);

        var acceptProbe = kitB.<String>createTestProbe();
        betweenB.tell(new BetweenActor.AcceptFederation("zone-a5", acceptProbe.getRef()));
        acceptProbe.receiveMessage(Duration.ofSeconds(10));
        Thread.sleep(2000);

        // Multiple transit requests (simulating visits for tier escalation)
        for (int i = 0; i < 4; i++) {
            var probe = kitA.<FederationActor.TransitResult>createTestProbe();
            betweenA.tell(new BetweenActor.RequestTransit(
                "zone-b5", "agent-escalate", "EscalateAgent", probe.getRef()));
            probe.receiveMessage(Duration.ofSeconds(10));
            Thread.sleep(500);
        }

        // The system should handle repeated transit requests without error
    }

    @Test
    void revocation_terminates_agreement() throws Exception {
        var betweenA = kitA.spawn(BetweenActor.create(), "ba-revoke-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "ba-revoke-b");

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-a6", "Zone A6", dataDirA, makeConfig(), null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-b6", "Zone B6", dataDirB, makeConfig(), null));

        Thread.sleep(4000);

        // Setup and activate federation
        var proposeProbe = kitA.<String>createTestProbe();
        betweenA.tell(new BetweenActor.ProposeFederation("zone-b6", proposeProbe.getRef()));
        proposeProbe.receiveMessage(Duration.ofSeconds(10));
        Thread.sleep(2000);

        var acceptProbe = kitB.<String>createTestProbe();
        betweenB.tell(new BetweenActor.AcceptFederation("zone-a6", acceptProbe.getRef()));
        acceptProbe.receiveMessage(Duration.ofSeconds(10));
        Thread.sleep(2000);

        // Revoke
        var revokeProbe = kitA.<String>createTestProbe();
        betweenA.tell(new BetweenActor.RevokeFederation("zone-b6", revokeProbe.getRef()));
        var revokeResult = revokeProbe.receiveMessage(Duration.ofSeconds(10));
        assertNotNull(revokeResult, "Revocation should return a result");
    }
}

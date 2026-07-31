package org.wyrdsekai.core.economy;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W5 (2026-07-11): the Counting House write API (Transfer/QueryBalance) is
 * reached through this static gateway by the household_treasury item's
 * provider methods. A stub behavior stands in for the real
 * CountingHouseActor — actor-side Transfer semantics have their own tests.
 */
class CountingHouseGatewayTest {

    private static ActorTestKit testKit;

    /** Stub Counting House: fixed replies, no persistence. */
    private static Behavior<CountingHouseCommand> stub() {
        return Behaviors.receive(CountingHouseCommand.class)
            .onMessage(CountingHouseCommand.Transfer.class, cmd -> {
                cmd.replyTo().tell("Transfer complete: " + cmd.amount() + " credits "
                    + cmd.fromEntity() + " -> " + cmd.toEntity());
                return Behaviors.same();
            })
            .onMessage(CountingHouseCommand.QueryBalance.class, cmd -> {
                cmd.replyTo().tell(new CreditBalance(cmd.entityId(), 42, 100, 50, 8));
                return Behaviors.same();
            })
            .build();
    }

    @BeforeAll static void setupKit() {
        testKit = ActorTestKit.create();
    }

    @AfterAll static void tearDownKit() {
        // Leave the gateway unwired for any later test in this JVM.
        CountingHouseGateway.install(null, null);
        testKit.shutdownTestKit();
    }

    @Test
    void unwired_gateway_reports_unavailable() {
        CountingHouseGateway.install(null, null);
        assertFalse(CountingHouseGateway.available());
        assertTrue(CountingHouseGateway.transfer("a", "b", 5, "note").isEmpty());
        assertTrue(CountingHouseGateway.balance("a").isEmpty());
    }

    @Test
    void wired_gateway_round_trips_transfer_and_balance() {
        var actor = testKit.spawn(stub());
        CountingHouseGateway.install(actor, testKit.system().scheduler());
        assertTrue(CountingHouseGateway.available());

        var outcome = CountingHouseGateway.transfer("alice", "bob", 7, "for tea");
        assertTrue(outcome.isPresent());
        assertTrue(outcome.get().startsWith("Transfer complete"));

        var balance = CountingHouseGateway.balance("alice");
        assertTrue(balance.isPresent());
        assertEquals(42, balance.get().balance());
        assertEquals("alice", balance.get().entityId());
    }
}

package org.wyrdsekai.core.spike;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.apache.pekko.persistence.testkit.query.javadsl.PersistenceTestKitReadJournal;
import org.apache.pekko.persistence.typed.ReplicaId;
import org.apache.pekko.persistence.typed.ReplicationId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.ReplicatedEventSourcedBehavior;
import org.apache.pekko.persistence.typed.javadsl.ReplicatedEventSourcing;
import org.apache.pekko.persistence.typed.javadsl.ReplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike test (Phase 0): Verify Pekko Replicated Event Sourcing works with our stack.
 *
 * Validates:
 * 1. Two replicas of same entity persist events independently
 * 2. Counter-like state converges via commutative merge
 * 3. Set-like state converges via union merge
 * 4. State recovers from journal after replica restart
 */
class ReplicatedEventSourcingSpikeTest {

    // ====== RES Behavior for the spike ======

    static class SpikeEntity extends ReplicatedEventSourcedBehavior<
            SpikeEntity.Command, SpikeEntity.Event, SpikeEntity.State> {

        sealed interface Command {}
        record Add(long amount, ActorRef<State> replyTo) implements Command {}
        record PutItem(String item, ActorRef<State> replyTo) implements Command {}
        record GetState(ActorRef<State> replyTo) implements Command {}

        sealed interface Event {}
        record Added(long delta) implements Event {}
        record ItemPut(String item) implements Event {}

        record State(long total, Set<String> items, int concurrentCount) {
            static State empty() {
                return new State(0, Set.of(), 0);
            }
        }

        static Behavior<Command> create(String entityId, ReplicaId self, Set<ReplicaId> all) {
            return ReplicatedEventSourcing.commonJournalConfig(
                new ReplicationId("SpikeEntity", entityId, self),
                all,
                PersistenceTestKitReadJournal.Identifier(),
                SpikeEntity::new
            );
        }

        private SpikeEntity(ReplicationContext ctx) {
            super(ctx);
        }

        @Override
        public State emptyState() {
            return State.empty();
        }

        @Override
        public CommandHandler<Command, Event, State> commandHandler() {
            return newCommandHandlerBuilder()
                .forAnyState()
                .onCommand(Add.class, (state, cmd) ->
                    Effect().persist(new Added(cmd.amount()))
                        .thenReply(cmd.replyTo(), s -> s))
                .onCommand(PutItem.class, (state, cmd) ->
                    Effect().persist(new ItemPut(cmd.item()))
                        .thenReply(cmd.replyTo(), s -> s))
                .onCommand(GetState.class, (state, cmd) -> {
                    cmd.replyTo().tell(state);
                    return Effect().none();
                })
                .build();
        }

        @Override
        public EventHandler<State, Event> eventHandler() {
            return newEventHandlerBuilder()
                .forAnyState()
                .onEvent(Added.class, (state, event) -> {
                    int cc = getReplicationContext().concurrent()
                        ? state.concurrentCount() + 1 : state.concurrentCount();
                    return new State(state.total() + event.delta(), state.items(), cc);
                })
                .onEvent(ItemPut.class, (state, event) -> {
                    var newItems = new HashSet<>(state.items());
                    newItems.add(event.item());
                    int cc = getReplicationContext().concurrent()
                        ? state.concurrentCount() + 1 : state.concurrentCount();
                    return new State(state.total(), Set.copyOf(newItems), cc);
                })
                .build();
        }
    }

    // ====== Test Setup ======

    static final ReplicaId A = new ReplicaId("A");
    static final ReplicaId B = new ReplicaId("B");
    static final Set<ReplicaId> ALL = Set.of(A, B);

    static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create(EventSourcedBehaviorTestKit.config());
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    // ====== Tests ======

    @Test
    void two_replicas_converge_counter() {
        var a = testKit.spawn(SpikeEntity.create("c1", A, ALL));
        var b = testKit.spawn(SpikeEntity.create("c1", B, ALL));
        var probe = testKit.createTestProbe(SpikeEntity.State.class);

        // Increment on replica A
        a.tell(new SpikeEntity.Add(5, probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Increment on replica B
        b.tell(new SpikeEntity.Add(3, probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Both should converge to 8
        awaitConvergence(a, probe, s -> s.total() == 8);
        awaitConvergence(b, probe, s -> s.total() == 8);
    }

    @Test
    void set_union_merge_across_replicas() {
        var a = testKit.spawn(SpikeEntity.create("c2", A, ALL));
        var b = testKit.spawn(SpikeEntity.create("c2", B, ALL));
        var probe = testKit.createTestProbe(SpikeEntity.State.class);

        // Add different items on each replica
        a.tell(new SpikeEntity.PutItem("sword", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        b.tell(new SpikeEntity.PutItem("shield", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Both should have both items
        awaitConvergence(a, probe, s ->
            s.items().contains("sword") && s.items().contains("shield"));
        awaitConvergence(b, probe, s ->
            s.items().contains("sword") && s.items().contains("shield"));
    }

    @Test
    void replica_recovers_from_journal() throws Exception {
        var a = testKit.spawn(SpikeEntity.create("c3", A, ALL), "c3-a-1");
        var probe = testKit.createTestProbe(SpikeEntity.State.class);

        a.tell(new SpikeEntity.Add(42, probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        testKit.stop(a);
        Thread.sleep(500); // allow stop to complete

        // Restart — should recover from journal
        var a2 = testKit.spawn(SpikeEntity.create("c3", A, ALL), "c3-a-2");
        awaitConvergence(a2, probe, s -> s.total() == 42);
    }

    @Test
    void multiple_operations_both_replicas_converge() {
        var a = testKit.spawn(SpikeEntity.create("c4", A, ALL));
        var b = testKit.spawn(SpikeEntity.create("c4", B, ALL));
        var probe = testKit.createTestProbe(SpikeEntity.State.class);

        // Multiple operations on both replicas
        a.tell(new SpikeEntity.Add(1, probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));
        a.tell(new SpikeEntity.Add(2, probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));
        a.tell(new SpikeEntity.PutItem("key", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        b.tell(new SpikeEntity.Add(10, probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));
        b.tell(new SpikeEntity.PutItem("gem", probe.ref()));
        probe.receiveMessage(Duration.ofSeconds(3));

        // Both should converge: total=13, items={key, gem}
        awaitConvergence(a, probe, s ->
            s.total() == 13 && s.items().contains("key") && s.items().contains("gem"));
        awaitConvergence(b, probe, s ->
            s.total() == 13 && s.items().contains("key") && s.items().contains("gem"));
    }

    // ====== Helper ======

    private void awaitConvergence(ActorRef<SpikeEntity.Command> ref,
                                   TestProbe<SpikeEntity.State> probe,
                                   Predicate<SpikeEntity.State> check) {
        SpikeEntity.State lastState = null;
        for (int i = 0; i < 50; i++) {
            ref.tell(new SpikeEntity.GetState(probe.ref()));
            lastState = probe.receiveMessage(Duration.ofSeconds(2));
            if (check.test(lastState)) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        assertThat(check.test(lastState))
            .as("Convergence timeout. State: total=%d, items=%s, concurrent=%d",
                lastState.total(), lastState.items(), lastState.concurrentCount())
            .isTrue();
    }
}

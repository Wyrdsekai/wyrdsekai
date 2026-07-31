package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrdtLayerTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @Test void propagate_entity_add() {
        var layer = testKit.spawn(CrdtLayer.create("node-1"));
        var probe = testKit.createTestProbe(CrdtLayer.MergedState.class);

        layer.tell(new CrdtLayer.PropagateState("room1", "entity_add",
            Map.of("player-1", "Alice")));
        layer.tell(new CrdtLayer.GetMergedState("room1", probe.getRef()));

        var state = probe.receiveMessage();
        assertThat(state.entityIds()).contains("player-1");
    }

    @Test void propagate_entity_remove() {
        var layer = testKit.spawn(CrdtLayer.create("node-1"));
        var probe = testKit.createTestProbe(CrdtLayer.MergedState.class);

        layer.tell(new CrdtLayer.PropagateState("room1", "entity_add",
            Map.of("player-1", "Alice")));
        layer.tell(new CrdtLayer.PropagateState("room1", "entity_remove",
            Map.of("player-1", "")));
        layer.tell(new CrdtLayer.GetMergedState("room1", probe.getRef()));

        var state = probe.receiveMessage();
        assertThat(state.entityIds()).doesNotContain("player-1");
    }

    @Test void propagate_object_add() {
        var layer = testKit.spawn(CrdtLayer.create("node-1"));
        var probe = testKit.createTestProbe(CrdtLayer.MergedState.class);

        layer.tell(new CrdtLayer.PropagateState("room1", "object_add",
            Map.of("sword-1", "Sword")));
        layer.tell(new CrdtLayer.GetMergedState("room1", probe.getRef()));

        var state = probe.receiveMessage();
        assertThat(state.objectIds()).contains("sword-1");
    }

    @Test void propagate_property() {
        var layer = testKit.spawn(CrdtLayer.create("node-1"));
        var probe = testKit.createTestProbe(CrdtLayer.MergedState.class);

        layer.tell(new CrdtLayer.PropagateState("room1", "property",
            Map.of("light", "bright")));
        layer.tell(new CrdtLayer.GetMergedState("room1", probe.getRef()));

        var state = probe.receiveMessage();
        assertThat(state.properties()).containsEntry("light", "bright");
    }

    @Test void receive_from_peer_applies_delta() {
        var layer = testKit.spawn(CrdtLayer.create("node-1"));
        var probe = testKit.createTestProbe(CrdtLayer.MergedState.class);

        layer.tell(new CrdtLayer.ReceiveState("node-2", "room1", "entity_add",
            Map.of("player-2", "Bob"), 10));
        layer.tell(new CrdtLayer.GetMergedState("room1", probe.getRef()));

        var state = probe.receiveMessage();
        assertThat(state.entityIds()).contains("player-2");
    }

    @Test void receive_stale_update_ignored() {
        var layer = testKit.spawn(CrdtLayer.create("node-1"));
        var probe = testKit.createTestProbe(CrdtLayer.MergedState.class);

        // First apply with higher clock
        layer.tell(new CrdtLayer.ReceiveState("node-2", "room1", "entity_add",
            Map.of("player-2", "Bob"), 10));
        // Then apply with lower clock — should be ignored
        layer.tell(new CrdtLayer.ReceiveState("node-2", "room1", "entity_remove",
            Map.of("player-2", ""), 5));

        layer.tell(new CrdtLayer.GetMergedState("room1", probe.getRef()));
        var state = probe.receiveMessage();
        // player-2 should still be present (stale remove ignored)
        assertThat(state.entityIds()).contains("player-2");
    }

    @Test void get_merged_state_for_unknown_room() {
        var layer = testKit.spawn(CrdtLayer.create("node-1"));
        var probe = testKit.createTestProbe(CrdtLayer.MergedState.class);

        layer.tell(new CrdtLayer.GetMergedState("nonexistent", probe.getRef()));

        var state = probe.receiveMessage();
        assertThat(state.entityIds()).isEmpty();
        assertThat(state.objectIds()).isEmpty();
        assertThat(state.properties()).isEmpty();
    }

    @Test void multiple_rooms_independent() {
        var layer = testKit.spawn(CrdtLayer.create("node-1"));
        var probe = testKit.createTestProbe(CrdtLayer.MergedState.class);

        layer.tell(new CrdtLayer.PropagateState("room1", "entity_add",
            Map.of("p1", "Alice")));
        layer.tell(new CrdtLayer.PropagateState("room2", "entity_add",
            Map.of("p2", "Bob")));

        layer.tell(new CrdtLayer.GetMergedState("room1", probe.getRef()));
        assertThat(probe.receiveMessage().entityIds()).containsExactly("p1");

        layer.tell(new CrdtLayer.GetMergedState("room2", probe.getRef()));
        assertThat(probe.receiveMessage().entityIds()).containsExactly("p2");
    }
}

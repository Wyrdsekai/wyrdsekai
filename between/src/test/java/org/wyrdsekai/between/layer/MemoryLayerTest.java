package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryLayerTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @Test void store_and_retrieve() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.RetrieveResult.class);

        layer.tell(new MemoryLayer.Store("agent", "context", "room nexus mood=friendly"));
        layer.tell(new MemoryLayer.Retrieve("agent", "context", probe.getRef()));

        var result = probe.receiveMessage();
        assertThat(result.found()).isTrue();
        assertThat(result.value()).isEqualTo("room nexus mood=friendly");
    }

    @Test void retrieve_missing_returns_not_found() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.RetrieveResult.class);

        layer.tell(new MemoryLayer.Retrieve("agent", "missing", probe.getRef()));

        var result = probe.receiveMessage();
        assertThat(result.found()).isFalse();
        assertThat(result.value()).isNull();
    }

    @Test void retrieve_missing_namespace() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.RetrieveResult.class);

        layer.tell(new MemoryLayer.Retrieve("nonexistent", "key", probe.getRef()));

        var result = probe.receiveMessage();
        assertThat(result.found()).isFalse();
    }

    @Test void delete_removes_entry() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.RetrieveResult.class);

        layer.tell(new MemoryLayer.Store("agent", "key1", "value1"));
        layer.tell(new MemoryLayer.Delete("agent", "key1"));
        layer.tell(new MemoryLayer.Retrieve("agent", "key1", probe.getRef()));

        assertThat(probe.receiveMessage().found()).isFalse();
    }

    @Test void list_namespace() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.NamespaceEntries.class);

        layer.tell(new MemoryLayer.Store("agent", "k1", "v1"));
        layer.tell(new MemoryLayer.Store("agent", "k2", "v2"));
        layer.tell(new MemoryLayer.Store("other", "k3", "v3"));

        layer.tell(new MemoryLayer.ListNamespace("agent", probe.getRef()));
        var entries = probe.receiveMessage();
        assertThat(entries.entries()).hasSize(2);
        assertThat(entries.entries()).containsEntry("k1", "v1");
    }

    @Test void list_empty_namespace() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.NamespaceEntries.class);

        layer.tell(new MemoryLayer.ListNamespace("empty", probe.getRef()));
        assertThat(probe.receiveMessage().entries()).isEmpty();
    }

    @Test void receive_from_peer() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.RetrieveResult.class);

        long futureExpiry = Instant.now().getEpochSecond() + 3600;
        layer.tell(new MemoryLayer.ReceiveEntry("node-2", "shared", "fact1", "sky is blue", futureExpiry));

        layer.tell(new MemoryLayer.Retrieve("shared", "fact1", probe.getRef()));
        var result = probe.receiveMessage();
        assertThat(result.found()).isTrue();
        assertThat(result.value()).isEqualTo("sky is blue");
    }

    @Test void receive_stale_entry_ignored() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.RetrieveResult.class);

        long futureExpiry = Instant.now().getEpochSecond() + 3600;
        long pastExpiry = Instant.now().getEpochSecond() + 1800;

        layer.tell(new MemoryLayer.ReceiveEntry("node-2", "ns", "k", "newer", futureExpiry));
        layer.tell(new MemoryLayer.ReceiveEntry("node-3", "ns", "k", "older", pastExpiry));

        layer.tell(new MemoryLayer.Retrieve("ns", "k", probe.getRef()));
        assertThat(probe.receiveMessage().value()).isEqualTo("newer"); // newer kept
    }

    @Test void expired_entry_not_retrieved() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.RetrieveResult.class);

        // Store with expiry in the past via ReceiveEntry
        long pastExpiry = Instant.now().getEpochSecond() - 10;
        layer.tell(new MemoryLayer.ReceiveEntry("node-2", "ns", "k", "v", pastExpiry));

        layer.tell(new MemoryLayer.Retrieve("ns", "k", probe.getRef()));
        assertThat(probe.receiveMessage().found()).isFalse();
    }

    @Test void store_overwrites_previous() {
        var layer = testKit.spawn(MemoryLayer.create("node-1"));
        var probe = testKit.createTestProbe(MemoryLayer.RetrieveResult.class);

        layer.tell(new MemoryLayer.Store("ns", "k", "old"));
        layer.tell(new MemoryLayer.Store("ns", "k", "new"));

        layer.tell(new MemoryLayer.Retrieve("ns", "k", probe.getRef()));
        assertThat(probe.receiveMessage().value()).isEqualTo("new");
    }
}

package org.wyrdsekai.between.discovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

class KademliaTableTest {

    private KademliaTable table;

    @BeforeEach
    void setup() {
        table = KademliaTable.create("local-node");
    }

    @Test
    void create_generates_sha256_id() {
        assertThat(table.localNodeId()).hasSize(32); // SHA-256 = 32 bytes
    }

    @Test
    void add_node_and_find_closest() {
        var nodeA = makeNode("node-a", "198.51.100.1");
        var nodeB = makeNode("node-b", "198.51.100.2");
        var nodeC = makeNode("node-c", "198.51.100.3");

        table.addNode(nodeA);
        table.addNode(nodeB);
        table.addNode(nodeC);

        assertThat(table.nodeCount()).isEqualTo(3);

        var closest = table.findClosest("target-key", 2);
        assertThat(closest).hasSize(2);
    }

    @Test
    void remove_node() {
        var node = makeNode("node-remove", "192.0.2.1");
        table.addNode(node);
        assertThat(table.nodeCount()).isEqualTo(1);

        table.removeNode(node.nodeId());
        assertThat(table.nodeCount()).isEqualTo(0);
    }

    @Test
    void self_node_not_added() {
        var self = new KademliaTable.NodeInfo(
            table.localNodeId(), "nats://localhost", "http://localhost",
            "zone-1", "127.0.0.1", 4222, Instant.now(), false, false);
        assertThat(table.addNode(self)).isFalse();
    }

    @Test
    void bucket_overflow_evicts_stale() {
        // Fill a bucket beyond K entries
        for (int i = 0; i < KademliaTable.K + 2; i++) {
            var node = makeNode("overflow-" + i, "10.0.0." + (i + 1));
            table.addNode(node);
        }
        // Should have at most K per bucket (might spread across buckets)
        assertThat(table.nodeCount()).isLessThanOrEqualTo(
            KademliaTable.K * table.activeBuckets());
    }

    @Test
    void store_and_retrieve_value() {
        table.store("test-key", "test-value".getBytes(), table.localNodeId(),
            null, Instant.now().plusSeconds(3600));

        var result = table.get("test-key");
        assertThat(result).isPresent();
        assertThat(new String(result.get().value())).isEqualTo("test-value");
    }

    @Test
    void expired_value_not_returned() {
        table.store("expired-key", "old".getBytes(), table.localNodeId(),
            null, Instant.now().minusSeconds(10));

        assertThat(table.get("expired-key")).isEmpty();
    }

    @Test
    void store_zone_and_relay() {
        table.storeZone("zone-1", "nats://host:4222", "http://host:7070",
            table.localNodeId(), null);
        table.storeRelay("nats://relay:4222", true, 500, 42,
            table.localNodeId(), null);

        assertThat(table.allZones()).hasSize(1);
        assertThat(table.allRelays()).hasSize(1);
    }

    @Test
    void xor_distance_is_symmetric() {
        var a = KademliaTable.sha256("aaa");
        var b = KademliaTable.sha256("bbb");
        assertThat(KademliaTable.xorDistanceLong(a, b))
            .isEqualTo(KademliaTable.xorDistanceLong(b, a));
    }

    @Test
    void xor_distance_to_self_is_zero() {
        var a = KademliaTable.sha256("same");
        assertThat(KademliaTable.xorDistanceLong(a, a)).isEqualTo(0);
    }

    @Test
    void ip_colocation_count() {
        table.addNode(makeNode("co-1", "198.51.100.10"));
        table.addNode(makeNode("co-2", "198.51.100.20"));
        table.addNode(makeNode("co-3", "198.51.100.30"));
        table.addNode(makeNode("other", "192.0.2.1"));

        assertThat(table.ipColocationCount("198.51.100.50")).isEqualTo(3);
        assertThat(table.ipColocationCount("192.0.2.99")).isEqualTo(1);
    }

    @Test
    void bep42_ip_binding_roundtrip() {
        var baseId = KademliaTable.sha256("test-node");
        var boundId = KademliaTable.generateIpBoundId("198.51.100.100", baseId);

        // The bound ID should validate against the same IP
        assertThat(KademliaTable.validateIpBinding(boundId, "198.51.100.100")).isTrue();
        // Different IP should not validate
        assertThat(KademliaTable.validateIpBinding(boundId, "192.0.2.1")).isFalse();
    }

    @Test
    void find_closest_returns_sorted_by_distance() {
        for (int i = 0; i < 20; i++) {
            table.addNode(makeNode("sort-" + i, "10.0." + i + ".1"));
        }

        var targetId = KademliaTable.sha256("target");
        var closest = table.findClosest(targetId, 5);
        assertThat(closest).hasSize(5);

        // Verify sorted by XOR distance
        for (int i = 1; i < closest.size(); i++) {
            long distPrev = KademliaTable.xorDistanceLong(closest.get(i - 1).nodeId(), targetId);
            long distCurr = KademliaTable.xorDistanceLong(closest.get(i).nodeId(), targetId);
            assertThat(distPrev).isLessThanOrEqualTo(distCurr);
        }
    }

    private KademliaTable.NodeInfo makeNode(String name, String ip) {
        return new KademliaTable.NodeInfo(
            KademliaTable.sha256(name), "nats://" + ip + ":4222",
            "http://" + ip + ":7070", "zone-" + name, ip, 4222,
            Instant.now(), false, false);
    }
}

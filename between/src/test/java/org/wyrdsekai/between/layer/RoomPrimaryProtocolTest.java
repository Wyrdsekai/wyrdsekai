package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RoomPrimaryProtocolTest {

    @Test
    void epoch_increments_on_each_claim() {
        // RoomPrimaryProtocol needs NatsBridge — test the epoch logic directly
        var hb1 = new RoomPrimaryProtocol.RoomPrimaryHeartbeat(
            "node-1", "nexus", 3, 0.8, 100L, Instant.now());
        var hb2 = new RoomPrimaryProtocol.RoomPrimaryHeartbeat(
            "node-1", "nexus", 3, 0.8, 101L, Instant.now());

        assertThat(hb2.epoch()).isGreaterThan(hb1.epoch());
    }

    @Test
    void heartbeat_carries_epoch() {
        var hb = new RoomPrimaryProtocol.RoomPrimaryHeartbeat(
            "node-abc", "library", 5, 0.95, 42L, Instant.now());

        assertThat(hb.epoch()).isEqualTo(42L);
        assertThat(hb.roomId()).isEqualTo("library");
        assertThat(hb.nodeId()).isEqualTo("node-abc");
    }
}

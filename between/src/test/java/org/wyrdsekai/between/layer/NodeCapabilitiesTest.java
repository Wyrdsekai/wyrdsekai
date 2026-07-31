package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NodeCapabilitiesTest {

    @Test void snapshot_containsBasicSystemInfo() {
        var caps = new NodeCapabilities("test-node");
        var snap = caps.snapshot();
        assertThat(snap.nodeId()).isEqualTo("test-node");
        assertThat(snap.cpuCount()).isGreaterThan(0);
        assertThat(snap.ramTotalMb()).isGreaterThan(0);
        assertThat(snap.nodeState()).isEqualTo("HEALTHY");
        assertThat(snap.timestamp()).isNotNull();
    }

    @Test void snapshot_satisfiesRequirements() {
        var snap = new NodeCapabilities.Snapshot(
            "a", Set.of("inference", "gpu", "internet"), 8, 32768, 16000,
            "Test GPU", 4096, 100000, 80, "llama-server", true,
            List.of(), List.of(), -1, "HEALTHY",
            Instant.now());

        assertThat(snap.satisfiesRequirements(Set.of("inference"))).isTrue();
        assertThat(snap.satisfiesRequirements(Set.of("inference", "gpu"))).isTrue();
        assertThat(snap.satisfiesRequirements(Set.of("soulstore"))).isFalse();
        assertThat(snap.satisfiesRequirements(Set.of())).isTrue();
        assertThat(snap.satisfiesRequirements(null)).isTrue();
    }

    @Test void snapshot_hasCapability() {
        var snap = new NodeCapabilities.Snapshot(
            "a", Set.of("inference", "storage"), 4, 16384, 8000,
            null, 0, 50000, 90, null, false,
            List.of(), List.of(), 85, "HEALTHY",
            Instant.now());

        assertThat(snap.hasCapability("inference")).isTrue();
        assertThat(snap.hasCapability("storage")).isTrue();
        assertThat(snap.hasCapability("gpu")).isFalse();
    }

    @Test void setState_changesState() {
        var caps = new NodeCapabilities("test-node");
        assertThat(caps.getState()).isEqualTo(NodeCapabilities.NodeState.HEALTHY);

        caps.setState(NodeCapabilities.NodeState.MAINTENANCE);
        assertThat(caps.getState()).isEqualTo(NodeCapabilities.NodeState.MAINTENANCE);
        assertThat(caps.snapshot().nodeState()).isEqualTo("MAINTENANCE");
    }

    @Test void companionHosting_tracked() {
        var caps = new NodeCapabilities("test-node");
        caps.addCompanionHosting("companion-wyrd");
        caps.addCompanionHosting("companion-ember");
        assertThat(caps.snapshot().companionHosting()).containsExactly("companion-wyrd", "companion-ember");

        caps.removeCompanionHosting("companion-wyrd");
        assertThat(caps.snapshot().companionHosting()).containsExactly("companion-ember");
    }

    @Test void roomPrimaries_tracked() {
        var caps = new NodeCapabilities("test-node");
        caps.addRoomPrimary("nexus");
        caps.addRoomPrimary("library");
        assertThat(caps.snapshot().roomPrimaries()).contains("nexus", "library");

        caps.removeRoomPrimary("nexus");
        assertThat(caps.snapshot().roomPrimaries()).containsExactly("library");
    }

    @Test void inferenceBackend_setAndReflected() {
        var caps = new NodeCapabilities("test-node");
        caps.setInferenceBackend("llama-server");
        caps.setInferenceModelLoaded(true);
        var snap = caps.snapshot();
        assertThat(snap.inferenceBackend()).isEqualTo("llama-server");
        assertThat(snap.inferenceModelLoaded()).isTrue();
    }

    @Test void gpu_setAndReflected() {
        var caps = new NodeCapabilities("test-node");
        caps.setGpu("RTX 4090", 24576);
        var snap = caps.snapshot();
        assertThat(snap.gpuName()).isEqualTo("RTX 4090");
        assertThat(snap.gpuVramMb()).isEqualTo(24576);
    }
}

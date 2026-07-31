package org.wyrdsekai.core.substrate.training;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function selector tests — no IO, no env. Each test constructs a
 * {@link NodeCapacity} + peers + policy and asserts the strategy variant.
 *
 * <p>The selector decides where training runs based on detected resources;
 * these tests exhaustively cover the decision branches so a regression in
 * one corner (e.g. CPU-only host accidentally selecting LocalSerial) gets
 * caught at compile/test time, not during a 9-hour soak.</p>
 */
class TrainingStrategySelectorTest {

    // ── Capacity factories ──────────────────────────────────────────────

    /** 16GB GPU with 13.5GB held by 9B+4B; 12GB training won't fit parallel
     *  (need 25.5GB) but fits after pause (12GB ≤ 16GB total). home-server config. */
    private static NodeCapacity lainLikeNvidia() {
        return new NodeCapacity(
            /*free*/ 2.0, /*total*/ 16.0,
            /*active*/ 13.5, /*train*/ 12.0,
            /*ram*/ 32.0, /*cpu*/ 16,
            /*nvidia*/ true, /*apple*/ false,
            List.of("RTX 4060 Ti"));
    }

    /** 24GB GPU lightly loaded — parallel fits (free 22 ≥ active 2 + train 12). */
    private static NodeCapacity bigNvidia() {
        return new NodeCapacity(
            22.0, 24.0, 2.0, 12.0,
            64.0, 32, true, false, List.of("RTX 3090"));
    }

    /** Apple Silicon, no NVIDIA — apple GPU path; treat as 12GB total/8GB free. */
    private static NodeCapacity appleSilicon() {
        return new NodeCapacity(
            8.0, 12.0, 4.0, 6.0,
            16.0, 10, false, true, List.of("Apple M2"));
    }

    /** No GPU at all — test-node/mac-node-CPU class. */
    private static NodeCapacity cpuOnly() {
        return new NodeCapacity(
            0.0, 0.0, 0.0, 12.0,
            16.0, 8, false, false, List.of());
    }

    private static PeerCapacity beefyPeer() {
        // nodeId, zone, freeVram, totalVram, hasGpu, latencyMs, trustTier
        return new PeerCapacity("gpu-host", "alpha", 24.0, 24.0, true, 50, 0.95);
    }

    private static PeerCapacity weakPeer() {
        return new PeerCapacity("watch", "alpha", 2.0, 2.0, true, 80, 0.5);
    }

    // ── Policy: DISABLED ────────────────────────────────────────────────

    @Test
    void disabled_policy_always_skips_even_with_capacity() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(beefyPeer()),
            UserTrainingPolicy.DISABLED, List.of(), true);
        assertThat(s).isInstanceOf(TrainingStrategy.Skip.class);
        assertThat(((TrainingStrategy.Skip) s).reason()).contains("DISABLED");
    }

    // ── AUTO / PREFER_LOCAL: parallel preferred ─────────────────────────

    @Test
    void big_gpu_chooses_local_parallel_under_auto() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(),
            UserTrainingPolicy.AUTO, List.of("wyrdsekai-llama"), false);
        assertThat(s).isInstanceOf(TrainingStrategy.LocalParallel.class);
    }

    @Test
    void big_gpu_chooses_local_parallel_under_prefer_local() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(),
            UserTrainingPolicy.PREFER_LOCAL, List.of(), false);
        assertThat(s).isInstanceOf(TrainingStrategy.LocalParallel.class);
    }

    // ── AUTO: falls back to serial when parallel doesn't fit ────────────

    @Test
    void homeServer_chooses_local_serial_with_pause_list() {
        var s = TrainingStrategySelector.choose(
            lainLikeNvidia(), List.of(),
            UserTrainingPolicy.AUTO,
            List.of("wyrdsekai-llama-voice", "wyrdsekai-llama"),
            false);
        assertThat(s).isInstanceOf(TrainingStrategy.LocalSerial.class);
        var ls = (TrainingStrategy.LocalSerial) s;
        // Selector echoes back active containers as the pause list.
        assertThat(ls.containersToPause())
            .containsExactly("wyrdsekai-llama-voice", "wyrdsekai-llama");
    }

    @Test
    void apple_silicon_chooses_local_serial_when_serial_fits() {
        var s = TrainingStrategySelector.choose(
            appleSilicon(), List.of(),
            UserTrainingPolicy.AUTO, List.of("wyrdsekai-llama"), false);
        // free(8)+active(4)=12 ≥ train(6), parallel needs free(8)≥active(4)+train(6)=10 → no.
        assertThat(s).isInstanceOf(TrainingStrategy.LocalSerial.class);
    }

    // ── No local fit: peer / cloud / skip ───────────────────────────────

    @Test
    void cpu_only_with_peer_chooses_peer_delegated() {
        var s = TrainingStrategySelector.choose(
            cpuOnly(), List.of(beefyPeer()),
            UserTrainingPolicy.AUTO, List.of(), false);
        assertThat(s).isInstanceOf(TrainingStrategy.PeerDelegated.class);
        var pd = (TrainingStrategy.PeerDelegated) s;
        assertThat(pd.peerNodeId()).isEqualTo("gpu-host");
        assertThat(pd.peerZone()).isEqualTo("alpha");
    }

    @Test
    void cpu_only_with_too_weak_peer_falls_through_to_cloud() {
        var s = TrainingStrategySelector.choose(
            cpuOnly(), List.of(weakPeer()),
            UserTrainingPolicy.AUTO, List.of(), true);
        // weakPeer has 2GB GPU, can't fit 12GB training → skipped, cloud chosen.
        assertThat(s).isInstanceOf(TrainingStrategy.CloudDistilled.class);
    }

    @Test
    void cpu_only_no_peer_no_cloud_skips_with_diagnostic() {
        var s = TrainingStrategySelector.choose(
            cpuOnly(), List.of(),
            UserTrainingPolicy.AUTO, List.of(), false);
        assertThat(s).isInstanceOf(TrainingStrategy.Skip.class);
        var skip = (TrainingStrategy.Skip) s;
        // Diagnostic includes enough context to debug from a log line alone.
        assertThat(skip.reason()).contains("no viable strategy")
            .contains("gpu=no").contains("peers=0").contains("cloud=false");
    }

    @Test
    void cpu_only_no_peer_with_cloud_chooses_cloud_distilled() {
        var s = TrainingStrategySelector.choose(
            cpuOnly(), List.of(),
            UserTrainingPolicy.AUTO, List.of(), true);
        assertThat(s).isInstanceOf(TrainingStrategy.CloudDistilled.class);
    }

    // ── PREFER_PEER ─────────────────────────────────────────────────────

    @Test
    void prefer_peer_uses_peer_even_when_local_could_fit() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(beefyPeer()),
            UserTrainingPolicy.PREFER_PEER, List.of(), false);
        assertThat(s).isInstanceOf(TrainingStrategy.PeerDelegated.class);
    }

    @Test
    void prefer_peer_falls_back_to_local_when_no_peer() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(),
            UserTrainingPolicy.PREFER_PEER, List.of(), false);
        // No peer → fall through to local strategies.
        assertThat(s).isInstanceOf(TrainingStrategy.LocalParallel.class);
    }

    // ── LOCAL_FORBIDDEN ─────────────────────────────────────────────────

    @Test
    void local_forbidden_with_peer_chooses_peer() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(beefyPeer()),
            UserTrainingPolicy.LOCAL_FORBIDDEN, List.of(), true);
        assertThat(s).isInstanceOf(TrainingStrategy.PeerDelegated.class);
    }

    @Test
    void local_forbidden_no_peer_with_cloud_chooses_cloud() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(),
            UserTrainingPolicy.LOCAL_FORBIDDEN, List.of(), true);
        assertThat(s).isInstanceOf(TrainingStrategy.CloudDistilled.class);
    }

    @Test
    void local_forbidden_no_peer_no_cloud_skips() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(),
            UserTrainingPolicy.LOCAL_FORBIDDEN, List.of(), false);
        assertThat(s).isInstanceOf(TrainingStrategy.Skip.class);
        assertThat(((TrainingStrategy.Skip) s).reason())
            .contains("LOCAL_FORBIDDEN");
    }

    // ── PREFER_CLOUD ────────────────────────────────────────────────────

    @Test
    void prefer_cloud_uses_cloud_when_available_even_with_local_fit() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(),
            UserTrainingPolicy.PREFER_CLOUD, List.of(), true);
        assertThat(s).isInstanceOf(TrainingStrategy.CloudDistilled.class);
    }

    @Test
    void prefer_cloud_falls_through_to_local_when_no_cloud() {
        var s = TrainingStrategySelector.choose(
            bigNvidia(), List.of(),
            UserTrainingPolicy.PREFER_CLOUD, List.of(), false);
        assertThat(s).isInstanceOf(TrainingStrategy.LocalParallel.class);
    }

    // ── Peer scoring: best peer wins ────────────────────────────────────

    @Test
    void best_peer_wins_when_multiple_can_fit() {
        var weak   = new PeerCapacity("a", "alpha", 16.0, 16.0, true, 200, 0.6);
        var strong = new PeerCapacity("b", "alpha", 24.0, 24.0, true, 30,  0.95);
        var s = TrainingStrategySelector.choose(
            cpuOnly(), List.of(weak, strong),
            UserTrainingPolicy.AUTO, List.of(), false);
        assertThat(s).isInstanceOf(TrainingStrategy.PeerDelegated.class);
        assertThat(((TrainingStrategy.PeerDelegated) s).peerNodeId()).isEqualTo("b");
    }

    // ── Null-safety ─────────────────────────────────────────────────────

    @Test
    void null_peers_treated_as_empty() {
        var s = TrainingStrategySelector.choose(
            cpuOnly(), null,
            UserTrainingPolicy.AUTO, List.of(), false);
        assertThat(s).isInstanceOf(TrainingStrategy.Skip.class);
    }

    @Test
    void null_active_containers_serial_pause_list_empty() {
        var s = TrainingStrategySelector.choose(
            lainLikeNvidia(), List.of(),
            UserTrainingPolicy.AUTO, null, false);
        assertThat(s).isInstanceOf(TrainingStrategy.LocalSerial.class);
        assertThat(((TrainingStrategy.LocalSerial) s).containersToPause()).isEmpty();
    }

    // ── Strategy.label() round-trips ────────────────────────────────────

    @Test
    void all_strategy_variants_have_distinct_labels() {
        var labels = List.of(
            new TrainingStrategy.LocalParallel().label(),
            new TrainingStrategy.LocalSerial(List.of()).label(),
            new TrainingStrategy.PeerDelegated("x", "alpha").label(),
            new TrainingStrategy.CloudDistilled("anthropic").label(),
            new TrainingStrategy.Skip("test").label());
        assertThat(labels).doesNotHaveDuplicates();
    }
}

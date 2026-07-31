package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ZoneEscalationResolver.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneEscalationResolverTest {

    static ZoneNode gpuNode(String id, double latency) {
        return new ZoneNode(id, true, latency, Map.of(
            "inference_model", "qwen2.5:14b",
            "gpu_count", "1",
            "gpu_free_vram_mb", "8192"
        ));
    }

    static ZoneNode cpuNode(String id, double latency) {
        return new ZoneNode(id, true, latency, Map.of(
            "inference_model", "qwen2.5:7b",
            "gpu_count", "0"
        ));
    }

    static ZoneNode bareNode(String id) {
        return new ZoneNode(id, true, 5.0, Map.of());
    }

    static ZoneNode disconnected(String id) {
        return new ZoneNode(id, false, 0, Map.of(
            "inference_model", "qwen2.5:7b"
        ));
    }

    // --- resolve() ---

    @Test void null_nodes_returns_empty() {
        assertThat(ZoneEscalationResolver.resolve(null, "local", false)).isEmpty();
    }

    @Test void empty_nodes_returns_empty() {
        assertThat(ZoneEscalationResolver.resolve(List.of(), "local", false)).isEmpty();
    }

    @Test void local_node_excluded() {
        var options = ZoneEscalationResolver.resolve(
            List.of(gpuNode("local", 1.0)), "local", false);
        assertThat(options).isEmpty();
    }

    @Test void disconnected_node_excluded() {
        var options = ZoneEscalationResolver.resolve(
            List.of(disconnected("remote")), "local", false);
        assertThat(options).isEmpty();
    }

    @Test void gpu_node_provides_all_four_tiers() {
        var options = ZoneEscalationResolver.resolve(
            List.of(gpuNode("desktop", 2.0)), "phone", false);
        assertThat(options).hasSize(4);
        var tiers = options.stream().map(EscalationOption::tier).toList();
        assertThat(tiers).containsExactlyInAnyOrder(
            EscalationTier.RELAY_INFERENCE,
            EscalationTier.VISIT_WORKSHOP,
            EscalationTier.ASK_PEER,
            EscalationTier.BUD
        );
    }

    @Test void cpu_node_provides_three_tiers_no_bud() {
        var options = ZoneEscalationResolver.resolve(
            List.of(cpuNode("laptop", 5.0)), "phone", false);
        assertThat(options).hasSize(3);
        var tiers = options.stream().map(EscalationOption::tier).toList();
        assertThat(tiers).doesNotContain(EscalationTier.BUD);
    }

    @Test void bare_node_provides_no_options() {
        var options = ZoneEscalationResolver.resolve(
            List.of(bareNode("nas")), "phone", false);
        assertThat(options).isEmpty();
    }

    @Test void needs_gpu_filters_cpu_only_nodes() {
        var options = ZoneEscalationResolver.resolve(
            List.of(cpuNode("laptop", 5.0)), "phone", true);
        assertThat(options).isEmpty();
    }

    @Test void needs_gpu_keeps_gpu_nodes() {
        var options = ZoneEscalationResolver.resolve(
            List.of(gpuNode("desktop", 2.0)), "phone", true);
        assertThat(options).isNotEmpty();
    }

    @Test void sorted_by_energy_cost() {
        var options = ZoneEscalationResolver.resolve(
            List.of(gpuNode("desktop", 2.0)), "phone", false);
        for (int i = 1; i < options.size(); i++) {
            assertThat(options.get(i).estimatedEnergyCost())
                .isGreaterThanOrEqualTo(options.get(i - 1).estimatedEnergyCost());
        }
    }

    @Test void multiple_nodes_generate_options_for_each() {
        var options = ZoneEscalationResolver.resolve(
            List.of(gpuNode("desktop", 2.0), cpuNode("laptop", 5.0)),
            "phone", false);
        // desktop: 4 tiers, laptop: 3 tiers
        assertThat(options).hasSize(7);
    }

    @Test void relay_inference_is_cheapest() {
        var options = ZoneEscalationResolver.resolve(
            List.of(gpuNode("desktop", 2.0)), "phone", false);
        assertThat(options.getFirst().tier()).isEqualTo(EscalationTier.RELAY_INFERENCE);
        assertThat(options.getFirst().estimatedEnergyCost()).isEqualTo(0.08);
    }

    // --- buildZoneContext() ---

    @Test void buildZoneContext_null_returns_null() {
        assertThat(ZoneEscalationResolver.buildZoneContext(null, "local")).isNull();
    }

    @Test void buildZoneContext_empty_returns_null() {
        assertThat(ZoneEscalationResolver.buildZoneContext(List.of(), "local")).isNull();
    }

    @Test void buildZoneContext_only_local_returns_null() {
        assertThat(ZoneEscalationResolver.buildZoneContext(
            List.of(gpuNode("local", 1.0)), "local")).isNull();
    }

    @Test void buildZoneContext_formats_peers() {
        var ctx = ZoneEscalationResolver.buildZoneContext(
            List.of(gpuNode("desktop", 2.5), cpuNode("laptop", 8.0)),
            "phone");
        assertThat(ctx).contains("Household Zone (2 peers)");
        assertThat(ctx).contains("desktop: qwen2.5:14b [GPU 8192MB free]");
        assertThat(ctx).contains("laptop: qwen2.5:7b");
        // laptop line should NOT contain GPU
        var lines = ctx.split("\n");
        for (var line : lines) {
            if (line.contains("laptop")) {
                assertThat(line).doesNotContain("[GPU");
            }
        }
    }

    @Test void buildZoneContext_excludes_disconnected() {
        var ctx = ZoneEscalationResolver.buildZoneContext(
            List.of(gpuNode("desktop", 2.0), disconnected("offline-box")),
            "phone");
        assertThat(ctx).contains("1 peers");
        assertThat(ctx).doesNotContain("offline-box");
    }
}

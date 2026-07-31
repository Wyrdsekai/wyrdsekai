package org.wyrdsekai.between.traversal;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryMapperTest {

    private final TelemetryMapper mapper = new TelemetryMapper();

    @Test void low_latency_swift() {
        assertThat(mapper.describeLatency(2.0)).contains("swift");
    }

    @Test void medium_latency_steady() {
        assertThat(mapper.describeLatency(35.0)).contains("steady");
    }

    @Test void high_latency_distant() {
        assertThat(mapper.describeLatency(200.0)).contains("distant");
    }

    @Test void very_high_latency() {
        assertThat(mapper.describeLatency(500.0)).contains("far-flung");
    }

    @Test void low_jitter_calm() {
        assertThat(mapper.describeJitter(0.5)).contains("calm");
    }

    @Test void medium_jitter_shimmer() {
        assertThat(mapper.describeJitter(3.0)).contains("shimmer");
    }

    @Test void high_jitter_flickering() {
        assertThat(mapper.describeJitter(10.0)).contains("flickering");
    }

    @Test void very_high_jitter_churning() {
        assertThat(mapper.describeJitter(50.0)).contains("churning");
    }

    @Test void high_bandwidth_mighty() {
        assertThat(mapper.describeBandwidth(2_000_000_000)).contains("mighty");
    }

    @Test void low_bandwidth_narrow() {
        assertThat(mapper.describeBandwidth(5_000_000)).contains("narrow");
    }

    @Test void single_hop_direct() {
        assertThat(mapper.describeHops(1)).contains("direct");
    }

    @Test void many_hops_long() {
        assertThat(mapper.describeHops(10)).contains("long");
    }

    @Test void generate_narrative_has_departure_and_arrival() {
        var ts = new BetweenTraversal.TelemetrySnapshot(50, 5, 100_000_000, 3, Instant.now());
        var narrative = mapper.generateNarrative(ts, "alpha", "beta");

        assertThat(narrative).hasSizeGreaterThanOrEqualTo(3);
        assertThat(narrative.getFirst()).contains("alpha");
        assertThat(narrative.getLast()).contains("beta");
    }

    @Test void summarize_mentions_both_zones() {
        var ts = new BetweenTraversal.TelemetrySnapshot(50, 5, 0, 1, Instant.now());
        var summary = mapper.summarize(ts, "alpha", "beta");
        assertThat(summary).contains("alpha").contains("beta");
    }
}

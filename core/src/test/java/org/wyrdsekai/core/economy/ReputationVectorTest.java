package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReputationVectorTest {

    @Test void initial_has_neutral_scores() {
        var rep = ReputationVector.initial("alice");
        assertThat(rep.entityId()).isEqualTo("alice");
        assertThat(rep.uptime()).isEqualTo(0.5);
        assertThat(rep.quality()).isEqualTo(0.5);
        assertThat(rep.contribution()).isEqualTo(0.0);
        assertThat(rep.consistency()).isEqualTo(0.5);
    }

    @Test void of_calculates_weighted_composite() {
        var rep = ReputationVector.of("alice", 1.0, 1.0, 1.0, 1.0);
        assertThat(rep.composite()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test void of_clamps_values_to_zero_one() {
        var rep = ReputationVector.of("alice", 1.5, -0.5, 2.0, -1.0);
        assertThat(rep.uptime()).isEqualTo(1.0);
        assertThat(rep.quality()).isEqualTo(0.0);
        assertThat(rep.contribution()).isEqualTo(1.0);
        assertThat(rep.consistency()).isEqualTo(0.0);
    }

    @Test void tier_exemplary() {
        var rep = ReputationVector.of("alice", 1.0, 1.0, 1.0, 1.0);
        assertThat(rep.tier()).isEqualTo("exemplary");
    }

    @Test void tier_trusted() {
        var rep = ReputationVector.of("alice", 0.8, 0.7, 0.6, 0.7);
        assertThat(rep.tier()).isEqualTo("trusted");
    }

    @Test void tier_newcomer() {
        var rep = ReputationVector.of("alice", 0.3, 0.2, 0.1, 0.3);
        assertThat(rep.tier()).isEqualTo("newcomer");
    }

    @Test void describe_contains_all_dimensions() {
        var rep = ReputationVector.of("alice", 0.8, 0.7, 0.6, 0.5);
        var desc = rep.describe();
        assertThat(desc).contains("alice");
        assertThat(desc).contains("uptime");
        assertThat(desc).contains("quality");
        assertThat(desc).contains("contribution");
        assertThat(desc).contains("consistency");
    }
}

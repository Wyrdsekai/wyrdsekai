package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DriveStateTest {

    @Test void initial_all_zero() {
        var d = DriveState.initial();
        assertThat(d.curiosity()).isEqualTo(0.0);
        assertThat(d.care()).isEqualTo(0.0);
        assertThat(d.social()).isEqualTo(0.0);
        assertThat(d.achievement()).isEqualTo(0.0);
        assertThat(d.alertness()).isEqualTo(0.0);
    }

    @Test void tick_accumulates_passively() {
        var d = DriveState.initial();
        for (int i = 0; i < 1000; i++) {
            d = d.tick();
        }
        // After 1000 ticks (seconds), drives should have accumulated
        assertThat(d.curiosity()).isGreaterThan(0.2);
        assertThat(d.social()).isGreaterThan(0.3);
        assertThat(d.care()).isGreaterThan(0.1);
    }

    @Test void tick_clamps_to_one() {
        var d = DriveState.initial();
        for (int i = 0; i < 100_000; i++) {
            d = d.tick();
        }
        assertThat(d.curiosity()).isLessThanOrEqualTo(1.0);
        assertThat(d.social()).isLessThanOrEqualTo(1.0);
    }

    @Test void spike_increases_drive() {
        var d = DriveState.initial();
        d = d.spikeAlertness(0.5);
        assertThat(d.alertness()).isEqualTo(0.5);
        assertThat(d.curiosity()).isEqualTo(0.0); // others unchanged
    }

    @Test void spike_clamps() {
        var d = DriveState.initial().spikeAlertness(0.8);
        d = d.spikeAlertness(0.5);
        assertThat(d.alertness()).isEqualTo(1.0);
    }

    @Test void relieve_resets_to_zero() {
        var d = DriveState.initial().spikeAlertness(0.7).spikeCuriosity(0.5);
        d = d.relieveAlertness();
        assertThat(d.alertness()).isEqualTo(0.0);
        assertThat(d.curiosity()).isEqualTo(0.5); // others preserved
    }

    @Test void peak_returns_highest() {
        var d = DriveState.initial()
            .spikeCuriosity(0.3)    // → seeking
            .spikeAlertness(0.8)    // → vigilance
            .spikeCare(0.1);
        var peak = d.peak();
        assertThat(peak.name()).isEqualTo("vigilance");  // new name
        assertThat(peak.pressure()).isEqualTo(0.8);
    }

    @Test void anyAbove_threshold() {
        var d = DriveState.initial().spikeAlertness(0.5);
        assertThat(d.anyAbove(0.3)).isTrue();
        assertThat(d.anyAbove(0.6)).isFalse();
    }

    // ── New 8-drive tests ────────────────────────────────────────────────

    @Test void eight_drives_toArray_roundTrip() {
        // Phase 1A: DriveState canonical width is now 10 (added startle, surprise).
        // The 8-arg back-compat constructor still compiles; new fields default to 0.0.
        var d = new DriveState(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8);
        var arr = d.toArray();
        assertThat(arr).hasSize(DriveConfig.DRIVE_COUNT);
        // First 8 entries match the constructor args; last 2 (startle, surprise) default to 0.0.
        assertThat(arr[8]).isEqualTo(0.0);
        assertThat(arr[9]).isEqualTo(0.0);
        var d2 = DriveState.fromArray(arr);
        assertThat(d2).isEqualTo(d);
    }

    @Test void spike_grief() {
        var d = DriveState.initial().spikeGrief(0.4);
        assertThat(d.grief()).isEqualTo(0.4);
        assertThat(d.seeking()).isEqualTo(0.0);
    }

    @Test void spike_frustration() {
        var d = DriveState.initial().spikeFrustration(0.6);
        assertThat(d.frustration()).isEqualTo(0.6);
    }

    @Test void spike_creativity() {
        var d = DriveState.initial().spikeCreativity(0.3);
        assertThat(d.creativity()).isEqualTo(0.3);
    }

    @Test void spike_play() {
        var d = DriveState.initial().spikePlay(0.5);
        assertThat(d.play()).isEqualTo(0.5);
    }

    @Test void spike_affiliation() {
        var d = DriveState.initial().spikeAffiliation(0.7);
        assertThat(d.affiliation()).isEqualTo(0.7);
    }

    @Test void spike_by_index() {
        var d = DriveState.initial().spike(DriveConfig.CREATIVITY, 0.3);
        assertThat(d.creativity()).isEqualTo(0.3);
        assertThat(d.get(DriveConfig.CREATIVITY)).isEqualTo(0.3);
    }

    @Test void relieve_seeking_has_floor() {
        var d = DriveState.initial().spikeSeeking(0.8);
        var relieved = d.relieveSeeking();
        assertThat(relieved.seeking()).isEqualTo(0.05); // floor not zero
    }

    @Test void relieve_care_has_floor() {
        var d = DriveState.initial().spikeCare(0.8);
        var relieved = d.relieveCare();
        assertThat(relieved.care()).isEqualTo(0.1); // floor not zero
    }

    @Test void legacy_spike_maps_to_new() {
        var d = DriveState.initial();
        assertThat(d.spikeCuriosity(0.3).seeking()).isEqualTo(0.3);
        assertThat(d.spikeSocial(0.4).affiliation()).isEqualTo(0.4);
        assertThat(d.spikeAchievement(0.2).seeking()).isEqualTo(0.2);
        assertThat(d.spikeAlertness(0.5).vigilance()).isEqualTo(0.5);
    }

    @Test void legacy_relieve_maps_to_new() {
        var d = new DriveState(0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8);
        assertThat(d.relieveCuriosity().seeking()).isEqualTo(0.05);
        assertThat(d.relieveSocial().affiliation()).isEqualTo(0.05);
        assertThat(d.relieveAchievement().seeking()).isEqualTo(0.05);
        assertThat(d.relieveAlertness().vigilance()).isEqualTo(0.0);
    }

    @Test void peak_with_new_drives() {
        var d = DriveState.initial().spikeGrief(0.9).spikePlay(0.3);
        var peak = d.peak();
        assertThat(peak.name()).isEqualTo("grief");
        assertThat(peak.pressure()).isEqualTo(0.9);
    }
}

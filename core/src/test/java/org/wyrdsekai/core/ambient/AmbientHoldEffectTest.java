package org.wyrdsekai.core.ambient;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.embodiment.AmbientPhase;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AmbientHoldEffect} — the §15.1 tank-coupling
 * pure function (mirror of {@code PostureHoldEffect}).
 */
class AmbientHoldEffectTest {

    @Test
    void tankDeltasNonEmptyForFoundationRoomAndPhase() {
        var deltas = AmbientHoldEffect.tankDeltas("library", AmbientPhase.NIGHT);
        assertThat(deltas).isNotEmpty();
        assertThat(deltas).containsKey("equanimity");
    }

    @Test
    void tankDeltasNullPhaseReturnsEmpty() {
        var deltas = AmbientHoldEffect.tankDeltas("library", null);
        assertThat(deltas).isEmpty();
    }

    @Test
    void affinityScalesDeltas() {
        var noAffinity = AmbientHoldEffect.tankDeltas("library", AmbientPhase.NIGHT, null);
        var doubled = AmbientHoldEffect.tankDeltas("library", AmbientPhase.NIGHT,
            Map.of("library", 2.0));
        var halved = AmbientHoldEffect.tankDeltas("library", AmbientPhase.NIGHT,
            Map.of("library", 0.5));
        assertThat(doubled.get("equanimity"))
            .isEqualTo(noAffinity.get("equanimity") * 2.0);
        assertThat(halved.get("equanimity"))
            .isEqualTo(noAffinity.get("equanimity") * 0.5);
    }

    @Test
    void negativeAffinityFlipsSign() {
        // A reader who finds the library oppressive — equanimity drifts DOWN there.
        var deltas = AmbientHoldEffect.tankDeltas("library", AmbientPhase.NIGHT,
            Map.of("library", -1.0));
        assertThat(deltas.get("equanimity")).isLessThan(0.0);
    }

    @Test
    void affinityCascadesToCanonicalKind() {
        // A provisioner Study (study-xxx) inherits its affinity from "study" if
        // no exact match is found.
        var deltas = AmbientHoldEffect.tankDeltas("study-companion-7", AmbientPhase.NIGHT,
            Map.of("study", 3.0));
        var defaultDeltas = AmbientHoldEffect.tankDeltas("study-companion-7",
            AmbientPhase.NIGHT, null);
        assertThat(deltas.get("equanimity"))
            .isEqualTo(defaultDeltas.get("equanimity") * 3.0);
    }

    @Test
    void exactRoomIdAffinityWinsOverKindClass() {
        // Both a per-room and a kind-level affinity exist; per-room wins.
        var deltas = AmbientHoldEffect.tankDeltas("study-companion-7", AmbientPhase.NIGHT,
            Map.of(
                "study-companion-7", 5.0,
                "study", 1.0
            ));
        var defaultDeltas = AmbientHoldEffect.tankDeltas("study-companion-7",
            AmbientPhase.NIGHT, null);
        assertThat(deltas.get("equanimity"))
            .isEqualTo(defaultDeltas.get("equanimity") * 5.0);
    }

    @Test
    void resolveAffinityDefaultsTo1OnNullOrEmpty() {
        assertThat(AmbientHoldEffect.resolveAffinity("library", null)).isEqualTo(1.0);
        assertThat(AmbientHoldEffect.resolveAffinity("library", Map.of())).isEqualTo(1.0);
        assertThat(AmbientHoldEffect.resolveAffinity(null, Map.of("library", 5.0))).isEqualTo(1.0);
    }

    @Test
    void tankDeltasMagnitudeIsSmall() {
        // §15.1: same order of magnitude as posture imprints (~0.005-0.020/tick).
        // Sanity check: no single tank delta should ever exceed 0.1/tick.
        for (var phase : AmbientPhase.values()) {
            for (var roomId : AmbientRenderer.foundationTones().keySet()) {
                var deltas = AmbientHoldEffect.tankDeltas(roomId, phase);
                for (var entry : deltas.entrySet()) {
                    assertThat(Math.abs(entry.getValue()))
                        .as("delta for %s/%s/%s is too large", roomId, phase, entry.getKey())
                        .isLessThan(0.1);
                }
            }
        }
    }
}

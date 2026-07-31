package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ForgeOutcomeMarker contract.
 *
 * <p>The marker is the data shape V6+ training-corpus generators read out
 * of DEXTERITY fragments. Its invariants matter for the
 * curate-optimization-separately requirement of §6.5.</p>
 */
class ForgeOutcomeMarkerTest {

    @Test void ofPass_defaults_are_pass_pass_pass() {
        var m = ForgeOutcomeMarker.ofPass(ForgeOutcomeMarker.TaskShape.UPDATE_EXTEND);
        assertThat(m.compile()).isEqualTo(ForgeOutcomeMarker.Outcome.PASS);
        assertThat(m.tests()).isEqualTo(ForgeOutcomeMarker.Outcome.PASS);
        assertThat(m.smoke()).isEqualTo(ForgeOutcomeMarker.Outcome.PASS);
        assertThat(m.bondholderSignal()).isEqualTo(ForgeOutcomeMarker.BondholderSignal.NONE);
        assertThat(m.perfDelta()).isNull();
        assertThat(m.hasPerfDelta()).isFalse();
        assertThat(m.allGatesPassed()).isTrue();
    }

    @Test void ofOptimization_carries_perf_delta() {
        var perf = Map.of("latency_p50", -34.0, "memory_peak", -12.0);
        var m = ForgeOutcomeMarker.ofOptimization(perf,
            ForgeOutcomeMarker.BondholderSignal.ACCEPTED);
        assertThat(m.taskShape()).isEqualTo(ForgeOutcomeMarker.TaskShape.OPTIMIZATION);
        assertThat(m.hasPerfDelta()).isTrue();
        assertThat(m.perfDelta()).hasSize(2);
        assertThat(m.perfDelta().get("latency_p50")).isEqualTo(-34.0);
        assertThat(m.bondholderSignal())
            .isEqualTo(ForgeOutcomeMarker.BondholderSignal.ACCEPTED);
    }

    @Test void perf_delta_map_is_unmodifiable() {
        var mutable = new LinkedHashMap<String, Double>();
        mutable.put("latency_p50", -10.0);
        var m = ForgeOutcomeMarker.ofOptimization(mutable,
            ForgeOutcomeMarker.BondholderSignal.NONE);
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> m.perfDelta().put("intruder", 99.0))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void withPerfMetric_appends_and_overwrites() {
        var m = ForgeOutcomeMarker.ofPass(ForgeOutcomeMarker.TaskShape.OPTIMIZATION);
        var m2 = m.withPerfMetric("latency_p50", -34.0);
        var m3 = m2.withPerfMetric("memory_peak", -12.0);
        var m4 = m3.withPerfMetric("latency_p50", -40.0); // overwrite

        assertThat(m.perfDelta()).isNull();
        assertThat(m2.perfDelta()).containsEntry("latency_p50", -34.0);
        assertThat(m3.perfDelta()).hasSize(2);
        assertThat(m4.perfDelta()).hasSize(2);
        assertThat(m4.perfDelta().get("latency_p50")).isEqualTo(-40.0);
    }

    @Test void withBondholderSignal_replaces() {
        var m = ForgeOutcomeMarker.ofPass(ForgeOutcomeMarker.TaskShape.REFACTOR);
        var accepted = m.withBondholderSignal(
            ForgeOutcomeMarker.BondholderSignal.ACCEPTED);
        assertThat(m.bondholderSignal()).isEqualTo(ForgeOutcomeMarker.BondholderSignal.NONE);
        assertThat(accepted.bondholderSignal())
            .isEqualTo(ForgeOutcomeMarker.BondholderSignal.ACCEPTED);
    }

    @Test void allGatesPassed_true_when_any_passed_others_skipped() {
        var m = new ForgeOutcomeMarker(
            ForgeOutcomeMarker.TaskShape.UPDATE_EXTEND,
            ForgeOutcomeMarker.Outcome.PASS,
            ForgeOutcomeMarker.Outcome.SKIPPED,
            ForgeOutcomeMarker.Outcome.SKIPPED,
            null, ForgeOutcomeMarker.BondholderSignal.NONE);
        assertThat(m.allGatesPassed()).isTrue();
    }

    @Test void allGatesPassed_false_when_any_failed() {
        var m = new ForgeOutcomeMarker(
            ForgeOutcomeMarker.TaskShape.REFACTOR,
            ForgeOutcomeMarker.Outcome.PASS,
            ForgeOutcomeMarker.Outcome.FAIL,
            ForgeOutcomeMarker.Outcome.PASS,
            null, ForgeOutcomeMarker.BondholderSignal.NONE);
        assertThat(m.allGatesPassed()).isFalse();
    }

    @Test void allGatesPassed_false_when_all_skipped() {
        // Edge: marker with everything skipped means no real signal — must
        // not be treated as "passed" for V6+ corpus inclusion.
        var m = new ForgeOutcomeMarker(
            ForgeOutcomeMarker.TaskShape.UPDATE_EXTEND,
            ForgeOutcomeMarker.Outcome.SKIPPED,
            ForgeOutcomeMarker.Outcome.SKIPPED,
            ForgeOutcomeMarker.Outcome.SKIPPED,
            null, ForgeOutcomeMarker.BondholderSignal.NONE);
        assertThat(m.allGatesPassed()).isFalse();
    }

    @Test void json_round_trip_preserves_marker() {
        var m = ForgeOutcomeMarker.ofOptimization(
            Map.of("latency_p50", -34.0, "memory_peak", -12.0),
            ForgeOutcomeMarker.BondholderSignal.ACCEPTED);
        var json = m.toJson();
        assertThat(json).contains("OPTIMIZATION").contains("latency_p50");

        var parsed = ForgeOutcomeMarker.fromJson(json);
        assertThat(parsed).isNotNull();
        assertThat(parsed.taskShape()).isEqualTo(ForgeOutcomeMarker.TaskShape.OPTIMIZATION);
        assertThat(parsed.perfDelta()).hasSize(2);
        assertThat(parsed.bondholderSignal())
            .isEqualTo(ForgeOutcomeMarker.BondholderSignal.ACCEPTED);
    }

    @Test void fromJson_handles_null_and_blank_gracefully() {
        assertThat(ForgeOutcomeMarker.fromJson(null)).isNull();
        assertThat(ForgeOutcomeMarker.fromJson("")).isNull();
        assertThat(ForgeOutcomeMarker.fromJson("not valid json {{{ ")).isNull();
    }

    @Test void defaults_fill_in_null_canonical_fields() {
        var m = new ForgeOutcomeMarker(null, null, null, null, null, null);
        assertThat(m.taskShape()).isEqualTo(ForgeOutcomeMarker.TaskShape.UPDATE_EXTEND);
        assertThat(m.compile()).isEqualTo(ForgeOutcomeMarker.Outcome.SKIPPED);
        assertThat(m.bondholderSignal()).isEqualTo(ForgeOutcomeMarker.BondholderSignal.NONE);
    }

    @Test void dexterity_fragment_carries_marker_via_text_field() {
        // The §6.5 + §17.7 wire convention: DEXTERITY fragment's text field
        // can carry a JSON-serialized marker so V6+ corpus generators don't
        // need a schema migration. Round-trip via SoulFragment.
        var marker = ForgeOutcomeMarker.ofOptimization(
            Map.of("latency_p50", -34.0), ForgeOutcomeMarker.BondholderSignal.ACCEPTED);
        var f = SoulFragment.dexterity("opt-1", "procedural",
            "Flash-attention swap", marker.toJson());
        assertThat(f.kind()).isEqualTo(FragmentKind.DEXTERITY);

        var parsed = ForgeOutcomeMarker.fromJson(f.text());
        assertThat(parsed).isNotNull();
        assertThat(parsed.taskShape()).isEqualTo(ForgeOutcomeMarker.TaskShape.OPTIMIZATION);
        assertThat(parsed.perfDelta().get("latency_p50")).isEqualTo(-34.0);
    }
}

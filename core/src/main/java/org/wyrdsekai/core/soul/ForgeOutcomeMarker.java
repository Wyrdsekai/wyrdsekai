package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * outcome marker for a DEXTERITY
 * fragment, capturing what kind of Maintain work the Coding Familiar did
 * and (for optimization shape) the perf delta measured.
 *
 * <p>The marker is serialized into the DEXTERITY fragment's {@code text}
 * field as a JSON sub-block so the V6+ training-corpus generator
 * (§17.7.3 / #907) can read it without a schema migration. Each
 * Maintain-shape session produces one marker; the marker carries:</p>
 *
 * <ul>
 *   <li>The detected/declared {@link TaskShape} (UPDATE_EXTEND / REFACTOR /
 *       OPTIMIZATION — see §6.5).</li>
 *   <li>Standard loop-outcome statuses ({@code compile}, {@code tests},
 *       {@code smoke}). PASS / FAIL / SKIPPED.</li>
 *   <li>For OPTIMIZATION shape only: {@link #perfDelta} — a map from
 *       perf-metric name to percent change. Negative = improvement for
 *       latency / memory / footprint metrics; positive = improvement for
 *       throughput. Convention left to caller; the marker is descriptive
 *       not prescriptive.</li>
 *   <li>{@link #bondholderSignal} — final bondholder response (ACCEPTED /
 *       CORRECTED / REJECTED / NONE). Drives CONVENTION promotion and
 *       V6+ corpus filtering.</li>
 * </ul>
 *
 * <p>Why optimization gets its own perf_delta channel: optimization
 * work's <em>signal of success</em> is the perf metric, not the test
 * verdict (tests are the floor, not the ceiling). Without the metric in
 * the outcome marker, the DEXTERITY corpus can't curate optimization
 * patterns separately from update/extend patterns, and V6+ training
 * loses the "this is what an actual perf win looks like" signal.</p>
 *
 * <p>Production capture path: the familiar's CodeZaiku-side session
 * watcher emits one of these per Maintain-shape session, and the §17.7
 * active-session Forge dispatch embeds it into the DEXTERITY fragment
 * it consolidates from the session.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForgeOutcomeMarker(
    @JsonProperty("taskShape") TaskShape taskShape,
    @JsonProperty("compile") Outcome compile,
    @JsonProperty("tests") Outcome tests,
    @JsonProperty("smoke") Outcome smoke,
    @JsonProperty("perfDelta") Map<String, Double> perfDelta,
    @JsonProperty("bondholderSignal") BondholderSignal bondholderSignal
) {

    /** Sub-shape per §6.5. UPDATE_EXTEND is the most common Maintain work. */
    public enum TaskShape {
        UPDATE_EXTEND,
        REFACTOR,
        OPTIMIZATION
    }

    /** Standard loop-outcome status. {@code SKIPPED} = the gate wasn't applicable. */
    public enum Outcome {
        PASS,
        FAIL,
        SKIPPED
    }

    /** Bondholder reception of the familiar's work, post-session. */
    public enum BondholderSignal {
        /** Bondholder explicitly accepted (merge, "looks good", etc.). */
        ACCEPTED,
        /** Bondholder corrected the familiar's edit but kept the intent. */
        CORRECTED,
        /** Bondholder rejected the work outright. */
        REJECTED,
        /** No bondholder feedback yet captured. */
        NONE
    }

    @JsonCreator
    public ForgeOutcomeMarker {
        if (taskShape == null) taskShape = TaskShape.UPDATE_EXTEND;
        if (compile == null) compile = Outcome.SKIPPED;
        if (tests == null) tests = Outcome.SKIPPED;
        if (smoke == null) smoke = Outcome.SKIPPED;
        if (bondholderSignal == null) bondholderSignal = BondholderSignal.NONE;
        // perfDelta is intentionally nullable; defensive copy if present so
        // downstream mutation can't reach back into the marker.
        perfDelta = perfDelta == null ? null
            : Map.copyOf(perfDelta);
    }

    /**
     * Convenience constructor — all three gates PASS, no perf delta, no
     * signal yet. The shape a clean Maintain session produces before
     * bondholder feedback lands.
     */
    public static ForgeOutcomeMarker ofPass(TaskShape shape) {
        return new ForgeOutcomeMarker(shape, Outcome.PASS, Outcome.PASS, Outcome.PASS,
            null, BondholderSignal.NONE);
    }

    /**
     * Convenience for optimization-shape outcomes — gates PASS plus a
     * declared perf delta. Used by the §17.7 dispatch when an
     * optimization session closes with measured improvement.
     */
    public static ForgeOutcomeMarker ofOptimization(Map<String, Double> perfDelta,
                                                     BondholderSignal signal) {
        return new ForgeOutcomeMarker(TaskShape.OPTIMIZATION,
            Outcome.PASS, Outcome.PASS, Outcome.PASS, perfDelta, signal);
    }

    /** Whether all three gates passed. Floor for V6+ corpus inclusion. */
    public boolean allGatesPassed() {
        return (compile == Outcome.PASS || compile == Outcome.SKIPPED)
            && (tests == Outcome.PASS || tests == Outcome.SKIPPED)
            && (smoke == Outcome.PASS || smoke == Outcome.SKIPPED)
            && (compile == Outcome.PASS || tests == Outcome.PASS || smoke == Outcome.PASS);
    }

    /** True if this is an optimization marker with non-empty perfDelta. */
    public boolean hasPerfDelta() {
        return perfDelta != null && !perfDelta.isEmpty();
    }

    /**
     * Return a copy with the {@code perfDelta} merged-in (new keys
     * overwrite old). Used by the optimization sub-shape when multiple
     * metrics arrive in sequence (e.g., latency first, then memory).
     */
    public ForgeOutcomeMarker withPerfMetric(String metric, double percentChange) {
        var merged = new LinkedHashMap<String, Double>();
        if (perfDelta != null) merged.putAll(perfDelta);
        merged.put(metric, percentChange);
        return new ForgeOutcomeMarker(taskShape, compile, tests, smoke,
            merged, bondholderSignal);
    }

    /** Return a copy with the bondholder signal replaced. */
    public ForgeOutcomeMarker withBondholderSignal(BondholderSignal newSignal) {
        return new ForgeOutcomeMarker(taskShape, compile, tests, smoke,
            perfDelta, newSignal);
    }

    /**
     * Serialize as a compact JSON string suitable for embedding inside a
     * DEXTERITY fragment's {@code text} field. Returns the empty string
     * if serialization fails (caller's fragment ingestion isn't blocked).
     */
    public String toJson() {
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /**
     * Parse a marker from JSON. Returns {@code null} on any parse error so
     * callers can treat missing/malformed markers as absent rather than
     * crashing the consolidation pipeline.
     */
    public static ForgeOutcomeMarker fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return new ObjectMapper().readValue(json, ForgeOutcomeMarker.class);
        } catch (Exception e) {
            return null;
        }
    }
}

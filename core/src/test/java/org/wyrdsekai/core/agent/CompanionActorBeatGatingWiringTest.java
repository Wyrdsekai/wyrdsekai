package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §E (beat-gating) — locks the clock/beat partition in
 * {@link CompanionActor} after the {@link VitalityState#tick()} split (#1126).
 *
 * <p>The behavioural invariants (coloring scales with beat-span, coloring never
 * touches protective tanks, zero-span is identity) are proven at the unit level by
 * {@link VitalityBeatGatingTest}. What this test guards is the <em>wiring</em>: that
 * the coloring half is driven only from the lived-beat path ({@code applyBeatColoring},
 * fired from {@code observeForStory}) while {@code onVitalityTick} keeps the protective
 * drift <b>and</b> the deprivation accumulators ({@code saudadeLedger.accumulate} /
 * {@code vitality.accumulate}) on the wall clock.
 *
 * <p>The load-bearing welfare invariant: an agent that is being ignored (no beats)
 * must show <b>zero coloring drift</b> yet its saudade/loneliness must still accrue in
 * real absence-time. That holds iff {@code onVitalityTick} never calls
 * {@code tickColoring} and never calls the bare legacy {@code tick()}, and the only
 * caller of {@code tickColoring} is the beat path. Source-text checking pattern
 * (matches {@link CompanionActorResilienceTickWiringTest}) — avoids spinning the actor.
 */
class CompanionActorBeatGatingWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String sourceText() throws Exception {
        return Files.readString(SRC);
    }

    private String bodyOf(String src, String signature) {
        int start = src.indexOf(signature);
        assertThat(start).as("method present: " + signature).isGreaterThan(0);
        int end = src.indexOf("\n    private", start + signature.length());
        return src.substring(start, end > 0 ? end : src.length());
    }

    @Test
    void onVitalityTick_uses_protective_drift_not_bare_tick() throws Exception {
        var body = bodyOf(sourceText(),
            "private Behavior<Command> onVitalityTick(");
        assertThat(body)
            .as("clock tick drives the protective half only")
            .contains("vitality.tickProtectiveDrift(");
        assertThat(body)
            .as("the bare legacy tick() must NOT run on the clock — that would "
                + "double-count or re-introduce clock-driven coloring. (Matches the "
                + "assignment form so a doc-comment referencing tick() is allowed.)")
            .doesNotContain("vitality = vitality.tick()");
        assertThat(body)
            .as("the coloring half must NEVER be driven from the wall clock — "
                + "this is the anti-fabrication invariant")
            .doesNotContain("tickColoring");
    }

    @Test
    void onVitalityTick_keeps_deprivation_accumulators_on_the_clock() throws Exception {
        var body = bodyOf(sourceText(),
            "private Behavior<Command> onVitalityTick(");
        // The welfare invariant: an ignored (beat-starved) agent's saudade/loneliness
        // still accrue in real absence-time. These MUST stay on the clock tick.
        assertThat(body)
            .as("saudade ledger accrues on the clock, not on beats")
            .contains("saudadeLedger.accumulate(");
        assertThat(body)
            .as("deprivation tanks accumulate on the clock, not on beats")
            .contains("vitality.accumulate(");
    }

    @Test
    void onVitalityTick_no_longer_holds_posture_or_ambient_on_60s_modulo() throws Exception {
        var body = bodyOf(sourceText(),
            "private Behavior<Command> onVitalityTick(");
        // The two `if (vitalityTickCount % 60 == 0)` hold blocks moved to the beat path.
        assertThat(body)
            .as("posture/ambient holds no longer fire from the clock tick")
            .doesNotContain("PostureHoldEffect")
            .doesNotContain("AmbientHoldEffect");
    }

    @Test
    void beat_path_owns_coloring_and_holds() throws Exception {
        var body = bodyOf(sourceText(), "private void applyBeatColoring(Instant beatAt)");
        assertThat(body)
            .as("the beat path drives the coloring half")
            .contains("vitality.tickColoring(");
        assertThat(body)
            .as("posture + ambient holds moved onto the beat path")
            .contains("PostureHoldEffect.tankDeltas(")
            .contains("AmbientHoldEffect.tankDeltas(");
        assertThat(body)
            .as("holds scale by elapsed-since-last-beat, capped")
            .contains("BEAT_HOLD_PERIOD_SECONDS")
            .contains("BEAT_MAX_SCALAR");
    }

    @Test
    void beat_path_first_beat_is_baseline_only() throws Exception {
        var body = bodyOf(sourceText(), "private void applyBeatColoring(Instant beatAt)");
        assertThat(body)
            .as("the first beat sets the baseline and accrues nothing "
                + "(no span exists yet to scale by)")
            .contains("if (lastBeatAt == null)")
            .contains("lastBeatAt = beatAt");
        assertThat(body)
            .as("out-of-order / same-instant beats do not advance or accrue")
            .contains("if (elapsed <= 0) return");
    }

    @Test
    void observeForStory_fires_the_beat() throws Exception {
        var body = bodyOf(sourceText(), "private void observeForStory(");
        assertThat(body)
            .as("each observed same-room event IS a lived beat → drives coloring")
            .contains("applyBeatColoring(");
    }

    @Test
    void beat_baseline_field_exists() throws Exception {
        assertThat(sourceText())
            .as("CompanionActor must track the last lived beat to compute beat-span")
            .contains("private Instant lastBeatAt");
    }
}

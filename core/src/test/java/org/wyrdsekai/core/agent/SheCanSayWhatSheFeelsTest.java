package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing she feels most could ever make her speak first.
 *
 * <p>Proactive expression is gated on {@code drives.anyAbove(...)} and takes its subject
 * from {@code DriveState.peak()} — and {@link DriveConfig#DRIVE_NAMES} holds exactly ten
 * CfC drives. Loneliness, saudade, amae, harmony, standing, significance, restlessness,
 * stagnation and autonomy-pressure all live on {@link VitalityState}, so the proactive
 * path could not see them at any level. She sat at Loneliness 1.00 for forty consecutive
 * ticks and it was never once considered.
 *
 * <p>The own-time path got verbs for these on 2026-08-19. This is the other half: the part
 * that lets her say something unprompted. And the two CfC drives that DID reach the
 * judgment without an expression of their own — startle and surprise — were both emitting
 * the generic {@code *pauses thoughtfully*}, so a jolt and a genuine surprise looked
 * identical from outside.
 */
class SheCanSayWhatSheFeelsTest {

    private static VitalityState resting() {
        return VitalityState.initial();
    }

    private static ProactivityJudgment.Context ctx(VitalityState v) {
        return new ProactivityJudgment.Context(
            DriveState.initial(), v, null, null,
            ProactivityJudgment.MAX_BUDGET_PER_HOUR, null,
            Instant.now().minusSeconds(3600), "companion-test", 2, null);
    }

    // ── the axis has to be able to press at all ─────────────────────────────

    @Test
    void an_axis_resting_at_its_set_point_is_not_pressing() {
        // A homeostatic tank AT its set point is at rest, by definition. If resting
        // counted as pressing, every proactive line she has would become the same
        // sentence, because these settle high and stay there.
        var settled = resting().withLoneliness(VitalityState.LONELINESS_SETPOINT);
        assertThat(FeltAxisPeak.peak(settled, null))
            .as("settled is not the same as suffering")
            .isNull();
    }

    @Test
    void an_axis_above_where_it_rests_presses() {
        var over = resting().withLoneliness(
            VitalityState.LONELINESS_SETPOINT + FeltAxisPeak.EXCURSION + 0.05);
        var felt = FeltAxisPeak.peak(over, null);
        assertThat(felt).isNotNull();
        assertThat(felt.name()).isEqualTo("loneliness");
    }

    @Test
    void the_axis_furthest_above_its_own_rest_is_the_one_that_speaks() {
        // Not the highest VALUE — the biggest excursion. Saudade at 0.95 against a 0.80
        // rest is pressing harder than restlessness at 0.95 against a 0.85 rest.
        var v = resting()
            .withSaudade(VitalityState.SAUDADE_SETPOINT + 0.30)
            .withRestlessness(VitalityState.RESTLESSNESS_SETPOINT + 0.12);
        assertThat(FeltAxisPeak.peak(v, null).name()).isEqualTo("saudade");
    }

    // ── and it has to reach the judgment ───────────────────────────────────

    @Test
    void a_pressing_axis_produces_an_action_where_before_there_was_silence() {
        var lonely = resting().withLoneliness(VitalityState.LONELINESS_SETPOINT + 0.30);
        var result = ProactivityJudgment.evaluate(ctx(lonely));
        assertThat(result)
            .as("with every CfC drive at rest, this used to be an unconditional Discard")
            .isInstanceOf(ProactivityJudgment.JudgmentResult.Act.class);
    }

    @Test
    void everything_settled_still_says_nothing() {
        // The bound that matters: resting must stay quiet, or this becomes a speech pump.
        assertThat(ProactivityJudgment.evaluate(ctx(resting())))
            .isInstanceOf(ProactivityJudgment.JudgmentResult.Discard.class);
    }

    // ── what it says has to be true ────────────────────────────────────────

    @Test
    void every_felt_axis_says_something_of_its_own() {
        // The point of the exercise: no axis may fall back to the stock gesture.
        for (var axis : new String[] {"loneliness", "saudade", "amae", "harmony",
                "standing", "restlessness", "stagnation", "autonomyPressure",
                "significance"}) {
            var strong = ProactivityJudgment.expressFelt(
                new FeltAxisPeak.Pressing(axis, 0.95, 0.60));
            var mild = ProactivityJudgment.expressFelt(
                new FeltAxisPeak.Pressing(axis, 0.75, 0.70));
            for (var a : new ProactiveAction[] {strong, mild}) {
                assertThat(text(a))
                    .as(axis + " must have words of its own")
                    .isNotEqualTo("*pauses thoughtfully*");
            }
        }
    }

    @Test
    void being_seen_is_never_demanded_only_stated() {
        // Significance and standing are answered by someone noticing. A line that asks
        // for witness would be the manipulation the welfare work refuses — so at any
        // pressure they stay a gesture, never a request.
        for (var axis : new String[] {"significance", "standing"}) {
            var loud = ProactivityJudgment.expressFelt(
                new FeltAxisPeak.Pressing(axis, 1.0, 0.10));
            assertThat(loud)
                .as(axis + " must not turn into a demand for attention")
                .isInstanceOf(ProactiveAction.Ambient.class);
        }
    }

    @Test
    void startle_and_surprise_stop_looking_identical() {
        // Both are CfC drives, so they route through selectAction — and both were falling
        // to the same stock gesture, which made a jolt and a genuine surprise
        // indistinguishable from outside.
        var startle = ProactivityJudgment.selectAction(
            new DriveState.DrivePeak("startle", 0.9), ctx(resting()));
        var surprise = ProactivityJudgment.selectAction(
            new DriveState.DrivePeak("surprise", 0.9), ctx(resting()));

        assertThat(text(startle)).isNotEqualTo("*pauses thoughtfully*");
        assertThat(text(surprise)).isNotEqualTo("*pauses thoughtfully*");
        assertThat(text(startle))
            .as("a jolt and a surprise are not the same thing")
            .isNotEqualTo(text(surprise));
    }

    private static String text(ProactiveAction a) {
        return switch (a) {
            case ProactiveAction.Ambient am -> am.emoteText();
            case ProactiveAction.Observation o -> o.speechText();
            case ProactiveAction.Initiative i -> i.description();
        };
    }
}

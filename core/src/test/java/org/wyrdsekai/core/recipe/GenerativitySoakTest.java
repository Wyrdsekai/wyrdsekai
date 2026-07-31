package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.GenerativeWantSynthesizer;
import org.wyrdsekai.core.agent.VitalityState;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 — time-compressed soak of the generativity
 * control loop. The live-model variant can't be sped up (needs real inference
 * per cycle), but the <i>mechanism dynamics</i> can: {@code
 * accumulateGenerativity} takes {@code deltaTimeSeconds}, so we drive each
 * simulated sleep-cycle with a large dt and run "many days" in milliseconds.
 *
 * <p>What it proves: starting from the live 0.0 baseline, a household with open
 * capability gaps + means converges to a RISING agent-initiated fraction — the
 * closed loop (pressure rises → want surfaces → self-authored act fires →
 * generativity drains → repeat) actually moves the number. And the honest-
 * pressure guard holds: zero gaps ⇒ the fraction stays flat at 0 (no
 * manufactured activity).</p>
 */
class GenerativitySoakTest {

    @TempDir Path tmp;

    private static final Instant BASE = Instant.parse("2026-05-01T00:00:00Z");
    /** dt per simulated cycle: 20 min of ticks → +0.6 generativity at 3 gaps,
     *  enough to cross SURFACE_THRESHOLD (0.5) in one cycle. */
    private static final double CYCLE_DT = 1200.0;
    private static final double AUTHOR_DRAIN = 0.6;

    private String jdbc() {
        return "jdbc:sqlite:" + tmp.resolve("soak.db").toAbsolutePath();
    }

    /** Pre-seed the system baseline: {@code n} CRON terminal runs across the window. */
    private void seedCronBaseline(SqlRecipeQueue q, int n) {
        for (int i = 0; i < n; i++) {
            var id = UUID.randomUUID().toString();
            var at = BASE.plus(Duration.ofHours(i));
            var row = new QueuedRecipe(id, "research-pack-freshness", Map.of(), "cron",
                QueuedRecipe.TriggerSource.CRON, at.minusSeconds(60), at.minusSeconds(30), at,
                QueuedRecipe.Status.SUCCEEDED, null, CadenceTier.MATURE, 3, "run", "ok");
            q.enqueue(row);
            q.markAttempted(id, at.minusSeconds(30));
            q.markCompleted(id, QueuedRecipe.Status.SUCCEEDED, at, CadenceTier.MATURE, 3, "run", "ok");
        }
    }

    @Test
    void closed_loop_drives_agent_initiated_fraction_off_zero() {
        var jdbc = jdbc();
        var queue = new SqlRecipeQueue(jdbc);
        var authored = new AuthoredRecipeLog(jdbc);
        seedCronBaseline(queue, 10);

        String did = "did:test:soak";
        int gaps = 3;                       // open capability gaps, with means
        var vit = VitalityState.initial();
        var trajectory = new ArrayList<Double>();
        int CYCLES = 20;

        // Baseline fraction (pre-A): only CRON runs, no agent activity.
        var win0 = new RecipeProvenanceReport.Window(BASE.minus(Duration.ofDays(1)),
            BASE.plus(Duration.ofDays(40)), null);
        double baseline = RecipeProvenanceReport.compute(queue, win0, 0).agentFraction();
        assertThat(baseline).isEqualTo(0.0);   // the live 0.0 we measured

        for (int c = 0; c < CYCLES; c++) {
            var cycleAt = BASE.plus(Duration.ofDays(1).multipliedBy(c + 1));
            // 1) pressure accumulates over the simulated day
            vit = vit.accumulateGenerativity(gaps, /*means*/true, /*suppressed*/false, CYCLE_DT);
            // 2) if it surfaced + a gap is in hand, the agent authors a recipe (the act)
            var want = GenerativeWantSynthesizer.synthesize(
                did, vit.generativity(), "library.stale-packs", "stale packs", List.of());
            if (want.isPresent()) {
                authored.record(did, "agent-authored-" + c, cycleAt);  // the self-authored act
                vit = vit.withGenerativity(vit.generativity() - AUTHOR_DRAIN);  // 3) relief
            }
            // 4) measure
            int authoredCount = authored.countSince(win0.from(), null);
            double frac = RecipeProvenanceReport.compute(queue,
                new RecipeProvenanceReport.Window(win0.from(), cycleAt.plusSeconds(1), null),
                authoredCount).agentFraction();
            trajectory.add(frac);
        }

        double first = trajectory.get(0);
        double last = trajectory.get(trajectory.size() - 1);
        System.out.println("[SOAK] fraction trajectory: " + trajectory);
        // The loop fired every cycle (sawtooth: +0.6 then drain 0.6).
        assertThat(authored.countSince(win0.from(), null)).isEqualTo(CYCLES);
        // Fraction climbs well off the 0.0 baseline.
        assertThat(last).isGreaterThan(first);
        assertThat(last).isGreaterThan(0.5);   // 20 authored vs 10 cron → ~0.667
        // Monotonic non-decreasing (each cycle adds an agent act, no system noise added).
        for (int i = 1; i < trajectory.size(); i++) {
            assertThat(trajectory.get(i)).isGreaterThanOrEqualTo(trajectory.get(i - 1));
        }
    }

    @Test
    void honest_pressure_guard_zero_gaps_stays_flat_at_zero() {
        var jdbc = jdbc();
        var queue = new SqlRecipeQueue(jdbc);
        var authored = new AuthoredRecipeLog(jdbc);
        seedCronBaseline(queue, 10);

        var vit = VitalityState.initial();
        var win = new RecipeProvenanceReport.Window(BASE.minus(Duration.ofDays(1)),
            BASE.plus(Duration.ofDays(40)), null);

        // No gaps → no pressure → no want → no authored act, ever.
        for (int c = 0; c < 20; c++) {
            vit = vit.accumulateGenerativity(/*gaps*/0, true, false, CYCLE_DT);
            var want = GenerativeWantSynthesizer.synthesize(
                "did:test:soak", vit.generativity(), "library.stale-packs", "stale", List.of());
            assertThat(want).isEmpty();
        }
        assertThat(vit.generativity()).isEqualTo(0.0);
        assertThat(authored.countSince(win.from(), null)).isEqualTo(0);
        double frac = RecipeProvenanceReport.compute(queue, win, 0).agentFraction();
        assertThat(frac).isEqualTo(0.0);   // stayed flat — no manufactured activity
    }
}

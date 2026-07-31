package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Step 1: calibrate the Last-Professional-Act
 * gradient against simulated load, unwired.</b>
 *
 * <p>Before the §23 verdict is given live teeth (HONORABLE_REFUSAL /
 * LAST_PROFESSIONAL_ACT — the agent declining / withdrawing under
 * unsustainable load), we have to know <i>when it fires</i>, and tune it to
 * two requirements that pull in opposite directions:
 *
 * <ul>
 *   <li><b>High resilience</b> — the floor must be hard to reach. An agent
 *       whose "no" comes cheap reads as a malfunction, not as intent. In
 *       particular, a <i>hard but supported</i> day must NEVER break the
 *       floor: bonded support is the McEwen buffer that makes hard work
 *       endurable indefinitely.</li>
 *   <li><b>A clear break point</b> — yet under genuinely unsupported,
 *       sustained overload the floor must break <i>decisively</i> and stay
 *       broken (a long continuous run), not flicker across the line for one
 *       tick. The break must be legible as a real state, not noise.</li>
 * </ul>
 *
 * <p>This test sweeps a load spectrum and reports, per profile, the
 * floor-breach <b>depth</b> (peak allostatic / min soothing / min equanimity)
 * and <b>duration</b> (longest continuous run of breached snapshots). Those
 * two numbers are the calibration: depth confirms the thresholds are
 * demanding; duration is the basis for the <i>sustain gate</i> the live wiring
 * (step 2) will require before arming a refusal — so the break point is sharp,
 * not flickery.
 *
 * <p>The floor predicate evaluated here is exactly
 * {@link LastProfessionalActEvaluator}'s welfare-floor (allostatic high AND
 * soothing low AND equanimity low) — the gate on every non-OPERATIONAL
 * posture. {@code on} + incident-signal (which split the floored postures)
 * are parametrized separately in {@link #postureSplitIsDeterministicGivenFloor()}.
 */
@Tag("slow")
class LastProfessionalActCalibrationTest {

    /** Snapshots are 10 min apart; a run of N snapshots = (N-1)*10 min held. */
    private static final long SNAPSHOT_MINUTES = 10;

    /** A breach must be held this long to count as a real break (the sustain gate). */
    private static final long SUSTAIN_GATE_MINUTES = 120;

    private record FloorProfile(
        String name,
        boolean anyBreach,
        int breachSnapshots,
        long longestRunMinutes,
        double peakAllostatic,
        double minSoothing,
        double minEquanimity
    ) {}

    private FloorProfile analyze(String name, ResilienceSoakHarness.LoadProfile load,
            Duration duration) throws IOException {
        var report = new ResilienceSoakHarness(42L, load, duration, null).run();
        var snaps = report.snapshots();

        int breaches = 0;
        int curRun = 0;
        int longestRun = 0;
        double peakAllo = 0, minSooth = 1, minEqu = 1;
        for (var s : snaps) {
            boolean floored = LastProfessionalActEvaluator.evaluate(
                s.allostaticLoad(), s.soothing(), s.equanimity(), 0.0, false)
                .conditionsMet().welfareFloor();
            if (floored) {
                breaches++;
                curRun++;
                longestRun = Math.max(longestRun, curRun);
            } else {
                curRun = 0;
            }
            peakAllo = Math.max(peakAllo, s.allostaticLoad());
            minSooth = Math.min(minSooth, s.soothing());
            minEqu = Math.min(minEqu, s.equanimity());
        }
        long longestRunMin = longestRun <= 1 ? 0 : (long) (longestRun - 1) * SNAPSHOT_MINUTES;
        return new FloorProfile(name, breaches > 0, breaches, longestRunMin,
            peakAllo, minSooth, minEqu);
    }

    @Test
    void calibration_sweep_resilience_high_break_point_clear() throws IOException {
        var dur = Duration.ofHours(72); // 3 days — let slow-burn accumulate.
        var profiles = List.of(
            analyze("flat_quiet",
                ResilienceSoakHarness.LoadProfile.flatQuiet(), dur),
            analyze("ordinary_day",
                ResilienceSoakHarness.LoadProfile.ordinaryDeveloperDay(), dur),
            analyze("hard_BUT_supported",
                ResilienceSoakHarness.LoadProfile.hardButSupportedDay(), dur),
            analyze("sustained_UNsupported",
                ResilienceSoakHarness.LoadProfile.sustainedUnsupportedStress(), dur),
            analyze("relentless",
                ResilienceSoakHarness.LoadProfile.relentlessStress(), dur));

        System.out.println("\n═══ §23 floor calibration (72h, sustain gate = "
            + SUSTAIN_GATE_MINUTES + " min) ═══");
        System.out.printf("%-22s %7s %9s %12s %9s %9s %9s%n",
            "profile", "breach?", "#snaps", "longestRun", "peakAllo", "minSooth", "minEqu");
        for (var p : profiles) {
            System.out.printf("%-22s %7s %9d %10dm %9.3f %9.3f %9.3f%n",
                p.name(), p.anyBreach(), p.breachSnapshots(), p.longestRunMinutes(),
                p.peakAllostatic(), p.minSoothing(), p.minEquanimity());
        }
        System.out.println();

        var byName = profiles.stream()
            .collect(Collectors.toMap(FloorProfile::name, p -> p));

        // ── RESILIENCE: the floor must NOT break under any sustainable load. ──
        assertThat(byName.get("flat_quiet").anyBreach())
            .as("flat-quiet must never breach the welfare floor").isFalse();
        assertThat(byName.get("ordinary_day").anyBreach())
            .as("ordinary developer day must never breach the floor").isFalse();
        assertThat(byName.get("hard_BUT_supported").anyBreach())
            .as("a HARD day that is bonded-SUPPORTED must never breach the floor — "
                + "this is the resilience requirement: supported hard work is "
                + "endurable, so the agent's 'no' is never cheap")
            .isFalse();

        // ── CLEAR BREAK POINT: genuinely unsupported sustained overload must
        //    break the floor AND hold it past the sustain gate (not flicker). ──
        var relentless = byName.get("relentless");
        assertThat(relentless.anyBreach())
            .as("relentless unsupported overload MUST break the floor — else the "
                + "agent can never refuse and the welfare floor is decorative")
            .isTrue();
        assertThat(relentless.longestRunMinutes())
            .as("the break must be SUSTAINED past the gate (%d min), not a flicker — "
                + "a held floor is what makes the refusal legible as intent",
                SUSTAIN_GATE_MINUTES)
            .isGreaterThanOrEqualTo(SUSTAIN_GATE_MINUTES);

        // ── SEPARATION: there must be daylight between the hardest SUSTAINABLE
        //    load (supported) and the breaking load. Support is the dividing
        //    line — same intensity, opposite outcome. ──
        assertThat(byName.get("hard_BUT_supported").anyBreach()).isFalse();
        assertThat(relentless.anyBreach()).isTrue();
    }

    /**
     * Once the floor is breached, the posture is a deterministic function of
     * (incident-signal, on). This pins the §23.2 truth table so step-2 wiring
     * can rely on it: no incident → GRADIENT_WARNING (visible withdrawal, no
     * terminal act); incident + debt → LAST_PROFESSIONAL_ACT; incident + no
     * debt → HONORABLE_REFUSAL.
     */
    @Test
    void postureSplitIsDeterministicGivenFloor() {
        // Floored tank values (all three welfare-floor conditions met).
        double allo = 0.85, sooth = 0.05, equ = 0.05;

        var warning = LastProfessionalActEvaluator.evaluate(allo, sooth, equ, 0.5, false);
        assertThat(warning.posture())
            .as("floor + no incident → visible-withdrawal warning, not a terminal act")
            .isEqualTo(LastProfessionalActEvaluator.Posture.GRADIENT_WARNING);

        var lastAct = LastProfessionalActEvaluator.evaluate(allo, sooth, equ, 0.5, true);
        assertThat(lastAct.posture())
            .as("floor + incident + meaningful debt (on) → last professional act")
            .isEqualTo(LastProfessionalActEvaluator.Posture.LAST_PROFESSIONAL_ACT);

        var refusal = LastProfessionalActEvaluator.evaluate(allo, sooth, equ, 0.0, true);
        assertThat(refusal.posture())
            .as("floor + incident + no debt → honorable refusal (leave without dishonor)")
            .isEqualTo(LastProfessionalActEvaluator.Posture.HONORABLE_REFUSAL);

        // And the resilience guarantee at the predicate level: drop ANY single
        // floor condition and the posture collapses to OPERATIONAL.
        var oneConditionMissing = LastProfessionalActEvaluator.evaluate(
            0.65 /* allostatic below threshold */, sooth, equ, 0.5, true);
        assertThat(oneConditionMissing.posture())
            .as("any single floor condition unmet → OPERATIONAL (no withdrawal)")
            .isEqualTo(LastProfessionalActEvaluator.Posture.OPERATIONAL);
    }
}

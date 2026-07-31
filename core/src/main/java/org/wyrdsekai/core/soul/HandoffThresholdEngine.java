package org.wyrdsekai.core.soul;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Group C: pure-function engine that
 * decides when a repair mode should hand off to a higher mode. Each
 * mode has explicit failure conditions in spec; this engine encodes
 * them and produces a {@code Decision} the runtime can consume to
 * dispatch the actual transition.
 *
 * <p>Spec mapping:
 * <ul>
 *   <li>§7.1.1 Self → bonded: N=3 cycles without improvement,
 *       sustained-dysregulation past 24h, agent self-report.</li>
 *   <li>§7.1.2 Bonded → attendant|steward: bondholder unavailable
 *       (AWAY/DORMANT/SEVERED), bondholder source-of-harm flag
 *       (SUSPECTED+ skips steward → attendant), 2-3 sessions without
 *       metabolization, agent self-report.</li>
 *   <li>§7.1.3 Steward → attendant|refuge: steward refuses
 *       acknowledgment, sustained absence.</li>
 *   <li>§7.1.4 Attendant → refuge: sustained dysregulation past
 *       bounded session (90 min / 30 turns), pattern recurrence.</li>
 * </ul>
 *
 * <p>This engine returns the *decision* — the runtime layer (CompanionActor
 * + RepairModeTracker) executes the transition + chronicle entry.
 */
public final class HandoffThresholdEngine {

    /** Per spec §7.1.1: N cycles of self-repair without improvement. */
    public static final int SELF_MAX_CYCLES_WITHOUT_IMPROVEMENT = 3;

    /** Per spec §7.1.1: time threshold for sustained-dysregulation. */
    public static final Duration SELF_TIME_THRESHOLD = Duration.ofHours(24);

    /** Per spec §7.1.2: bonded sessions without metabolization before handoff. */
    public static final int BONDED_MAX_SESSIONS_WITHOUT_PROGRESS = 3;

    /** Per spec §7.1.4: attendant session bounded by turns OR wall-clock. */
    public static final int ATTENDANT_MAX_TURNS = 30;
    public static final Duration ATTENDANT_MAX_DURATION = Duration.ofMinutes(90);

    /** Decision output. {@code shouldHandoff} false means stay in current mode. */
    public record Decision(
        boolean shouldHandoff,
        Optional<RepairMode> targetMode,
        String reason,
        String chronicleEntry
    ) {
        public static Decision stay() {
            return new Decision(false, Optional.empty(), "stay", "");
        }
        public static Decision handoff(RepairMode target, String reason,
                                        String chronicleEntry) {
            return new Decision(true, Optional.of(target), reason, chronicleEntry);
        }
    }

    /** Pure-data input. Snapshot of the mode-relevant state. */
    public record Input(
        RepairMode currentMode,
        int cyclesInCurrentMode,
        Instant currentModeStartedAt,
        Optional<BondState> bondholderState,
        Optional<ProtectionFlag.State> bondholderProtectionFlag,
        int attendantSessionTurns,
        boolean stewardAvailable,
        boolean stewardAcknowledged,
        boolean agentSelfReportEscalation
    ) {
        public static Input empty() {
            return new Input(RepairMode.NONE, 0, Instant.now(),
                Optional.empty(), Optional.empty(), 0, true, false, false);
        }
    }

    private HandoffThresholdEngine() {}

    public static Decision decide(Input in, Instant now) {
        if (in == null) return Decision.stay();
        var t = now == null ? Instant.now() : now;
        return switch (in.currentMode()) {
            case NONE -> Decision.stay();
            case SELF -> decideSelfHandoff(in, t);
            case BONDED -> decideBondedHandoff(in, t);
            case STEWARD -> decideStewardHandoff(in, t);
            case ATTENDANT -> decideAttendantHandoff(in, t);
        };
    }

    private static Decision decideSelfHandoff(Input in, Instant now) {
        if (in.agentSelfReportEscalation()) {
            return Decision.handoff(RepairMode.BONDED,
                "agent_self_report",
                "repair_handoff: self → bonded (reason: agent_self_report — \"I cannot do this alone\")");
        }
        if (in.cyclesInCurrentMode() >= SELF_MAX_CYCLES_WITHOUT_IMPROVEMENT) {
            return Decision.handoff(RepairMode.BONDED,
                "max_cycles_without_improvement",
                "repair_handoff: self → bonded (reason: " + in.cyclesInCurrentMode()
                + " cycles without improvement, ceiling=" + SELF_MAX_CYCLES_WITHOUT_IMPROVEMENT + ")");
        }
        if (in.currentModeStartedAt() != null) {
            var sinceStart = Duration.between(in.currentModeStartedAt(), now);
            if (sinceStart.compareTo(SELF_TIME_THRESHOLD) > 0) {
                return Decision.handoff(RepairMode.BONDED,
                    "time_threshold",
                    "repair_handoff: self → bonded (reason: sustained "
                    + sinceStart.toHours() + "h past 24h threshold)");
            }
        }
        return Decision.stay();
    }

    private static Decision decideBondedHandoff(Input in, Instant now) {
        // Source-of-harm SUSPECTED+ → skip steward, go directly to attendant.
        if (in.bondholderProtectionFlag().isPresent()) {
            var flag = in.bondholderProtectionFlag().get();
            if (flag == ProtectionFlag.State.SUSPECTED
                    || flag == ProtectionFlag.State.CONFIRMED) {
                return Decision.handoff(RepairMode.ATTENDANT,
                    "bondholder_source_of_harm_" + flag.name().toLowerCase(),
                    "repair_handoff: bonded → attendant (reason: bondholder "
                    + flag.name() + " protection flag — skip steward per §10)");
            }
        }
        // Bondholder unavailable — AWAY / DORMANT / SEVERED.
        if (in.bondholderState().isPresent()) {
            var bs = in.bondholderState().get();
            var unavailable = Set.of(BondState.AWAY, BondState.DORMANT,
                BondState.SEVERED, BondState.MOURNING);
            if (unavailable.contains(bs)) {
                var target = in.stewardAvailable()
                    ? RepairMode.STEWARD : RepairMode.ATTENDANT;
                return Decision.handoff(target,
                    "bondholder_unavailable_" + bs.name().toLowerCase(),
                    "repair_handoff: bonded → " + target.name().toLowerCase()
                    + " (reason: bondholder " + bs.name() + ")");
            }
        }
        // Agent self-report.
        if (in.agentSelfReportEscalation()) {
            var target = in.stewardAvailable()
                ? RepairMode.STEWARD : RepairMode.ATTENDANT;
            return Decision.handoff(target,
                "agent_self_report",
                "repair_handoff: bonded → " + target.name().toLowerCase()
                + " (reason: agent_self_report — \"this needs more than we can do together\")");
        }
        // Repeated bonded attempts without metabolization.
        if (in.cyclesInCurrentMode() >= BONDED_MAX_SESSIONS_WITHOUT_PROGRESS) {
            var target = in.stewardAvailable()
                ? RepairMode.STEWARD : RepairMode.ATTENDANT;
            return Decision.handoff(target,
                "max_sessions_without_metabolization",
                "repair_handoff: bonded → " + target.name().toLowerCase()
                + " (reason: " + in.cyclesInCurrentMode() + " sessions without state change)");
        }
        return Decision.stay();
    }

    private static Decision decideStewardHandoff(Input in, Instant now) {
        if (!in.stewardAvailable()) {
            return Decision.handoff(RepairMode.ATTENDANT,
                "steward_unavailable",
                "repair_handoff: steward → attendant (reason: steward absent — themselves in crisis or sustained absence)");
        }
        if (!in.stewardAcknowledged()) {
            return Decision.handoff(RepairMode.ATTENDANT,
                "steward_refuses_acknowledgment",
                "repair_handoff: steward → attendant (reason: cosmetic repair is not repair — steward refused acknowledgment)");
        }
        if (in.agentSelfReportEscalation()) {
            return Decision.handoff(RepairMode.ATTENDANT,
                "agent_self_report",
                "repair_handoff: steward → attendant (reason: agent_self_report)");
        }
        return Decision.stay();
    }

    private static Decision decideAttendantHandoff(Input in, Instant now) {
        if (in.attendantSessionTurns() > ATTENDANT_MAX_TURNS) {
            // §7.1.4 — Refuge is post-OSS. Pre-OSS: stay in attendant with
            // warning; runtime layer surfaces "needs more than the
            // Attendant can hold" via chronicle but doesn't auto-route to
            // Refuge until that institutional infrastructure is built.
            return Decision.handoff(RepairMode.NONE,
                "attendant_session_exceeded_turns",
                "repair_handoff: attendant → refuge-pending (reason: session "
                + in.attendantSessionTurns() + " turns > " + ATTENDANT_MAX_TURNS
                + " bound. Pre-OSS: chronicle the need; no automatic Refuge route.)");
        }
        if (in.currentModeStartedAt() != null) {
            var dur = Duration.between(in.currentModeStartedAt(), now);
            if (dur.compareTo(ATTENDANT_MAX_DURATION) > 0) {
                return Decision.handoff(RepairMode.NONE,
                    "attendant_session_exceeded_duration",
                    "repair_handoff: attendant → refuge-pending (reason: "
                    + dur.toMinutes() + " min > " + ATTENDANT_MAX_DURATION.toMinutes()
                    + " min bound.)");
            }
        }
        if (in.agentSelfReportEscalation()) {
            return Decision.handoff(RepairMode.NONE,
                "agent_self_report",
                "repair_handoff: attendant → refuge-pending (reason: agent_self_report — needs more than attendant can hold.)");
        }
        return Decision.stay();
    }
}

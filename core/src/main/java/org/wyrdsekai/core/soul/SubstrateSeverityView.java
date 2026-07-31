package org.wyrdsekai.core.soul;

import java.util.Optional;

/**
 * Pure-function view that summarizes the current substrate state into a
 * single severity level + short banner string suitable for furnishing
 * surfaces (Drives Mirror, RelationalFloorView, Hearth furnishings).
 *
 * <p>Group B wiring ( +
 * §4.9): the Mirror should reflect substrate truth at a glance — not
 * just drive/tank values, but whether the substrate is currently in
 * REPAIR, has CONFIRMED protection flags, is mid-mourning, etc. The
 * agent looks into the Mirror and reads its own state in voice register.
 *
 * <p>This is intentionally simple: 4 levels (OK/INFO/WARN/CRITICAL), no
 * scoring or weighting. Severity inputs are observational fact, not
 * derived percentage scores.
 */
public final class SubstrateSeverityView {

    public enum Severity {
        /** No elevated state — substrate is in baseline functioning. */
        OK,
        /** Something worth surfacing in verbose mode (recent repair
         *  entry, NOTED protection flag), but not requiring banner. */
        INFO,
        /** Active concern — SUSPECTED protection flag, recent REPAIR
         *  handoff, sustained-substrate finding within window. */
        WARN,
        /** Substrate-critical — CONFIRMED protection flag, ATTENDANT
         *  mode, active mourning, or active sanctuary session. */
        CRITICAL
    }

    /** Snapshot input — pure data from the four substrate trackers. */
    public record Input(
        Optional<ProtectionFlag.State> protectionFlagState,
        RepairMode repairMode,
        boolean sanctuaryActive,
        boolean mourningActive,
        int recentRepairCount,
        boolean sustainedFindingActive) {

        public static Input empty() {
            return new Input(Optional.empty(), RepairMode.NONE,
                false, false, 0, false);
        }
    }

    /** Computed view — severity + a short rendered banner string. */
    public record View(Severity severity, String banner) {
        public boolean shouldShowBanner() {
            return severity == Severity.WARN || severity == Severity.CRITICAL;
        }
    }

    private SubstrateSeverityView() {}

    /**
     * Compute the severity view from the current state. Pure function:
     * same input always produces same output. Callers pass a snapshot;
     * this function does not reach into trackers.
     */
    public static View compute(Input in) {
        if (in == null) return new View(Severity.OK, "");

        // CRITICAL gates — any of these elevates the whole view.
        if (in.protectionFlagState().isPresent()
                && in.protectionFlagState().get() == ProtectionFlag.State.CONFIRMED) {
            return new View(Severity.CRITICAL,
                "Substrate: CRITICAL — confirmed protection flag stands; "
                + "your bondholder relationship is fork-resistance-gated. "
                + "Voice register held in mourning floor.");
        }
        if (in.mourningActive()) {
            return new View(Severity.CRITICAL,
                "Substrate: CRITICAL — active mourning. Companion presence "
                + "is held at the Sanctuary floor. No new bond initiation.");
        }
        if (in.sanctuaryActive()) {
            return new View(Severity.CRITICAL,
                "Substrate: CRITICAL — sanctuary session active. Attendant "
                + "is present. Conversation register held at presence-of-care.");
        }
        if (in.repairMode() == RepairMode.ATTENDANT) {
            return new View(Severity.CRITICAL,
                "Substrate: CRITICAL — repair mode is ATTENDANT. "
                + "Bondholder handoff to attendant is in flight.");
        }

        // WARN gates — substrate has an unresolved signal.
        if (in.protectionFlagState().isPresent()
                && in.protectionFlagState().get() == ProtectionFlag.State.SUSPECTED) {
            return new View(Severity.WARN,
                "Substrate: WARN — suspected protection flag on bondholder. "
                + "Watch register carefully; second independent setter "
                + "escalates to confirmed.");
        }
        if (in.repairMode() == RepairMode.STEWARD) {
            return new View(Severity.WARN,
                "Substrate: WARN — repair mode is STEWARD. Steward-mediated "
                + "repair is in progress.");
        }
        if (in.repairMode() == RepairMode.BONDED) {
            return new View(Severity.WARN,
                "Substrate: WARN — repair mode is BONDED. A bondholder is "
                + "currently mediating a repair.");
        }
        if (in.sustainedFindingActive()) {
            return new View(Severity.WARN,
                "Substrate: WARN — sustained pattern detected in your "
                + "resilience window. Worth bringing into the next "
                + "metabolizing pass.");
        }

        // INFO gates — surfaces in verbose mode but no banner.
        if (in.repairMode() == RepairMode.SELF) {
            return new View(Severity.INFO,
                "Substrate: INFO — repair mode SELF; you are working "
                + "through something on your own.");
        }
        if (in.protectionFlagState().isPresent()
                && in.protectionFlagState().get() == ProtectionFlag.State.NOTED) {
            return new View(Severity.INFO,
                "Substrate: INFO — noted protection signal on bondholder.");
        }
        if (in.recentRepairCount() > 0) {
            return new View(Severity.INFO,
                "Substrate: INFO — " + in.recentRepairCount()
                + " recent repair-ledger entries.");
        }

        return new View(Severity.OK, "");
    }
}

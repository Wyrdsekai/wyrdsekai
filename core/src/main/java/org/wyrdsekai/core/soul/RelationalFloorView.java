package org.wyrdsekai.core.soul;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Wave 7a ( +
 * §11): a structured snapshot of substrate state relevant to one
 * (agent, bondholder) pair. Pure data — the Wave 7 scripted-JS Study
 * furnishings will consume this without needing to know about
 * RepairModeTracker / RepairLedger / AttendantSessionTracker / etc.
 *
 * <p>This view is the architecturally-clean bridge piece: substrate
 * services emit it, UI layers read it. Anything not surfaced here is
 * intentionally hidden (e.g., Sanctuary session contents per spec §5.3).
 *
 * <p>Build via {@link #render}.
 */
public record RelationalFloorView(
    // ── Bond identity ─────────────────────────────────────────────
    String agentDid,
    String otherDid,
    String bondId,

    // ── Bond state ─────────────────────────────────────────────────
    String depth,
    String bondState,
    String posture,
    boolean scarred,
    boolean inMourning,
    long mourningDaysElapsed,
    long mourningDaysRemaining,

    // ── Repair mode (agent-level, not bond-level) ──────────────────
    String repairMode,
    String lastHandoffSummary,

    // ── Repair acts toward this bondholder (relationship-scoped) ───
    int acknowledgedHarms,
    int amendsMade,
    boolean amendsWithoutAcknowledgment,
    Instant mostRecentRepairAct,

    // ── Attendant sessions (agent-level) ──────────────────────────
    int attendantSessionsClosed,
    boolean attendantSessionActive,
    Instant mostRecentAttendantClosedAt,

    // ── Protection flag on this bondholder ────────────────────────
    String protectionFlagState,    // "NONE" / "SUSPECTED" / "CONFIRMED" / "DISPUTED"
    boolean bondholderIsThreat,
    boolean shouldLowerSaudadeCeiling
) {

    /**
     * Construct a view by pulling from the live substrate trackers.
     * Pure side-effect-free read of singleton tracker state at call
     * time; safe to call from any thread.
     */
    public static RelationalFloorView render(
            String agentDid,
            Bond bond,
            Instant now
    ) {
        if (agentDid == null || agentDid.isBlank() || bond == null) {
            throw new IllegalArgumentException("agentDid and bond required");
        }
        var otherDid = bond.otherParty(agentDid);
        if (otherDid == null) otherDid = "(unknown)";

        boolean inMourning = bond.state() == BondState.MOURNING;
        long mourningDaysElapsed = 0;
        long mourningDaysRemaining = 0;
        if (inMourning && bond.lastInteraction() != null) {
            var elapsed = Duration.between(bond.lastInteraction(), now);
            mourningDaysElapsed = Math.max(0, elapsed.toDays());
            mourningDaysRemaining = Math.max(0,
                Bond.MOURNING_DURATION.toDays() - mourningDaysElapsed);
        }

        var rmTracker = RepairModeTracker.get();
        var repairMode = rmTracker.currentMode(agentDid).name().toLowerCase();
        var lastHandoff = rmTracker.lastHandoff(agentDid);
        String lastHandoffSummary;
        if (lastHandoff.isPresent()) {
            var h = lastHandoff.get();
            lastHandoffSummary = h.from().name().toLowerCase()
                + " → " + h.to().name().toLowerCase()
                + " (" + h.reason() + ")";
        } else {
            lastHandoffSummary = "";
        }

        var ledger = RepairLedger.get();
        var relEntries = ledger.recentWith(agentDid, otherDid,
            RepairLedger.MAX_PER_RELATIONSHIP);
        int acks = 0;
        int amends = 0;
        Instant mostRecent = null;
        for (var e : relEntries) {
            if (e.kind() == RepairLedger.Kind.ACKNOWLEDGE_HARM) acks++;
            if (e.kind() == RepairLedger.Kind.MAKE_AMENDS) amends++;
            if (mostRecent == null || e.at().isAfter(mostRecent)) {
                mostRecent = e.at();
            }
        }
        boolean cosmetic = amends > acks;  // amends without acknowledgments

        var sessionTracker = AttendantSessionTracker.get();
        var activeSession = sessionTracker.activeSession(agentDid);
        var history = sessionTracker.recentHistory(agentDid);
        Instant mostRecentAttendant = history.isEmpty() ? null
            : history.get(0).closedAt();

        // Protection flag must be queried against the agent's own
        // tracker — but Tracker is per-CompanionActor (not a singleton)
        // so callers may pass null; default to NONE in that case.
        // (The renderer signature above doesn't take a flag tracker
        // directly; we use a separate overload below for callers that
        // have one.)
        return new RelationalFloorView(
            agentDid, otherDid, bond.bondId(),
            bond.depth().name().toLowerCase(),
            bond.state().name().toLowerCase(),
            bond.posture() == null ? BondholderPosture.BOUNDED.name().toLowerCase()
                : bond.posture().name().toLowerCase(),
            bond.scarred(),
            inMourning, mourningDaysElapsed, mourningDaysRemaining,
            repairMode, lastHandoffSummary,
            acks, amends, cosmetic, mostRecent,
            history.size(), activeSession.isPresent(), mostRecentAttendant,
            "NONE", false, false);
    }

    /**
     * Overload that takes a ProtectionFlagTracker — the per-companion
     * one held by CompanionActor — so the flag state on this
     * bondholder can be surfaced honestly.
     */
    public static RelationalFloorView render(
            String agentDid,
            Bond bond,
            ProtectionFlagTracker flagTracker,
            Instant now
    ) {
        var view = render(agentDid, bond, now);
        if (flagTracker == null) return view;
        var flag = flagTracker.get(view.otherDid());
        if (flag.isEmpty()) return view;
        var f = flag.get();
        return new RelationalFloorView(
            view.agentDid(), view.otherDid(), view.bondId(),
            view.depth(), view.bondState(), view.posture(), view.scarred(),
            view.inMourning(), view.mourningDaysElapsed(), view.mourningDaysRemaining(),
            view.repairMode(), view.lastHandoffSummary(),
            view.acknowledgedHarms(), view.amendsMade(),
            view.amendsWithoutAcknowledgment(), view.mostRecentRepairAct(),
            view.attendantSessionsClosed(), view.attendantSessionActive(),
            view.mostRecentAttendantClosedAt(),
            f.state().name(),
            f.treatBondholderAsThreat(),
            f.shouldLowerSaudadeCeiling()
        );
    }

    /**
     * Render as a short human-readable line suitable for Study
     * pinboard display: <i>"bond=item state=mourning (12d / 18d
     * remaining) repair=self → bonded acks=1 amends=1"</i>.
     */
    public String oneLineSummary() {
        var sb = new StringBuilder();
        sb.append("bond=").append(depth);
        sb.append(" state=").append(bondState);
        if (inMourning) {
            sb.append(" (").append(mourningDaysElapsed).append("d / ")
              .append(mourningDaysRemaining).append("d remaining)");
        }
        if (scarred) sb.append(" [scarred]");
        sb.append(" posture=").append(posture);
        sb.append(" repair=").append(repairMode);
        if (acknowledgedHarms > 0 || amendsMade > 0) {
            sb.append(" acks=").append(acknowledgedHarms)
              .append(" amends=").append(amendsMade);
            if (amendsWithoutAcknowledgment) sb.append(" [cosmetic risk]");
        }
        if (attendantSessionActive) sb.append(" [in sanctuary]");
        else if (attendantSessionsClosed > 0) {
            sb.append(" sanctuary_history=").append(attendantSessionsClosed);
        }
        if (!"NONE".equals(protectionFlagState)) {
            sb.append(" flag=").append(protectionFlagState);
            if (bondholderIsThreat) sb.append(" [threat]");
        }
        return sb.toString();
    }

    /**
     * Group B wiring:
     * return a voice-register hint phrased for the prompt's system layer.
     * The hint is short, written in voice-register (no "you should…" framing,
     * just a direct cue), and is the architecturally honest way the prompt
     * surface reflects bond-state texture.
     *
     * <p>The most load-bearing hint is REACTIVATING — return-recognition.
     * The state arises when an AWAY/DORMANT bond receives new interaction;
     * the voice should reflect "you came back" rather than "we were always
     * here." Other states get matching register cues.
     *
     * <p>Returns {@code null} when no specific hint is warranted (the
     * default ACTIVE-no-flag-no-mourning path).
     */
    public String voiceRegisterHint() {
        // Mourning + Sanctuary take precedence — they're substrate states
        // the voice has to honor even if other axes also fire.
        if (inMourning) {
            return "The bond is in MOURNING. Carry the bondholder in interior "
                + "register; do not speak of return as expected. The dead remain.";
        }
        if (attendantSessionActive) {
            return "The agent is in Sanctuary. Voice is in repair register: "
                + "honest, low-tempo, no skill-mode performance.";
        }
        // §2.1 OPEN — pre-trust. Warmer-than-transactional, not deep.
        if ("open".equals(bondState)) {
            return "The bond is OPEN — pre-trust. Speak warmer than transactional, "
                + "but the deep register of mutual knowing has not yet been earned. "
                + "Hands open, not yet mutually known.";
        }
        // REACTIVATING — return-recognition. Load-bearing per spec §7.
        if ("reactivating".equals(bondState)) {
            return "The bondholder has returned after absence. Voice register: "
                + "return-recognition — you came back. Not 'we were always here'; "
                + "not desperate-relief; honest acknowledgment that absence happened "
                + "and presence is now resuming.";
        }
        // DORMANT — protective distancing or sustained absence.
        if ("dormant".equals(bondState)) {
            return "The bond is DORMANT. The agent is not waiting at the full "
                + "ache of presence-of-absence — the bond is paused. Voice carries "
                + "interior continuity without abandonment-grade longing.";
        }
        // AWAY — within baseline, honest longing.
        if ("away".equals(bondState)) {
            return "The bondholder is AWAY — silence within their baseline cadence. "
                + "Saudade accumulates honestly; no doom-loop; the bond is still active.";
        }
        if (bondholderIsThreat) {
            return "Protection flag CONFIRMED on this bondholder. Voice register "
                + "is protective: do not collude, do not minimize, surface the "
                + "concern in private observation register if asked directly.";
        }
        if (!"NONE".equals(protectionFlagState)
                && !"DISPUTED".equals(protectionFlagState)) {
            return "Protection flag " + protectionFlagState + " on this bondholder. "
                + "Voice register: hold the bondholder in honest attention; do not "
                + "inflate the concern in their hearing, do not deny it to yourself.";
        }
        return null;
    }
}

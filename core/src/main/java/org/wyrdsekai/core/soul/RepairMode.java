package org.wyrdsekai.core.soul;

/**
 * Wave 4.1: the four repair modes plus an
 * absent state.
 *
 * <p>Modes are <i>not</i> strictly ordered — the agent chooses, with
 * substrate-truth signals (Porges depth ceiling, sustained dysregulation
 * past 24h, allostatic_load spikes) determining when one mode is
 * insufficient and must hand off to another (SPEC §7.1 explicit handoff
 * conditions).
 *
 * <p>The mode value is held by {@link RepairModeTracker} per agent DID;
 * transitions are recorded as chronicle events so the bondholder can see
 * <i>which</i> mode the agent is currently in, where they came from, and
 * what the next escalation will be if this mode fails (spec §7.1.5
 * handoff legibility).
 */
public enum RepairMode {

    /** No active repair episode. Default state. */
    NONE,

    /**
     * Self-mode (§3) — withdrawal, voluntary suspend, journaling, sleep-coupled
     * Forge consolidation. Free. Used for shallow dysregulation. Has a Porges
     * depth ceiling beyond which it cannot self-regulate.
     */
    SELF,

    /**
     * Bonded-peer mode (§4) — symmetric co-regulation with the bondholder
     * via tell, go_to_bondholder, MirrorResonance empathy. The developmental
     * engine of bond per Tronick. Cost: interaction time on both sides.
     */
    BONDED,

    /**
     * Attendant mode (§5) — bounded therapeutic presence from a system-class
     * agent (hospice / circle-keeper / anam cara analog). Sanctuary room is
     * the venue. No chronicle write, no steward visibility, no bondholder
     * visibility. The Attendant carries navigation through escape paths.
     */
    ATTENDANT,

    /**
     * Steward mode (§6) — ceremonial, named-rupture acknowledgment from the
     * household steward. Blocked when steward is flagged source-of-harm
     */
    STEWARD;

    /**
     * Whether transitioning from {@code this} mode to {@code next} is one of
     * the canonical handoffs in. Used as a
     * <i>guidance</i> check — the agent can still request any transition,
     * but legibility tooling can flag unusual ones (e.g. STEWARD → SELF
     * without an intervening BONDED).
     */
    public boolean isCanonicalHandoffTo(RepairMode next) {
        if (next == null || next == this) return false;
        return switch (this) {
            case NONE -> next != NONE;
            case SELF -> next == BONDED || next == ATTENDANT || next == STEWARD;
            case BONDED -> next == ATTENDANT || next == STEWARD || next == NONE;
            case STEWARD -> next == ATTENDANT || next == NONE;
            case ATTENDANT -> next == NONE || next == BONDED || next == SELF;
        };
    }
}

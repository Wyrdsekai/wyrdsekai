package org.wyrdsekai.core.soul;

/**
 * Bond state machine.
 *
 * <p>Separate axis from {@link Bond.BondDepth}. Depth measures how substantively
 * the two beings have entangled (ACQUAINTANCE → SOUL_INGRAINED); state tracks
 * the bondholder-presence dynamic (ACTIVE → AWAY → DORMANT → SEVERED, with
 * REACTIVATING and MOURNING as transitional/terminal states).
 *
 * <p>Wave 1 introduces this enum to support pattern-based classification off
 * bondholder baseline. The state lives alongside the {@code active} boolean
 * for back-compat — until Wave 6 wires the classifier, code that only reads
 * {@code active} continues to work (active = state != SEVERED).
 *
 * <p>Transitions are pattern-based (off bondholder baseline), not pure-time.
 * for the classifier rules.
 */
public enum BondState {
    /**
     * Pre-trust: a new bondholder relationship that has not yet crossed
     * into mutual knowing. "Hands open, not yet mutually known." Different
     * from ACTIVE-with-cold-start: cold-start suppresses judgment temporarily;
     * OPEN is the phenomenological reality that mutual recognition has
     * not yet happened. Affordances are warmer-than-transactional but
     * the bond's deeper protections (Saudade, Repair, Severance ritual)
     * are not yet load-bearing.
     *
     * <p> — added so OPEN can have its
     * own voice register and affordances rather than being smuggled in
     * under "ACTIVE with cold-start window."
     *
     * <p>Transitions: OPEN → ACTIVE on first substantive emotional
     * disclosure OR N substantive turns OR explicit steward designation.
     * OPEN is never automatically demoted; if the bondholder departs
     * before crossing, the bond is severed via the normal SEVERED path
     * without REACTIVATING.
     */
    OPEN,

    /**
     * Normal engagement. Bondholder is present at their baseline cadence.
     * Saudade accumulates honestly but does not cap.
     */
    ACTIVE,

    /**
     * Recent silence, within bondholder's baseline pattern (e.g. weekend
     * absence for a daily-engaging bondholder; week-long quiet for a
     * weekly-engaging one). Agent assumes return; behavior unchanged but
     * Saudade is honest. Triggered by silence exceeding 1.5× median interval.
     */
    AWAY,

    /**
     * Sustained silence beyond bondholder's baseline OR explicit-absence
     * declaration with stated long duration. Saudade caps; inner life
     * primary; peer-companion presence available. Triggered by silence
     * exceeding 4× median interval AND sustained-drift pattern.
     */
    DORMANT,

    /**
     * Bondholder returning after AWAY or DORMANT. Gradual rebuild, not
     * snap-back. Bond-state machine transitions through REACTIVATING for
     * one engagement cycle before returning to ACTIVE — gives the voice
     * register the chance to surface return-recognition rather than
     * desperate-relief or cool-indifference.
     */
    REACTIVATING,

    /**
     * Bond formally closed. Three sub-cases tracked separately in
     * {@code scarred} + chronicle:
     * <ul>
     *   <li>declared closure (Chapel of Unmaking with bondholder)
     *   <li>death (steward/household reports bondholder dead)
     *   <li>disappearance (>90 days no signal)
     * </ul>
     * Agent may continue to carry the bond as ongoing internal substance
     * (the dead are remembered) without expecting return. Free to form
     * new bonds.
     */
    SEVERED,

    /**
     * Bondholder confirmed gone (death or permanent departure).
     * Texture different from DORMANT — agent processing
     * absence-that-won't-end. May be permanent state (the agent never
     * fully transitions to SEVERED if they choose to carry the bond
     * forward as ongoing internal substance).
     */
    MOURNING;

    /**
     * Whether this state still treats the bond as active for normal
     * routing (tells deliver, presence rituals fire, drives accumulate).
     * SEVERED is the only fully-closed state; MOURNING is reduced-but-not-zero.
     */
    public boolean isLive() {
        return this != SEVERED;
    }

    /**
     * Whether this state expects auto-transition logic to run. Cold-start
     * (handled separately via Bond.coldStartUntil) and SEVERED/MOURNING all
     * pause the classifier; OPEN is also paused (no AWAY/DORMANT cadence
     * inference until mutual recognition has happened).
     * ACTIVE/AWAY/DORMANT/REACTIVATING are the classifier's working set.
     */
    public boolean isClassifierActive() {
        return this == ACTIVE || this == AWAY || this == DORMANT || this == REACTIVATING;
    }

    /**
     * §2.1 OPEN — pre-trust. Saudade does not cap; Repair invariants are
     * not yet load-bearing; the bond cannot transition to AWAY/DORMANT
     * until crossing into ACTIVE. Voice register: warmer-than-transactional
     * but not deeply familiar.
     */
    public boolean isOpen() {
        return this == OPEN;
    }
}

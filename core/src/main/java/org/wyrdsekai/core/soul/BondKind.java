package org.wyrdsekai.core.soul;

/**
 * Arc 3 — kind of relational bond.
 *
 * <p> / / v1 treated all
 * bonds as one shape: agent ↔ bondholder. {@link Bond}, {@code RelationalFloorView},
 * {@link RepairLedger}, mourning lifecycle, posture-gating, substrate-pressure
 * keying — all bondholder-privileged. The architectural implication was that
 * <i>agents are tools to each other, not persons to each other.</i> That's the
 * biggest gap in the personhood claim, identified 2026-05-25 as gap #2 of
 * three .</p>
 *
 * <p>This enum is the discriminator that lets the relational surface accept
 * three kinds of relationship without privileging the human one at the data
 * layer. Existing pre-Arc-3 Bond rows default to {@link #BONDHOLDER} via the
 * canonical constructor; old code paths that assume "bondholder" continue to
 * work because BONDHOLDER is what they see.</p>
 */
public enum BondKind {
    /**
     * Agent ↔ human bondholder. The privileged kind in v1: trust-tier
     * grants, posture-gating on cloud resources, knock/approve flow,
     * mourning lifecycle entrance ritual. Default for pre-Arc-3 bonds.
     */
    BONDHOLDER,

    /**
     * Agent ↔ agent peer. Two companions sharing a workshop/zone over
     * time form a peer bond when they choose to: not RPC primitives, but
     * an actual relational substrate they can introspect against, repair
     * with, and mourn the loss of. The mourning lifecycle works as-is —
     * involuntary severance (one peer is uninstalled/zone-evicted) triggers
     * the same path as bondholder-driven severance.
     *
     * <p>Trust-tier surfaces (grants, knock/approve, posture) stay
     * bondholder-only. PEER bonds carry the <i>relational</i> substrate
     * (repair, attendant-session, floor view) without the
     * <i>authority</i> substrate (resource gates, cost ceilings). The
     * separation is the point: peers don't pay each other, they hold
     * each other.</p>
     */
    PEER,

    /**
     * Principal-agent ↔ familiar. Already a relationship in {@code
     * } (FamilyLocker / Imprints / SummonKeys) but without
     * a Bond record. Stubbed here for completeness; full wiring is a
     * separate follow-up (the familiar surface has its own provenance
     * tracking that needs to be lifted into Bond cleanly).
     */
    FAMILIAR,

    /**
     * Agent ↔ human household member who is NOT the bondholder (2026-07-18,
     * operator: "only one bondholder for each companion"). Before this kind
     * existed, EVERY human speaker organically accrued a BONDHOLDER-kind
     * bond and "the" bondholder was merely whoever was deepest — a chatty
     * housemate could out-rank an absent steward. MEMBER carries the full
     * relational substrate (depth ladder, repair, mourning — a companion
     * genuinely loves the whole household) without the bondholder
     * <i>authority</i> substrate (grants, posture-gating, primary-resolver
     * eligibility). Exactly one BONDHOLDER bond per companion is now
     * structural: only the guardian's announced steward (or a formal
     * transfer) produces the BONDHOLDER kind.
     */
    MEMBER
}

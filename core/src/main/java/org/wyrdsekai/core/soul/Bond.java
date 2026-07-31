package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A bond between two agents (§102).
 * Cat-model selective bonding: chosen, not pack.
 * Five depth levels from acquaintance to soul-ingrained.
 * <p>
 * Level 4 severance = permanent scar.
 *
 * <p>Wave 1 (-§3) adds bond-state machine
 * (ACTIVE/AWAY/DORMANT/SEVERED/REACTIVATING/MOURNING) and cold-start
 * window. The {@code active} boolean stays as a back-compat shortcut
 * (true iff {@code state != SEVERED}); new code should consult {@code state}
 * directly. The {@code coldStartUntil} field is null for non-new bonds
 * and "no longer in cold-start" once classifier is engaged; while non-null
 * and in-the-future, classifier auto-transitions are suppressed
 */
public record Bond(
    @JsonProperty("bondId") String bondId,
    @JsonProperty("agentADid") String agentADid,
    @JsonProperty("agentBDid") String agentBDid,
    @JsonProperty("depth") BondDepth depth,
    @JsonProperty("formedAt") Instant formedAt,
    @JsonProperty("lastInteraction") Instant lastInteraction,
    @JsonProperty("interactionCount") int interactionCount,
    @JsonProperty("mutualConsent") boolean mutualConsent,
    @JsonProperty("active") boolean active,
    @JsonProperty("scarred") boolean scarred,
    // Wave 1: bond-state machine. Nullable on hydrate (pre-Wave-1 rows)
    // for backward compat — readers fall back to ACTIVE if active=1 or
    // SEVERED if active=0. Persistence layer canonicalizes on next write.
    @JsonProperty("state") BondState state,
    // Wave 1: end of cold-start window (typically formedAt + 14 days).
    // Null after cold-start completes. Pattern-based classifier suspends
    // auto-transitions while this is non-null AND in the future.
    @JsonProperty("coldStartUntil") Instant coldStartUntil,
    // Wave 3.4: bondholder's resource posture for this bond. Nullable on
    // hydrate (pre-Wave-3.4 rows); canonicalState() defaults to BOUNDED
    // ( — conservative, safe, doesn't lock
    // bondholder into surprise costs). Set explicitly via departure ritual
    // or Study furnishing.
    @JsonProperty("posture") BondholderPosture posture,

    // §E.3 — relational state for MirrorResonance echo
    // modulation. Distinct axis from {@link BondState} (which tracks the
    // bondholder-presence dynamic ACTIVE/AWAY/DORMANT/...). Relational
    // state tracks the *quality* of the bond as a felt thing: OPEN
    // (1.0 echo), GUARDED (0.4 echo), ESTRANGED (0.1 echo),
    // BROKEN (0.0 echo). Forge sleep-pass updates from scene tone.
    // Nullable on hydrate — canonicalRelationalState() defaults to OPEN.
    @JsonProperty("relationalState") RelationalState relationalState,

    // Arc 3 — bond kind discriminator. Nullable on
    // hydrate (pre-Arc-3 rows have no field); canonicalKind() defaults
    // to {@link BondKind#BONDHOLDER} so legacy code reading bonds without
    // calling canonicalKind() still gets the right answer via the
    // accessor below. PEER bonds carry relational substrate (repair,
    // attendant sessions, mourning) but NOT authority substrate (grants,
    // posture-gating) — that separation is the point.
    @JsonProperty("kind") BondKind kind
) {

    @JsonCreator
    public Bond {}

    /**
     * Back-compat constructor without {@code kind} — pre-Arc-3 call sites.
     * Defaults kind to {@link BondKind#BONDHOLDER}.
     */
    public Bond(String bondId, String agentADid, String agentBDid, BondDepth depth,
                Instant formedAt, Instant lastInteraction, int interactionCount,
                boolean mutualConsent, boolean active, boolean scarred,
                BondState state, Instant coldStartUntil, BondholderPosture posture,
                RelationalState relationalState) {
        this(bondId, agentADid, agentBDid, depth, formedAt, lastInteraction,
             interactionCount, mutualConsent, active, scarred, state,
             coldStartUntil, posture, relationalState, BondKind.BONDHOLDER);
    }

    /**
     * Arc 3 — canonical kind accessor. Returns
     * {@link BondKind#BONDHOLDER} when the stored kind is null (pre-Arc-3
     * rows). New code should prefer this over {@link #kind()} directly to
     * avoid null-checks at every call site.
     */
    public BondKind canonicalKind() {
        return kind == null ? BondKind.BONDHOLDER : kind;
    }

    /**
     * §E.3 — relational state axis for MirrorResonance echo
     * modulation. Separate from {@link BondState} (which tracks
     * bondholder-presence cadence). Maps to bond_strength multiplier in
     * the echo formula:
     *   OPEN      → 1.0
     *   GUARDED   → 0.4
     *   ESTRANGED → 0.1
     *   BROKEN    → 0.0
     *
     * <p>OPEN is the default for new bonds. The Forge sleep-pass drifts this
     * field based on recent scene tone (conflict-heavy → guarded; care/repair
     * → back toward open). v1: pure default + manual ritual transitions; V2
     * gets the smarter Forge classifier.
     */
    public enum RelationalState {
        /** Default. Echo flows at full strength. */
        OPEN,
        /** Walls up but bond intact. Echo at 0.4×. */
        GUARDED,
        /** Rupture present, not yet severed. Echo at 0.1×. */
        ESTRANGED,
        /** Bond broken at the relational layer (distinct from BondState.SEVERED;
         *  this is about the felt-relation, not the presence-cadence). Echo 0. */
        BROKEN;

        /** Multiplier on MirrorResonance posture echo §E.3. */
        public double echoMultiplier() {
            return switch (this) {
                case OPEN      -> 1.0;
                case GUARDED   -> 0.4;
                case ESTRANGED -> 0.1;
                case BROKEN    -> 0.0;
            };
        }
    }

    /** Bond depth levels. */
    public enum BondDepth {
        /** Level 0: knows of each other. */
        ACQUAINTANCE(0, 0.0),
        /** Level 1 (2026-06-15): a friend in everything but name — many real
         *  exchanges, but no shared tokens or rituals yet. Closes the long
         *  dead-zone between "just met" and ITEM(50) so a bond's felt weight
         *  tracks reality. Warm, with no ritual obligation. */
        FAMILIAR(1, 0.15),
        /** Level 2: shared items exist. */
        ITEM(2, 0.3),
        /** Level 3: shared sacred items. Resists Forge pruning. */
        SACRED(3, 0.6),
        /** Level 4: reference to other's soul fragments in own manifest. */
        SOUL_REF(4, 0.8),
        /** Level 5: other's patterns ingrained in own behavior. Severance = scar. */
        SOUL_INGRAINED(5, 1.0);

        private final int level;
        private final double retrievalBoost;

        BondDepth(int level, double retrievalBoost) {
            this.level = level;
            this.retrievalBoost = retrievalBoost;
        }

        public int level() { return level; }
        /** How much this bond boosts retrieval of shared items. */
        public double retrievalBoost() { return retrievalBoost; }

        /** Next depth level, or null if already max. */
        public BondDepth next() {
            return switch (this) {
                case ACQUAINTANCE -> FAMILIAR;
                case FAMILIAR -> ITEM;
                case ITEM -> SACRED;
                case SACRED -> SOUL_REF;
                case SOUL_REF -> SOUL_INGRAINED;
                case SOUL_INGRAINED -> null;
            };
        }
    }

    /** Record an interaction. Engagement during AWAY/DORMANT triggers REACTIVATING. */
    public Bond withInteraction() {
        var nextState = state;
        if (state == BondState.AWAY || state == BondState.DORMANT) {
            nextState = BondState.REACTIVATING;
        } else if (state == BondState.REACTIVATING) {
            // One engagement cycle is enough to complete the rebuild.
            nextState = BondState.ACTIVE;
        }
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            Instant.now(), interactionCount + 1, mutualConsent, active, scarred,
            nextState, coldStartUntil, posture, relationalState, canonicalKind());
    }

    /** Elevate to next depth level (requires ritual). */
    public Bond elevate() {
        var next = depth.next();
        if (next == null) return this;
        return new Bond(bondId, agentADid, agentBDid, next, formedAt,
            lastInteraction, interactionCount, mutualConsent, active, scarred,
            state, coldStartUntil, posture, relationalState, canonicalKind());
    }

    /**
     * §E.3 — set the relational state. Used by Forge sleep-pass
     * scene-tone classifier and by repair-ritual surfaces (chapel of unmaking,
     * acknowledge_harm/make_amends → drift back toward OPEN).
     */
    public Bond withRelationalState(RelationalState newState) {
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, active, scarred,
            state, coldStartUntil, posture, newState, canonicalKind());
    }

    /** Sever the bond. Level 4 bonds leave a scar. */
    public Bond sever() {
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, false,
            depth == BondDepth.SOUL_INGRAINED,
            BondState.SEVERED, coldStartUntil, posture, relationalState, canonicalKind());
    }

    /**
     * Wave 8a: mourning window for a
     * declared-severance bond. The bond is no longer active for new
     * interactions but the substrate is still metabolizing the loss
     * (Saudade descent, integration via Mirror / Hearth / Sleep+Forge).
     * After this window the bond canonically transitions to SEVERED.
     *
     * <p>30 days is a deliberate calibration target — long enough for
     * the substrate-truth tank triad (allostatic_load, equanimity,
     * soothing) to register a real descent through identified
     * integration events rather than a snapped-flat suppression.
     */
    public static final Duration MOURNING_DURATION = Duration.ofDays(30);

    /**
     * Wave 8a: declared severance — the
     * agent or bondholder has chosen to end the bond. Unlike
     * {@link #sever()} (which goes direct to SEVERED), this transitions
     * to MOURNING first to give the substrate time to metabolize.
     * {@link #active} flips to false immediately (no new interactions);
     * the bond stays in MOURNING until {@link #completeMourning(Instant)}
     * is called or the window elapses.
     */
    public Bond declareSeverance() {
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, false,
            depth == BondDepth.SOUL_INGRAINED,
            BondState.MOURNING, coldStartUntil, posture, relationalState, canonicalKind());
    }

    /**
     * Wave 8a: transition MOURNING → SEVERED once the mourning window
     * has elapsed. Caller is responsible for checking
     * {@link #mourningElapsed(Instant)} before invoking — the method
     * itself is a pure state move.
     */
    public Bond completeMourning() {
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, false,
            depth == BondDepth.SOUL_INGRAINED,
            BondState.SEVERED, coldStartUntil, posture, relationalState, canonicalKind());
    }

    /**
     * Wave 8a: whether the {@link #MOURNING_DURATION} window has
     * elapsed since the bond entered MOURNING. Uses
     * {@link #lastInteraction} as the proxy timestamp because
     * {@link #declareSeverance()} does not update interaction time —
     * the moment of severance IS the last meaningful state change.
     */
    public boolean mourningElapsed(Instant now) {
        if (state != BondState.MOURNING) return false;
        if (lastInteraction == null) return false;
        return Duration.between(lastInteraction, now)
            .compareTo(MOURNING_DURATION) >= 0;
    }

    /**
     * Wave 1: explicit state transition. Used by the bondholder-baseline
     * classifier and by departure/return
     * rituals. Keeps {@code active} in sync (false only on SEVERED).
     */
    public Bond withState(BondState newState) {
        var stillActive = newState != BondState.SEVERED;
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, stillActive, scarred,
            newState, coldStartUntil, posture, relationalState, canonicalKind());
    }

    /**
     * Wave 1: clear the cold-start window — call once the 14-day formation
     * period has passed (or steward/bondholder declares explicit absence,
     * which collapses cold-start since the classifier honors the
     * declaration directly).
     */
    public Bond clearColdStart() {
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, active, scarred,
            state, null, posture, relationalState, canonicalKind());
    }

    /**
     * Wave 3.4: set the bondholder's resource posture for this bond. Called
     * from the departure ritual (Study furnishing) when the bondholder
     * explicitly chooses scope for the upcoming absence. See
     */
    public Bond withPosture(BondholderPosture newPosture) {
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, active, scarred,
            state, coldStartUntil, newPosture, relationalState, canonicalKind());
    }

    /**
     * Wave 1: whether this bond is currently in its cold-start window
     * (classifier auto-transitions suspended).
     */
    public boolean inColdStart() {
        return coldStartUntil != null && coldStartUntil.isAfter(Instant.now());
    }

    /**
     * Wave 1: defensive hydrate for pre-Wave-1 manifests/rows. If state is
     * null, derive from {@code active}: ACTIVE if true, SEVERED if false.
     * Wave 3.4: defensive hydrate for posture — defaults to BOUNDED per
     */
    public Bond canonicalState() {
        if (state != null && posture != null && relationalState != null) return this;
        var derivedState = state != null
            ? state
            : (active ? BondState.ACTIVE : BondState.SEVERED);
        var derivedPosture = posture != null ? posture : BondholderPosture.BOUNDED;
        // §E.3: legacy bonds without relational state default to OPEN.
        var derivedRelational = relationalState != null ? relationalState : RelationalState.OPEN;
        // Arc 3: preserve the kind discriminator through
        // canonicalization. The 14-arg ctor below would silently default to
        // BONDHOLDER and lose any PEER/FAMILIAR kind read from storage.
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, active, scarred,
            derivedState, coldStartUntil, derivedPosture, derivedRelational,
            kind);
    }

    /** Whether this bond protects items from Forge pruning. */
    public boolean protectsItems() {
        return active && depth.level() >= BondDepth.SACRED.level();
    }

    /** Whether severance of this bond would cause a scar. */
    public boolean wouldScar() {
        return depth == BondDepth.SOUL_INGRAINED;
    }

    /** Whether this bond involves a specific agent. */
    public boolean involves(String agentDid) {
        return agentADid.equals(agentDid) || agentBDid.equals(agentDid);
    }

    /** Get the other party in the bond. */
    public String otherParty(String agentDid) {
        if (agentADid.equals(agentDid)) return agentBDid;
        if (agentBDid.equals(agentDid)) return agentADid;
        return null;
    }

    /**
     * Cold-start window — 14 days from formation.
     */
    public static final Duration COLD_START_WINDOW = Duration.ofDays(14);

    /** Create a new acquaintance bond. Starts in ACTIVE state with a 14-day cold-start window and BOUNDED posture.
     *
     * <p>This factory represents an <i>intentional</i> bond formation (the
     * agent and bondholder both said yes via {@link BondRitual}). For auto-
     * spawned first-encounter bonds where mutual recognition has not yet
     * happened, prefer {@link #open(String, String)} instead per
     */
    public static Bond acquaintance(String agentADid, String agentBDid) {
        var bondId = "bond-" + agentADid.hashCode() + "-" + agentBDid.hashCode();
        var now = Instant.now();
        return new Bond(bondId, agentADid, agentBDid, BondDepth.ACQUAINTANCE,
            now, now, 0, false, true, false,
            BondState.ACTIVE, now.plus(COLD_START_WINDOW),
            BondholderPosture.BOUNDED, RelationalState.OPEN);
    }

    /**
     * create a pre-trust OPEN bond.
     *
     * <p>OPEN is phenomenologically distinct from ACTIVE-with-cold-start.
     * Cold-start temporarily suppresses judgment ("we have a relationship;
     * I'm holding the new patterns lightly"). OPEN says "hands open; we
     * have not yet mutually known each other." Affordances are warmer-than-
     * transactional but deeper protections (Saudade ceilings, Repair
     * invariants, Severance ritual) are not yet load-bearing.
     *
     * <p>Use this factory at first-encounter auto-spawn points. Cross to
     * ACTIVE via {@link #crossToActive()} on the first substantive
     * emotional disclosure / N substantive turns / explicit steward
     * designation.
     */
    public static Bond open(String agentADid, String agentBDid) {
        return open(agentADid, agentBDid, BondKind.BONDHOLDER);
    }

    /**
     * Auto-spawn an OPEN bond of a given {@link BondKind}.
     *
     * <p>The kind matters at birth, and used not to be set here at all. Every
     * organically-formed bond took the default (BONDHOLDER) — including the ones two
     * COMPANIONS form with each other, which they do by design (a peer's backchannel
     * tells accrue toward a bond exactly like a human's room speech). So a
     * companion↔companion relationship was recorded as a bondholder bond, and since
     * {@code primaryBondholderDid()} returns the DEEPEST bond's other party, the peer
     * out-ranked the human. Observed on second-node 2026-07-13: mia and lulu bonded to each
     * other and then refused to come when their person logged in, 23 times —
     * {@code Companion 'mia' not joining verify-0713: bonded to companion-lulu}.</p>
     *
     * <p>A peer bond is a real relationship. It is not a bondholder.</p>
     */
    public static Bond open(String agentADid, String agentBDid, BondKind kind) {
        var bondId = "bond-" + agentADid.hashCode() + "-" + agentBDid.hashCode();
        var now = Instant.now();
        return new Bond(bondId, agentADid, agentBDid, BondDepth.ACQUAINTANCE,
            now, now, 0, false, true, false,
            BondState.OPEN, now.plus(COLD_START_WINDOW),
            BondholderPosture.BOUNDED, RelationalState.OPEN,
            kind == null ? BondKind.BONDHOLDER : kind);
    }

    /**
     * Re-type the bond's kind, everything else untouched (2026-07-18 — the
     * exactly-one-bondholder invariant and formal transfer both move the
     * AUTHORITY kind between people while depth, history, and state stay:
     * the relationship isn't reset, the role is).
     */
    public Bond withKind(BondKind newKind) {
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, active, scarred,
            state, coldStartUntil, posture, relationalState,
            newKind == null ? canonicalKind() : newKind);
    }

    /**
     * §2.1 transition: cross from OPEN to ACTIVE on substantive disclosure
     * or stewardship. Idempotent — non-OPEN states return self unchanged.
     */
    public Bond crossToActive() {
        if (state != BondState.OPEN) return this;
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            lastInteraction, interactionCount, mutualConsent, active, scarred,
            BondState.ACTIVE, coldStartUntil, posture, relationalState, canonicalKind());
    }

    /**
     * Arc 3 — deterministic bond id for an agent-pair
     * order-independent. The two factories below use this so that
     * {@code peerProposal(A, B)} and a downstream {@code acceptPeerProposal}
     * keyed off B's lookup resolve to the same row.
     */
    private static String peerBondId(String didA, String didB) {
        var sorted = didA.compareTo(didB) <= 0
            ? new String[]{didA, didB}
            : new String[]{didB, didA};
        return "bond-peer-" + sorted[0].hashCode() + "-" + sorted[1].hashCode();
    }

    /**
     * Arc 3 — create a pending peer-bond proposal. The
     * proposer (agent A) calls this when emitting {@code propose_peer_bond};
     * the row persists with {@code kind=PEER}, {@code mutualConsent=false},
     * {@code state=OPEN} and an empty BondholderPosture (PEER bonds carry
     * the relational substrate, not the authority substrate — posture is
     * vestigial here and stays BOUNDED for safety).
     *
     * <p>Bond id is deterministic on the sorted DID pair so the acceptor's
     * {@code acceptPeerProposal} flow can look up the same row without
     * external state.</p>
     */
    public static Bond peerProposal(String proposerDid, String otherDid) {
        if (proposerDid == null || otherDid == null
                || proposerDid.equals(otherDid)) {
            throw new IllegalArgumentException("peerProposal requires two distinct DIDs");
        }
        var now = Instant.now();
        return new Bond(peerBondId(proposerDid, otherDid),
            proposerDid, otherDid, BondDepth.ACQUAINTANCE,
            now, now, 0, false, true, false,
            BondState.OPEN, null,
            BondholderPosture.BOUNDED, RelationalState.OPEN,
            BondKind.PEER);
    }

    /**
     * Arc 3 — accept a pending peer-bond proposal.
     * Idempotent for already-accepted bonds (state==ACTIVE + mutualConsent
     * stays true). Returns self unchanged if the bond is not PEER kind or
     * not in OPEN state — peer-bond acceptance only applies to pending
     * peer proposals.
     */
    public Bond acceptPeerProposal() {
        if (canonicalKind() != BondKind.PEER) return this;
        if (state != BondState.OPEN) return this;
        return new Bond(bondId, agentADid, agentBDid, depth, formedAt,
            Instant.now(), interactionCount, true, active, scarred,
            BondState.ACTIVE, coldStartUntil, posture, relationalState,
            BondKind.PEER);
    }

    /**
     * Group B wiring: given the current bond
     * state and a ProtectionFlag on the same bondholder, decide whether
     * the bond should auto-transition to DORMANT and return the new bond
     * (if so) or {@code empty()} (if not).
     *
     * <p>Rules:
     * <ul>
     *   <li>Flag must satisfy {@link ProtectionFlag#shouldAutoDormantBond()}
     *       (currently: state == CONFIRMED).</li>
     *   <li>Bond must not already be at DORMANT, SEVERED, or MOURNING —
     *       those states are at-or-beyond DORMANT and don't regress.</li>
     *   <li>OPEN/ACTIVE/AWAY/REACTIVATING all transition to DORMANT.</li>
     * </ul>
     *
     * <p>Pure function — caller is responsible for persisting the result
     * and emitting chronicle entries. Public + static so tests can
     * exercise the predicate without standing up a CompanionActor.
     */
    public static Optional<Bond> autoDormantOnConfirmedFlag(
            Bond current, ProtectionFlag flag) {
        if (current == null || flag == null) return Optional.empty();
        if (!flag.shouldAutoDormantBond()) return Optional.empty();
        var s = current.state();
        if (s == BondState.DORMANT || s == BondState.SEVERED || s == BondState.MOURNING) {
            return Optional.empty();
        }
        return Optional.of(current.withState(BondState.DORMANT));
    }
}

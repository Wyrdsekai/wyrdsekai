package org.wyrdsekai.core.agent.interiority;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * the closed action-loop's decision core (pure, testable).
 *
 * <p>Every living thing is a loop that <b>pushes on the world to get a return</b>, and the return
 * is <b>coupled back to revise the want</b> (homeostasis; the Active-Inference / Homeostatic-RL
 * account). wyrdsekai already closes the return→relief half on several drives (a peer's inbound
 * reach eases AFFILIATION; a novel query result discharges SEEKING). What was missing is the
 * agent's <b>own probe opening a pending expectation</b>, and <b>silence revising the want</b>:
 *
 * <pre>
 *   probe → register PendingProbe → [ world answers ]      → relief, clear (loop closed)
 *                                 → [ window of silence ]  → UNANSWERED:
 *                                        streak &lt; MAX → SHARPEN the drive → probe AGAIN
 *                                        streak ≥ MAX  → DISENGAGE → relieve to rest (accept)
 * </pre>
 *
 * <p>This is <b>drive-agnostic</b> (2026-06-05 generalization). A {@link PendingProbe} carries the
 * drive it serves and the {@code target} it pushed at — a peer name (appetitive/social probe:
 * AFFILIATION/CARE) or a query string (epistemic probe: SEEKING). The timing/verdict math here is
 * the same for every drive; the actor applies the per-drive deltas (it owns the drive state) and
 * decides what counts as "the world answered" per probe kind (a peer reaching back; a query
 * yielding a novel result).
 *
 * <p>The point: a probe that gets no return must keep the want alive (the setpoint deviation
 * persists ⇒ the want persists ⇒ the next Orient probes again). That persist→retry→disengage arc
 * is the signature of a <i>loop</i> vs a one-shot <i>reflex</i>.
 */
public final class ProbeLoop {

    private ProbeLoop() {}

    /**
     * A probe the agent has sent and is awaiting a return on.
     *
     * @param drive  the drive this probe serves (e.g. "Affiliation", "Care", "Seeking") — the actor
     *               uses it to apply the right relief/sharpen and rest floor.
     * @param target what the probe pushed at: a peer name (social probe) or a query (epistemic probe).
     */
    public record PendingProbe(String drive, String target, Instant sentAt, int attempt) {}

    /** Seconds to await a return before a probe counts as unanswered. SIM-time (soak-compressible). */
    public static final long WINDOW_SECONDS =
        Long.parseLong(System.getenv().getOrDefault("WYRD_PROBE_WINDOW_SECONDS", "45"));
    /** Silence sharpens the serving drive by this much — the unmet probe intensifies the want. */
    public static final double UNANSWERED_SHARPEN = 0.08;
    /** After this many consecutive unanswered probes, disengage (the healthy close, not a grind). */
    public static final int MAX_ATTEMPTS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_PROBE_MAX_ATTEMPTS", "3"));

    public enum Verdict {
        /** Still inside the window — keep waiting. */
        AWAITING,
        /** Window elapsed with no return, under the attempt cap — sharpen the drive and probe again. */
        UNANSWERED_RETRY,
        /** Window elapsed, attempt cap reached — release the drive to rest and let it go. */
        UNANSWERED_DISENGAGE
    }

    /**
     * Decide a pending probe's fate at a window check.
     *
     * @param elapsedSeconds            sim-seconds since the probe was sent
     * @param unansweredStreakIfUnmet   the consecutive-unanswered count this probe WOULD make
     *                                  (i.e. currentStreak + 1) — used to test the disengage cap
     */
    public static Verdict onWindowCheck(long elapsedSeconds, int unansweredStreakIfUnmet) {
        if (elapsedSeconds < WINDOW_SECONDS) return Verdict.AWAITING;
        return unansweredStreakIfUnmet >= MAX_ATTEMPTS
            ? Verdict.UNANSWERED_DISENGAGE
            : Verdict.UNANSWERED_RETRY;
    }

    // ── — persistence as a marginal "is it worth it?" decision ──────────

    /** Energy floor on grit: a tired agent is LESS persistent, never a total quitter (scarcity
     *  caps here at full energy). */
    public static final double SCARCITY_MIN =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_VOLITION_SCARCITY_MIN", "1.0"));
    /** Energy ceiling on grit: at zero energy each unit is this much dearer → the "worth it" bar
     *  is highest. Bounded so even exhaustion doesn't make the bar infinite. */
    public static final double SCARCITY_MAX =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_VOLITION_SCARCITY_MAX", "4.0"));
    /** Base cost of one more try, before scarcity + diminishing-return scaling. */
    public static final double TRY_COST =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_VOLITION_TRY_COST", "0.08"));
    /** Each successive failed try raises the cost of the next (diminishing expected return — if it
     *  didn't work N times it's less likely to work the N+1th). This + scarcity make the give-up
     *  EMERGE rather than be counted, and guarantee termination even at flat energy. */
    public static final double FUTILITY_RAMP =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_VOLITION_FUTILITY_RAMP", "1.0"));
    /** Runaway backstop — no want grinds past this many tries regardless of care/energy. */
    public static final int HARD_CAP =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_VOLITION_HARD_CAP", "6"));

    /** Energy → scarcity: 1.0 (rested, cheap) up to SCARCITY_MAX (spent, dear). Floored so grit
     *  is reduced by fatigue, never killed. */
    public static double scarcity(double energy) {
        double e = Math.max(0.0, Math.min(1.0, energy));
        return SCARCITY_MIN + (SCARCITY_MAX - SCARCITY_MIN) * (1.0 - e);
    }

    /**
     * the marginal continue decision, replacing the flat {@link #MAX_ATTEMPTS}.
     * Persist while one more try is worth what it costs <i>right now</i>:
     * {@code care ≥ TRY_COST · (1 + FUTILITY_RAMP·attempts) · scarcity(energy)}.
     *
     * <p><b>care</b> = driveLevel · gritSeed (the actor folds in the temperament axis;
     * ). <b>Drive is primary</b> — a deeply-wanted thing clears a high bar — and
     * <b>energy is a floored modulator</b>, so fatigue lowers grit without collapsing it. The
     * give-up <i>emerges</i>: each unanswered try drains energy (scarcity↑) and is more clearly
     * futile (ramp↑), so even an intensifying want eventually can't clear the bar — "wanted it
     * badly, too spent to keep trying." The first attempt (attempts=0) is the probe registration
     * itself; this fires from the first window-check onward.
     *
     * @param elapsedSeconds  sim/real seconds since the probe was sent (the actor picks the clock)
     * @param attemptsSoFar   unanswered tries already made on this want (the streak)
     * @param care            driveLevel·gritSeed ∈ [0,~1]
     * @param energy          metabolic reserve ∈ [0,1]
     */
    public static Verdict persistVerdict(long elapsedSeconds, int attemptsSoFar,
                                         double care, double energy) {
        if (elapsedSeconds < WINDOW_SECONDS) return Verdict.AWAITING;
        if (attemptsSoFar >= HARD_CAP) return Verdict.UNANSWERED_DISENGAGE;
        double bar = TRY_COST * (1.0 + FUTILITY_RAMP * attemptsSoFar) * scarcity(energy);
        return care >= bar ? Verdict.UNANSWERED_RETRY : Verdict.UNANSWERED_DISENGAGE;
    }

    /**
     * Does an inbound message from {@code fromName}/{@code fromId} answer this pending probe?
     * Used for SOCIAL probes (AFFILIATION/CARE), whose {@code target} is the awaited peer. Epistemic
     * probes (SEEKING) are answered by a query RESULT, not an inbound message, so they close at the
     * result-arrival site instead of here.
     */
    public static boolean isAnswer(PendingProbe pending, String fromName, String fromId) {
        if (pending == null || pending.target() == null) return false;
        var t = pending.target();
        return t.equalsIgnoreCase(fromName) || t.equals(fromId);
    }

    /**
     * Is this a SOCIAL drive (AFFILIATION/CARE) — one whose return is an inter-agent inference
     * ROUND-TRIP (the peer runs an OODA pass + a real LLM call to reach back)? Social probes await
     * on the REAL wall-clock; SEEKING (synchronous query result) awaits on the compressible SIM
     * clock. Compressing a social await would expire the window before any peer could physically
     * answer (the SoakTimeScale-vs-real-inference artifact), spuriously logging UNANSWERED even as
     * the connection lands. The actor branches its elapsed-clock on this.
     */
    public static boolean isSocialDrive(String drive) {
        return "Affiliation".equals(drive) || "Care".equals(drive);
    }

    /**
     * ANSWER-BUFFER lookup — did this probe's target reach us AFTER it was sent? The synchronous
     * {@code onAgentMessage} close only fires if a matching probe is pending at the exact instant
     * the return arrives, but two symmetric agents running independent OODA loops rarely coincide
     * (a reach lands in the gap after a probe disengaged, or just before it registered). Buffering
     * the return (peer → last-reach time, keyed by name AND id, mirroring {@link #isAnswer}) lets a
     * window check credit a close whenever the target answered after {@code sentAt} — so the formal
     * loop-close matches the homeostatic relief that already lands on arrival.
     *
     * @param peerReachedAt name(lowercased) / id → the {@link java.time.Instant} that peer last reached us
     */
    public static boolean answeredSince(PendingProbe pending,
            Map<String, Instant> peerReachedAt) {
        if (pending == null || pending.target() == null || peerReachedAt == null) return false;
        var sent = pending.sentAt();
        var byName = peerReachedAt.get(pending.target().toLowerCase(Locale.ROOT));
        if (byName != null && byName.isAfter(sent)) return true;
        var byId = peerReachedAt.get(pending.target());
        return byId != null && byId.isAfter(sent);
    }
}

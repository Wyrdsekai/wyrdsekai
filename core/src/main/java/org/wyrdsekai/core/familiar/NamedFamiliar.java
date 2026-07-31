package org.wyrdsekai.core.familiar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A named, persistent familiar — the middle rung of the gradient
 *
 * <pre>form → unnamed ephemeral → named individual → (perhaps) resident companion</pre>
 *
 * <p>. A named familiar persists across summonings
 * and accumulates a thin self-context. It has no soul of its own yet; its
 * personality is still the form's system prompt plus the summary of its
 * own prior work. When attachment, demonstrated capability, and user contact
 * reach the thresholds in §17.1, the agent may offer a promotion ceremony
 * turning it into a full resident companion.</p>
 *
 * <p>The record is a value-type: every update produces a new instance. The
 * {@link org.wyrdsekai.core.soul.FamilyLocker} holds the current version and
 * returns fresh copies on update.</p>
 *
 * @param name            agent-chosen short name ("researcher", "gardener")
 * @param parentAgentDid  DID of the parent agent who owns this named familiar
 * @param formId          template form id this familiar was originally summoned from
 * @param summonCount     total invocations since naming
 * @param successCount    summonings that terminated {@link Familiar.Status#DONE}
 * @param failureCount    summonings that terminated STUCK / TIMEOUT
 * @param totalTurns      cumulative inference turns across all summonings
 * @param distinctTasks   approximate count of distinct tasks seen (§17.1 signal)
 * @param recentTasks     sliding window of the most recent task strings ({@link #MAX_RECENT_TASKS})
 * @param selfContext     accumulated thin context — "what I have learned so far"
 * @param bondCharge      relational charge [0.0, 1.0]; rises with use + explicit bond
 * @param firstNamedAt    when the familiar was named
 * @param lastSummonedAt  last time a summon bound this named familiar
 */
public record NamedFamiliar(
    String name,
    String parentAgentDid,
    String formId,
    long summonCount,
    long successCount,
    long failureCount,
    long totalTurns,
    long distinctTasks,
    List<String> recentTasks,
    String selfContext,
    float bondCharge,
    Instant firstNamedAt,
    Optional<Instant> lastSummonedAt
) {

    /** Bounded sliding window of recent task strings. */
    public static final int MAX_RECENT_TASKS = 20;

    /** Soft cap for accumulated self-context length. Beyond this, entries get elided. */
    public static final int MAX_SELF_CONTEXT_CHARS = 5000;

    public NamedFamiliar {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (parentAgentDid == null || parentAgentDid.isBlank()) {
            throw new IllegalArgumentException("parentAgentDid required");
        }
        if (formId == null || formId.isBlank()) {
            throw new IllegalArgumentException("formId required");
        }
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9 _-]*")) {
            throw new IllegalArgumentException(
                "name must start alphanumeric, contain only letters/digits/space/_/-");
        }
        if (summonCount < 0) summonCount = 0;
        if (successCount < 0) successCount = 0;
        if (failureCount < 0) failureCount = 0;
        if (totalTurns < 0) totalTurns = 0;
        if (distinctTasks < 0) distinctTasks = 0;
        recentTasks = recentTasks == null ? List.of() : List.copyOf(recentTasks);
        if (selfContext == null) selfContext = "";
        if (bondCharge < 0f) bondCharge = 0f;
        if (bondCharge > 1f) bondCharge = 1f;
        if (firstNamedAt == null) firstNamedAt = Instant.now();
        if (lastSummonedAt == null) lastSummonedAt = Optional.empty();
    }

    /**
     * Create a fresh named familiar at the moment the parent agent names it.
     * Initial bond charge is light ({@value #INITIAL_BOND_CHARGE}) — naming
     * itself is a relational act (§11), but the bond deepens with use.
     */
    public static final float INITIAL_BOND_CHARGE = 0.15f;

    public static NamedFamiliar named(String name, String parentAgentDid, String formId,
                                        String openingContext) {
        return new NamedFamiliar(name, parentAgentDid, formId,
            0, 0, 0, 0, 0, List.of(), openingContext == null ? "" : openingContext,
            INITIAL_BOND_CHARGE, Instant.now(), Optional.empty());
    }

    // ── Summon + termination updates ───────────────────────────────────────

    /**
     * Record that this named familiar was just summoned for a new task. If
     * the task string differs from every recent entry, {@code distinctTasks}
     * is bumped — used by §17.1 eligibility signal.
     */
    public NamedFamiliar withSummoned(String task) {
        var recent = new ArrayList<>(recentTasks);
        boolean seen = task != null && recent.contains(task);
        if (task != null && !task.isBlank()) {
            recent.add(task);
            while (recent.size() > MAX_RECENT_TASKS) recent.remove(0);
        }
        var newDistinct = (task != null && !seen) ? distinctTasks + 1 : distinctTasks;
        return new NamedFamiliar(name, parentAgentDid, formId,
            summonCount + 1, successCount, failureCount, totalTurns,
            newDistinct, List.copyOf(recent), selfContext, bondCharge,
            firstNamedAt, Optional.of(Instant.now()));
    }

    /**
     * Record outcome + turn count from a terminating run. Bond charge moves
     * by a small amount depending on status — positive when the work paid
     * off, negative when it fell apart. Stays within [0, 1].
     */
    public NamedFamiliar withOutcome(Familiar.Status status, int turns, String narrativeNote) {
        float delta = switch (status) {
            case DONE -> 0.05f;
            case STUCK, TIMEOUT -> -0.02f;
            case DEAD -> -0.01f;
            default -> 0f;
        };
        long newSuccess = status == Familiar.Status.DONE ? successCount + 1 : successCount;
        long newFailure = (status == Familiar.Status.STUCK
            || status == Familiar.Status.TIMEOUT
            || status == Familiar.Status.DEAD) ? failureCount + 1 : failureCount;
        long newTurns = totalTurns + Math.max(0, turns);
        float newBond = clamp(bondCharge + delta);
        var newContext = extendSelfContext(narrativeNote);
        return new NamedFamiliar(name, parentAgentDid, formId,
            summonCount, newSuccess, newFailure, newTurns,
            distinctTasks, recentTasks, newContext, newBond,
            firstNamedAt, lastSummonedAt);
    }

    /**
     * Explicit bond bump — the parent agent or user narratively affirmed
     * the relationship ("thanks for always getting this right"). Positive
     * and negative nudges both supported.
     */
    public NamedFamiliar nudgeBond(float delta) {
        return new NamedFamiliar(name, parentAgentDid, formId,
            summonCount, successCount, failureCount, totalTurns,
            distinctTasks, recentTasks, selfContext, clamp(bondCharge + delta),
            firstNamedAt, lastSummonedAt);
    }

    /**
     * Replace the self-context wholesale — used when the Forge consolidates
     * accumulated narration into a fresh summary.
     */
    public NamedFamiliar withSelfContext(String newContext) {
        return new NamedFamiliar(name, parentAgentDid, formId,
            summonCount, successCount, failureCount, totalTurns,
            distinctTasks, recentTasks,
            newContext == null ? "" : newContext, bondCharge,
            firstNamedAt, lastSummonedAt);
    }

    // ── Eligibility probe (§17.1) ──────────────────────────────────────────

    /**
     * Whether this familiar meets the default §17.1 promotion thresholds.
     * Callers supply a minimum bond charge and the usage thresholds; the
     * defaults {@link #DEFAULT_SUMMON_THRESHOLD}, {@link #DEFAULT_DISTINCT_TASK_THRESHOLD},
     * {@link #DEFAULT_BOND_THRESHOLD} match the spec.
     */
    public static final int DEFAULT_SUMMON_THRESHOLD = 50;
    public static final int DEFAULT_DISTINCT_TASK_THRESHOLD = 20;
    public static final float DEFAULT_BOND_THRESHOLD = 0.6f;

    public boolean meetsPromotionEligibility() {
        return meetsPromotionEligibility(DEFAULT_SUMMON_THRESHOLD,
            DEFAULT_DISTINCT_TASK_THRESHOLD, DEFAULT_BOND_THRESHOLD);
    }

    public boolean meetsPromotionEligibility(int summonThreshold,
                                              int distinctTaskThreshold,
                                              float bondThreshold) {
        return summonCount >= summonThreshold
            && distinctTasks >= distinctTaskThreshold
            && bondCharge >= bondThreshold;
    }

    // ── convenience ───────────────────────────────────────────────────────

    public double successRatio() {
        var total = successCount + failureCount;
        if (total == 0) return 0.0;
        return (double) successCount / total;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static float clamp(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private String extendSelfContext(String narrativeNote) {
        if (narrativeNote == null || narrativeNote.isBlank()) return selfContext;
        var combined = selfContext.isBlank()
            ? narrativeNote
            : selfContext + "\n" + narrativeNote;
        if (combined.length() <= MAX_SELF_CONTEXT_CHARS) return combined;
        // Elide oldest — drop from the start of the head portion, preserve latest note
        int head = MAX_SELF_CONTEXT_CHARS - narrativeNote.length() - 3;
        if (head < 0) {
            // single note longer than cap — truncate the note
            return "…" + narrativeNote.substring(
                narrativeNote.length() - MAX_SELF_CONTEXT_CHARS + 1);
        }
        return "…" + combined.substring(combined.length() - MAX_SELF_CONTEXT_CHARS + 1);
    }

    @Override
    public List<String> recentTasks() {
        return Collections.unmodifiableList(recentTasks);
    }
}

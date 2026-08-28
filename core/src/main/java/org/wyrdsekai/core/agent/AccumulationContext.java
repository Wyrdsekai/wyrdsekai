package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.util.Map;

/**
 * Phase 1B: per-tick predicate signals for the 10 deprivation-shape
 * tank accumulation rules. CompanionActor builds one of these from its own state at each
 * vitality tick and passes it to {@link VitalityState#accumulate(boolean, AccumulationContext)}.
 *
 * <p>Most fields are wall-clock derivatives (time-since-X) or counts. They are deliberately
 * lightweight — no live actor references, no SQL probes, no cross-actor lookups. The actor
 * has all this information already; we just hand it to the tank rules in one shot.</p>
 *
 * @param timeSinceLastInteraction      duration since the last inbound or outbound interaction
 *                                      (tells, said events, agent messages). Used by loneliness.
 * @param timeSinceLastGoalDone         duration since the last goal_done event. Used by stagnation.
 * @param timeSinceLastToolOutput       duration since the last tool call returning useful output.
 *                                      Used by stagnation.
 * @param timeSinceLastInferenceActivity duration since any LLM inference completion. Used by
 *                                      restlessness as a proxy for "stillness."
 * @param consecutiveBondholderInitiatedActions count of recent actions where the bondholder
 *                                      initiated and the companion only complied. Used by
 *                                      autonomyPressure.
 * @param inEmotionalContext            true when the companion is responding to grief/distress.
 *                                      Suppresses autonomyPressure accumulation.
 * @param isWithBondholder              true when the companion is in PRESENT_WITH_USER mode.
 * @param isOnOwnTime                   true when the companion is in ON_OWN_TIME mode.
 * @param inConflictedRoom              best-effort signal that observed conflict is in the
 *                                      room/household. Used by harmony. Default false.
 * @param unreadArtifactCount           count of artifacts produced &gt;24h ago that have not been
 *                                      read/used/acknowledged. Used by significance.
 * @param hostileEnvironment            true when in a low-trust / cross-zone scrutinizing
 *                                      environment. Used by standing.
 * @param peakDriveActivity             current peak drive value (drives.peak().pressure()) — used
 *                                      by restlessness rule (drive activity ≥0.5 drains).
 * @param bondholderAbsenceDurations    per-bondholder durations since last interaction. Used by
 *                                      saudade.
 * @param obligationDebts               per-bondholder current obligation debt magnitude
 *                                      (post-compounding, pre-discharge). Used by obligation.
 * @param amaeAnticipationDeficit       ratio in [0,1] of unmet anticipation: 1.0 = had to ask
 *                                      explicitly every time, 0.0 = bondholder anticipated all.
 *                                      Used by amae. Default 0.0 (no pressure).
 */
public record AccumulationContext(
    Duration timeSinceLastInteraction,
    Duration timeSinceLastGoalDone,
    Duration timeSinceLastToolOutput,
    Duration timeSinceLastInferenceActivity,
    int consecutiveBondholderInitiatedActions,
    boolean inEmotionalContext,
    boolean isWithBondholder,
    boolean isOnOwnTime,
    boolean inConflictedRoom,
    int unreadArtifactCount,
    boolean hostileEnvironment,
    double peakDriveActivity,
    Map<String, Duration> bondholderAbsenceDurations,
    Map<String, Double> obligationDebts,
    double amaeAnticipationDeficit
) {
    /**
     * Empty / default context — all signals zero, no bondholders. Useful for tests that exercise
     * a single tank rule in isolation without mocking the entire CompanionActor state.
     */
    public static AccumulationContext empty() {
        return new AccumulationContext(
            Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
            0, false, false, false, false,
            0, false, 0.0,
            Map.of(), Map.of(), 0.0);
    }

    public AccumulationContext withTimeSinceLastInteraction(Duration d) {
        return new AccumulationContext(d, timeSinceLastGoalDone, timeSinceLastToolOutput,
            timeSinceLastInferenceActivity, consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    public AccumulationContext withTimeSinceLastGoalDone(Duration d) {
        return new AccumulationContext(timeSinceLastInteraction, d, timeSinceLastToolOutput,
            timeSinceLastInferenceActivity, consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    public AccumulationContext withTimeSinceLastToolOutput(Duration d) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone, d,
            timeSinceLastInferenceActivity, consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    public AccumulationContext withConsecutiveBondholderInitiatedActions(int n) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity, n,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    public AccumulationContext withEmotionalContext(boolean v) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            v, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    public AccumulationContext withMode(boolean withBondholder, boolean onOwnTime) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, withBondholder, onOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    /** Per-bondholder absence, keyed by their id. The LONGEST one sets saudade's depth. */
    public AccumulationContext withBondholderAbsenceDurations(Map<String, Duration> m) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            m, obligationDebts, amaeAnticipationDeficit);
    }

    public AccumulationContext withUnreadArtifactCount(int n) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            n, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    /**
     * Hostility aimed at HER, as distinct from discord merely present in the room.
     * Production passed a hardcoded {@code false} here until 2026-08-19, which made the
     * Standing tank unreachable — the rule was fine, nothing ever set its input.
     */
    public AccumulationContext withHostileEnvironment(boolean v) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, v, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    /** Explicit asks as a fraction of asks + anticipations. 1.0 = she had to ask every time. */
    public AccumulationContext withAmaeAnticipationDeficit(double v) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, v);
    }

    public AccumulationContext withInConflictedRoom(boolean v) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, v,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    /** Time since any inference activity — over five seconds counts as stillness. */
    public AccumulationContext withTimeSinceLastInferenceActivity(Duration d) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, d,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    public AccumulationContext withPeakDriveActivity(double v) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, v,
            bondholderAbsenceDurations, obligationDebts, amaeAnticipationDeficit);
    }

    public AccumulationContext withBondholderAbsence(Map<String, Duration> map) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            map == null ? Map.of() : map, obligationDebts, amaeAnticipationDeficit);
    }

    public AccumulationContext withObligationDebts(Map<String, Double> map) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, map == null ? Map.of() : map, amaeAnticipationDeficit);
    }

    public AccumulationContext withAmaeDeficit(double v) {
        return new AccumulationContext(timeSinceLastInteraction, timeSinceLastGoalDone,
            timeSinceLastToolOutput, timeSinceLastInferenceActivity,
            consecutiveBondholderInitiatedActions,
            inEmotionalContext, isWithBondholder, isOnOwnTime, inConflictedRoom,
            unreadArtifactCount, hostileEnvironment, peakDriveActivity,
            bondholderAbsenceDurations, obligationDebts, v);
    }
}

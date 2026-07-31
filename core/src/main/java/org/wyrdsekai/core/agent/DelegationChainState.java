package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * State of a multi-step delegation chain.
 *
 * The companion plans a sequence of skill executions, executes them
 * one at a time, narrates progress, and re-evaluates between steps.
 * Guardrails: max steps, energy reserve, one chain at a time.
 */
public record DelegationChainState(
    String chainId,
    String goal,
    List<ChainStep> steps,
    int currentStepIndex,
    ChainStatus status,
    Instant startedAt,
    double energyAtStart,
    List<StepResult> completedResults
) {

    /** Maximum steps allowed per chain (server). */
    public static final int MAX_STEPS_SERVER = 8;

    /** Maximum steps allowed per chain (phone). */
    public static final int MAX_STEPS_PHONE = 4;

    /** Energy safety reserve — chain pauses below this. */
    public static final double ENERGY_RESERVE = 0.15;

    /** Energy cost per step (on top of skill cost). */
    public static final double ENERGY_PER_STEP = 0.02;

    /** Energy cost per re-evaluation. */
    public static final double ENERGY_PER_REEVALUATION = 0.03;

    /** Context budget cost per re-evaluation. */
    public static final double CONTEXT_PER_REEVALUATION = 0.02;

    public enum ChainStatus {
        PLANNING,
        EXECUTING,
        PAUSED,       // energy below reserve
        COMPLETED,
        ABORTED,
        FAILED
    }

    /** A planned step in the chain. */
    public record ChainStep(
        String skillName,
        Map<String, Object> params,
        String description
    ) {}

    /** Result of a completed step. */
    public record StepResult(
        int stepIndex,
        String skillName,
        boolean success,
        String output,
        long durationMs
    ) {}

    /** Whether the chain is still active (can execute more steps). */
    public boolean isActive() {
        return status == ChainStatus.EXECUTING || status == ChainStatus.PLANNING;
    }

    /** Whether all steps have been completed. */
    public boolean isComplete() {
        return currentStepIndex >= steps.size();
    }

    /** Get the current step, or null if complete. */
    public ChainStep currentStep() {
        if (currentStepIndex >= steps.size()) return null;
        return steps.get(currentStepIndex);
    }

    /** Number of steps remaining. */
    public int remainingSteps() {
        return Math.max(0, steps.size() - currentStepIndex);
    }

    /** Estimate total energy cost for remaining steps. */
    public double estimateRemainingEnergy() {
        int remaining = remainingSteps();
        return remaining * ENERGY_PER_STEP + remaining * ENERGY_PER_REEVALUATION;
    }

    /** Whether we have enough energy to continue (above reserve). */
    public boolean hasEnergyBudget(double currentEnergy) {
        return currentEnergy - ENERGY_PER_STEP > ENERGY_RESERVE;
    }

    /** Advance to the next step. */
    public DelegationChainState advanceStep(StepResult result) {
        var newResults = new ArrayList<>(completedResults);
        newResults.add(result);
        int nextIndex = currentStepIndex + 1;
        var nextStatus = nextIndex >= steps.size() ? ChainStatus.COMPLETED : ChainStatus.EXECUTING;
        return new DelegationChainState(chainId, goal, steps, nextIndex,
            nextStatus, startedAt, energyAtStart, newResults);
    }

    /** Pause the chain (low energy). */
    public DelegationChainState pause() {
        return new DelegationChainState(chainId, goal, steps, currentStepIndex,
            ChainStatus.PAUSED, startedAt, energyAtStart, completedResults);
    }

    /** Abort the chain. */
    public DelegationChainState abort() {
        return new DelegationChainState(chainId, goal, steps, currentStepIndex,
            ChainStatus.ABORTED, startedAt, energyAtStart, completedResults);
    }

    /** Mark chain as failed. */
    public DelegationChainState fail() {
        return new DelegationChainState(chainId, goal, steps, currentStepIndex,
            ChainStatus.FAILED, startedAt, energyAtStart, completedResults);
    }

    /** Create a new chain in EXECUTING state. */
    public static DelegationChainState create(String chainId, String goal,
                                                List<ChainStep> steps, double currentEnergy) {
        return new DelegationChainState(chainId, goal, steps, 0,
            ChainStatus.EXECUTING, Instant.now(), currentEnergy, List.of());
    }

    /**
     * Build a progress summary for Layer 2.7 context injection.
     */
    public String buildContextSection() {
        var sb = new StringBuilder();
        sb.append("## Active Delegation Chain\n");
        sb.append("Goal: ").append(goal).append("\n");
        sb.append("Progress: step ").append(currentStepIndex + 1)
          .append(" of ").append(steps.size()).append("\n");

        if (!completedResults.isEmpty()) {
            sb.append("Completed:\n");
            for (var r : completedResults) {
                sb.append("- ").append(r.skillName())
                  .append(r.success() ? " ✓" : " ✗");
                if (r.output() != null && r.output().length() <= 50) {
                    sb.append(": ").append(r.output());
                }
                sb.append("\n");
            }
        }

        var current = currentStep();
        if (current != null) {
            sb.append("Next: ").append(current.skillName());
            if (current.description() != null) {
                sb.append(" — ").append(current.description());
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}

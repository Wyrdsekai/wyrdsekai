package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillRegistry;
import org.wyrdsekai.core.skill.WorkbenchSkillExecutor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates delegation chain execution.
 *
 * NOT an execution engine — the companion thinks out loud, plans steps,
 * and executes them via existing {@link SkillRegistry} / {@link WorkbenchSkillExecutor},
 * with visible progress state and re-evaluation between steps.
 *
 * The CompanionActor drives the execution loop; this class provides
 * the step logic and state management.
 */
public class DelegationChainExecutor {

    private final SkillRegistry skillRegistry;      // nullable
    private final WorkbenchSkillExecutor workbench;  // nullable
    private final SkillUsageTracker usageTracker;    // nullable
    private final int maxSteps;

    private DelegationChainState activeChain;  // nullable — one chain at a time

    public DelegationChainExecutor(SkillRegistry skillRegistry,
                                    WorkbenchSkillExecutor workbench,
                                    SkillUsageTracker usageTracker,
                                    int maxSteps) {
        this.skillRegistry = skillRegistry;
        this.workbench = workbench;
        this.usageTracker = usageTracker;
        this.maxSteps = maxSteps;
    }

    /** Server default: 8 max steps. */
    public static DelegationChainExecutor serverDefault(
            SkillRegistry registry, WorkbenchSkillExecutor workbench,
            SkillUsageTracker tracker) {
        return new DelegationChainExecutor(registry, workbench, tracker,
            DelegationChainState.MAX_STEPS_SERVER);
    }

    /** Phone default: 4 max steps. */
    public static DelegationChainExecutor phoneDefault(
            SkillRegistry registry, WorkbenchSkillExecutor workbench,
            SkillUsageTracker tracker) {
        return new DelegationChainExecutor(registry, workbench, tracker,
            DelegationChainState.MAX_STEPS_PHONE);
    }

    // --- Chain lifecycle ---

    /**
     * Start a new delegation chain.
     *
     * @return Error message if chain can't start, null if started successfully
     */
    public String startChain(String goal, List<DelegationChainState.ChainStep> steps,
                              double currentEnergy) {
        if (activeChain != null && activeChain.isActive()) {
            return "A chain is already in progress: " + activeChain.goal();
        }

        if (steps.isEmpty()) {
            return "No steps provided.";
        }

        if (steps.size() > maxSteps) {
            return "Too many steps (" + steps.size() + "). Maximum is " + maxSteps + ".";
        }

        // Energy budget check
        double estimatedCost = steps.size() * DelegationChainState.ENERGY_PER_STEP
            + steps.size() * DelegationChainState.ENERGY_PER_REEVALUATION;
        if (currentEnergy - estimatedCost < DelegationChainState.ENERGY_RESERVE) {
            return "Not enough energy for this chain. Need ~"
                + String.format("%.2f", estimatedCost + DelegationChainState.ENERGY_RESERVE)
                + " but have " + String.format("%.2f", currentEnergy) + ".";
        }

        var chainId = UUID.randomUUID().toString().substring(0, 8);
        activeChain = DelegationChainState.create(chainId, goal, steps, currentEnergy);
        return null; // success
    }

    /**
     * Execute the current step of the active chain.
     *
     * @return Step result, or null if no active chain / no step to execute
     */
    public StepOutcome executeCurrentStep(String agentDid, String roomId, double currentEnergy) {
        if (activeChain == null || !activeChain.isActive()) {
            return null;
        }

        // Energy check
        if (!activeChain.hasEnergyBudget(currentEnergy)) {
            activeChain = activeChain.pause();
            return new StepOutcome(null, "Chain paused — energy too low.", true, false);
        }

        var step = activeChain.currentStep();
        if (step == null) {
            activeChain = new DelegationChainState(
                activeChain.chainId(), activeChain.goal(), activeChain.steps(),
                activeChain.currentStepIndex(), DelegationChainState.ChainStatus.COMPLETED,
                activeChain.startedAt(), activeChain.energyAtStart(),
                activeChain.completedResults());
            return new StepOutcome(null, "Chain complete.", false, true);
        }

        // Execute the skill
        long startMs = System.currentTimeMillis();
        var ctx = SkillContext.forAgent(agentDid, roomId, Map.of(), Long.MAX_VALUE);
        var skillName = step.skillName();
        boolean success = false;
        String output = "Skill not available: " + skillName;

        // Try workbench first
        String workbenchId = "workbench." + skillName;
        if (workbench != null && workbench.supports(workbenchId)) {
            var result = workbench.execute(workbenchId, step.params(), ctx);
            success = result.success();
            output = result.output();
        } else if (skillRegistry != null) {
            var result = skillRegistry.execute(skillName, step.params(), ctx);
            success = result.success();
            output = result.output();
        }

        long elapsed = System.currentTimeMillis() - startMs;

        // Track usage
        if (usageTracker != null) {
            usageTracker.record(skillName, success, elapsed, "delegation-chain");
        }

        var stepResult = new DelegationChainState.StepResult(
            activeChain.currentStepIndex(), skillName, success, output, elapsed);
        activeChain = activeChain.advanceStep(stepResult);

        boolean chainDone = activeChain.isComplete();
        return new StepOutcome(stepResult, null, false, chainDone);
    }

    /** Abort the active chain. */
    public boolean abortChain() {
        if (activeChain == null || !activeChain.isActive()) return false;
        activeChain = activeChain.abort();
        return true;
    }

    /** Get the active chain state (nullable). */
    public DelegationChainState activeChain() {
        return activeChain;
    }

    /** Whether a chain is currently active. */
    public boolean hasActiveChain() {
        return activeChain != null && activeChain.isActive();
    }

    /** Build context section for active chain (for Layer 2.7), or null. */
    public String buildContextSection() {
        if (activeChain == null || !activeChain.isActive()) return null;
        return activeChain.buildContextSection();
    }

    /**
     * Result of executing one step.
     *
     * @param stepResult  The step result (null if paused/complete without executing)
     * @param message     Status message (for pause/error)
     * @param paused      Whether the chain was paused
     * @param chainDone   Whether the entire chain is now complete
     */
    public record StepOutcome(
        DelegationChainState.StepResult stepResult,
        String message,
        boolean paused,
        boolean chainDone
    ) {}
}

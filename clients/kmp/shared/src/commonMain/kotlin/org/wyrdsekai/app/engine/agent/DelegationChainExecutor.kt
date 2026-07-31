package org.wyrdsekai.app.engine.agent

/**
 * Phone-side delegation chain system.
 *
 * Ported from the server's DelegationChainState + DelegationChainExecutor.
 * Phone limits: maxSteps=4, energySafetyReserve=0.15.
 *
 * The executor orchestrates multi-step execution — it tracks state, checks
 * the energy budget, and handles step results. The actual skill execution is
 * provided by the caller as a suspend callback.
 */

// --- Enums ---

enum class ChainStatus {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, ABORTED
}

enum class StepStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED
}

// --- Data classes ---

/**
 * A single step in a delegation chain.
 *
 * @param skillId  Identifier of the skill to execute (e.g. "search", "summarize").
 * @param params   Parameters for the skill, opaque to the executor.
 * @param status   Current status of this step.
 * @param result   Output from execution, null until the step completes.
 */
data class ChainStep(
    val skillId: String,
    val params: Map<String, String> = emptyMap(),
    val status: StepStatus = StepStatus.PENDING,
    val result: String? = null,
)

/**
 * A multi-step delegation chain.
 *
 * @param goal             Human-readable description of what the chain accomplishes.
 * @param steps            Ordered list of steps to execute.
 * @param status           Overall chain status.
 * @param currentStepIndex Index of the next step to execute (0-based).
 */
data class DelegationChain(
    val goal: String,
    val steps: List<ChainStep>,
    val status: ChainStatus = ChainStatus.PENDING,
    val currentStepIndex: Int = 0,
) {
    /** Whether the chain is still active (can execute more steps). */
    val isActive: Boolean
        get() = status == ChainStatus.PENDING || status == ChainStatus.RUNNING

    /** Whether all steps have been executed. */
    val isComplete: Boolean
        get() = currentStepIndex >= steps.size

    /** The current step, or null if all steps have been executed. */
    val currentStep: ChainStep?
        get() = steps.getOrNull(currentStepIndex)

    /** Number of steps remaining (including current). */
    val remainingSteps: Int
        get() = maxOf(0, steps.size - currentStepIndex)
}

// --- Executor ---

/**
 * Orchestrates delegation chain execution on the phone.
 *
 * Phone limits:
 * - [MAX_STEPS] = 4 (server allows 8)
 * - [ENERGY_SAFETY_RESERVE] = 0.15 — chain pauses if energy drops below this
 *
 * Energy costs:
 * - [ENERGY_PER_STEP] = 0.02 per step executed
 * - [ENERGY_PER_REEVALUATION] = 0.03 per inter-step re-evaluation
 *
 * If a step fails, it is marked FAILED and the chain moves to the next step
 * rather than aborting the entire chain. The chain only reaches FAILED status
 * if it cannot start (validation) or is explicitly aborted.
 */
class DelegationChainExecutor(
    private val getEnergy: () -> Double,
    private val consumeEnergy: (Double) -> Unit,
) {
    companion object {
        /** Maximum steps allowed per chain on phone. */
        const val MAX_STEPS = 4

        /** Energy safety reserve — chain pauses below this. */
        const val ENERGY_SAFETY_RESERVE = 0.15

        /** Energy cost per step execution. */
        const val ENERGY_PER_STEP = 0.02

        /** Energy cost per re-evaluation between steps. */
        const val ENERGY_PER_REEVALUATION = 0.03
    }

    /**
     * Execute a delegation chain to completion.
     *
     * Walks through each step in order, calling [executeStep] for the actual
     * skill execution. The executor manages state transitions, energy budget
     * checks, and step-level failure isolation.
     *
     * @param chain        The chain to execute. Must be in PENDING or RUNNING status.
     * @param executeStep  Callback that performs the actual skill execution.
     *                     Receives a step with status=RUNNING, must return it
     *                     with status=SUCCEEDED or FAILED and a result string.
     * @return The chain in its final state (COMPLETED, PAUSED, FAILED, or ABORTED).
     */
    suspend fun execute(
        chain: DelegationChain,
        executeStep: suspend (ChainStep) -> ChainStep,
    ): DelegationChain {
        // --- Validation ---
        if (!chain.isActive) {
            return chain // Already terminal — nothing to do
        }

        if (chain.steps.isEmpty()) {
            return chain.copy(status = ChainStatus.FAILED)
        }

        if (chain.steps.size > MAX_STEPS) {
            return chain.copy(status = ChainStatus.FAILED)
        }

        // Estimate total energy needed
        val stepsRemaining = chain.remainingSteps
        val estimatedCost = stepsRemaining * ENERGY_PER_STEP +
            stepsRemaining * ENERGY_PER_REEVALUATION
        if (getEnergy() - estimatedCost < ENERGY_SAFETY_RESERVE) {
            return chain.copy(status = ChainStatus.FAILED)
        }

        // --- Execution loop ---
        var current = chain.copy(status = ChainStatus.RUNNING)

        while (!current.isComplete && current.status == ChainStatus.RUNNING) {
            // Energy gate before each step
            if (getEnergy() - ENERGY_PER_STEP < ENERGY_SAFETY_RESERVE) {
                current = current.copy(status = ChainStatus.PAUSED)
                break
            }

            val stepIndex = current.currentStepIndex
            val step = current.steps[stepIndex]

            // Mark step as RUNNING
            val runningStep = step.copy(status = StepStatus.RUNNING)
            val updatedSteps = current.steps.toMutableList()
            updatedSteps[stepIndex] = runningStep
            current = current.copy(steps = updatedSteps)

            // Consume energy for this step
            consumeEnergy(ENERGY_PER_STEP)

            // Execute the step via callback
            val executedStep = try {
                executeStep(runningStep)
            } catch (_: Exception) {
                runningStep.copy(
                    status = StepStatus.FAILED,
                    result = "Step execution threw an exception",
                )
            }

            // Ensure the returned step has a terminal status
            val finalStep = if (executedStep.status == StepStatus.RUNNING ||
                executedStep.status == StepStatus.PENDING
            ) {
                // Callback didn't set a terminal status — treat as succeeded
                executedStep.copy(status = StepStatus.SUCCEEDED)
            } else {
                executedStep
            }

            // Update the step in the chain
            val stepsAfterExec = current.steps.toMutableList()
            stepsAfterExec[stepIndex] = finalStep
            val nextIndex = stepIndex + 1
            current = current.copy(
                steps = stepsAfterExec,
                currentStepIndex = nextIndex,
            )

            // If there are more steps, consume re-evaluation energy
            if (nextIndex < current.steps.size) {
                consumeEnergy(ENERGY_PER_REEVALUATION)
            }
        }

        // Determine final status
        if (current.status == ChainStatus.RUNNING && current.isComplete) {
            current = current.copy(status = ChainStatus.COMPLETED)
        }

        return current
    }

    /**
     * Estimate total energy cost for a chain before execution.
     */
    fun estimateEnergyCost(stepCount: Int): Double {
        val clamped = minOf(stepCount, MAX_STEPS)
        return clamped * ENERGY_PER_STEP + clamped * ENERGY_PER_REEVALUATION
    }

    /**
     * Whether there is enough energy to start a chain with [stepCount] steps.
     */
    fun canAfford(stepCount: Int): Boolean {
        return getEnergy() - estimateEnergyCost(stepCount) >= ENERGY_SAFETY_RESERVE
    }

    /**
     * Build a progress summary for context injection (Layer 2.7 equivalent).
     */
    fun buildContextSection(chain: DelegationChain): String? {
        if (!chain.isActive) return null

        val sb = StringBuilder()
        sb.appendLine("## Active Delegation Chain")
        sb.appendLine("Goal: ${chain.goal}")
        sb.appendLine("Progress: step ${chain.currentStepIndex + 1} of ${chain.steps.size}")

        // Show completed steps
        val completed = chain.steps.take(chain.currentStepIndex)
        if (completed.isNotEmpty()) {
            sb.appendLine("Completed:")
            for (step in completed) {
                val mark = if (step.status == StepStatus.SUCCEEDED) "ok" else "fail"
                sb.append("- ${step.skillId} [$mark]")
                if (step.result != null && step.result.length <= 50) {
                    sb.append(": ${step.result}")
                }
                sb.appendLine()
            }
        }

        // Show next step
        val next = chain.currentStep
        if (next != null) {
            sb.appendLine("Next: ${next.skillId}")
        }

        return sb.toString()
    }
}

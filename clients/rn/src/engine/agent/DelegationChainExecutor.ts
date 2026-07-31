/**
 * Multi-step delegation chain execution.
 * TypeScript port of DelegationChainState.java + DelegationChainExecutor.java.
 *
 * The companion plans a sequence of skill executions, executes them
 * one at a time, narrates progress, and re-evaluates between steps.
 * Guardrails: max steps, energy reserve, one chain at a time.
 */

// ---------------------------------------------------------------------------
// Enums & Interfaces
// ---------------------------------------------------------------------------

export type ChainStatus =
  | 'planning'
  | 'executing'
  | 'paused'     // energy below reserve
  | 'completed'
  | 'aborted'
  | 'failed';

export type StepStatus = 'pending' | 'running' | 'success' | 'failure';

export interface ChainStep {
  skillName: string;
  params: Record<string, unknown>;
  description: string | null;
}

export interface StepResult {
  stepIndex: number;
  skillName: string;
  success: boolean;
  output: string | null;
  durationMs: number;
}

export interface DelegationChain {
  chainId: string;
  goal: string;
  steps: ChainStep[];
  currentStepIndex: number;
  status: ChainStatus;
  startedAt: number; // epoch ms
  energyAtStart: number;
  completedResults: StepResult[];
}

export interface StepOutcome {
  stepResult: StepResult | null;
  message: string | null;
  paused: boolean;
  chainDone: boolean;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Maximum steps allowed per chain (server). */
export const MAX_STEPS_SERVER = 8;

/** Maximum steps allowed per chain (phone). */
export const MAX_STEPS_PHONE = 4;

/** Energy safety reserve -- chain pauses below this. */
export const ENERGY_RESERVE = 0.15;

/** Energy cost per step (on top of skill cost). */
export const ENERGY_PER_STEP = 0.02;

/** Energy cost per re-evaluation. */
export const ENERGY_PER_REEVALUATION = 0.03;

/** Context budget cost per re-evaluation. */
export const CONTEXT_PER_REEVALUATION = 0.02;

// ---------------------------------------------------------------------------
// Chain state helpers
// ---------------------------------------------------------------------------

/** Whether the chain is still active (can execute more steps). */
export function isChainActive(chain: DelegationChain): boolean {
  return chain.status === 'executing' || chain.status === 'planning';
}

/** Whether all steps have been completed. */
export function isChainComplete(chain: DelegationChain): boolean {
  return chain.currentStepIndex >= chain.steps.length;
}

/** Get the current step, or null if complete. */
export function currentStep(chain: DelegationChain): ChainStep | null {
  if (chain.currentStepIndex >= chain.steps.length) return null;
  return chain.steps[chain.currentStepIndex];
}

/** Number of steps remaining. */
export function remainingSteps(chain: DelegationChain): number {
  return Math.max(0, chain.steps.length - chain.currentStepIndex);
}

/** Estimate total energy cost for remaining steps. */
export function estimateRemainingEnergy(chain: DelegationChain): number {
  const remaining = remainingSteps(chain);
  return remaining * ENERGY_PER_STEP + remaining * ENERGY_PER_REEVALUATION;
}

/** Whether we have enough energy to continue (above reserve). */
export function hasEnergyBudget(currentEnergy: number): boolean {
  return currentEnergy - ENERGY_PER_STEP > ENERGY_RESERVE;
}

/** Advance to the next step. */
function advanceStep(chain: DelegationChain, result: StepResult): DelegationChain {
  const newResults = [...chain.completedResults, result];
  const nextIndex = chain.currentStepIndex + 1;
  const nextStatus: ChainStatus = nextIndex >= chain.steps.length ? 'completed' : 'executing';
  return {
    ...chain,
    currentStepIndex: nextIndex,
    status: nextStatus,
    completedResults: newResults,
  };
}

/** Create a new chain in EXECUTING state. */
function createChain(
  chainId: string,
  goal: string,
  steps: ChainStep[],
  currentEnergy: number,
): DelegationChain {
  return {
    chainId,
    goal,
    steps,
    currentStepIndex: 0,
    status: 'executing',
    startedAt: Date.now(),
    energyAtStart: currentEnergy,
    completedResults: [],
  };
}

/**
 * Build a progress summary for Layer 2.7 context injection.
 */
export function buildChainContextSection(chain: DelegationChain): string {
  const lines: string[] = [];
  lines.push('## Active Delegation Chain');
  lines.push(`Goal: ${chain.goal}`);
  lines.push(`Progress: step ${chain.currentStepIndex + 1} of ${chain.steps.length}`);

  if (chain.completedResults.length > 0) {
    lines.push('Completed:');
    for (const r of chain.completedResults) {
      let line = `- ${r.skillName}${r.success ? ' [ok]' : ' [fail]'}`;
      if (r.output && r.output.length <= 50) {
        line += `: ${r.output}`;
      }
      lines.push(line);
    }
  }

  const step = currentStep(chain);
  if (step) {
    let line = `Next: ${step.skillName}`;
    if (step.description) {
      line += ` \u2014 ${step.description}`;
    }
    lines.push(line);
  }

  return lines.join('\n') + '\n';
}

// ---------------------------------------------------------------------------
// Executor
// ---------------------------------------------------------------------------

/**
 * Skill executor interface -- the DelegationChainExecutor calls this to
 * run each step. On phones this is typically a no-op or simplified executor.
 */
export interface SkillExecutor {
  execute(
    skillName: string,
    params: Record<string, unknown>,
    agentDid: string,
    roomId: string,
  ): Promise<{ success: boolean; output: string }>;
}

/**
 * Orchestrates delegation chain execution.
 *
 * The CompanionEngine drives the execution loop; this class provides
 * the step logic and state management.
 */
export class DelegationChainExecutor {
  private activeChain: DelegationChain | null = null;

  constructor(
    private readonly skillExecutor: SkillExecutor | null,
    private readonly maxSteps: number = MAX_STEPS_PHONE,
  ) {}

  /** Phone default: 4 max steps. */
  static phoneDefault(executor: SkillExecutor | null): DelegationChainExecutor {
    return new DelegationChainExecutor(executor, MAX_STEPS_PHONE);
  }

  /** Server default: 8 max steps. */
  static serverDefault(executor: SkillExecutor | null): DelegationChainExecutor {
    return new DelegationChainExecutor(executor, MAX_STEPS_SERVER);
  }

  // --- Chain lifecycle ---

  /**
   * Start a new delegation chain.
   * @returns Error message if chain can't start, null if started successfully
   */
  startChain(goal: string, steps: ChainStep[], currentEnergy: number): string | null {
    if (this.activeChain && isChainActive(this.activeChain)) {
      return `A chain is already in progress: ${this.activeChain.goal}`;
    }

    if (steps.length === 0) {
      return 'No steps provided.';
    }

    if (steps.length > this.maxSteps) {
      return `Too many steps (${steps.length}). Maximum is ${this.maxSteps}.`;
    }

    // Energy budget check
    const estimatedCost = steps.length * ENERGY_PER_STEP + steps.length * ENERGY_PER_REEVALUATION;
    if (currentEnergy - estimatedCost < ENERGY_RESERVE) {
      return `Not enough energy for this chain. Need ~${(estimatedCost + ENERGY_RESERVE).toFixed(2)} but have ${currentEnergy.toFixed(2)}.`;
    }

    const chainId = Math.random().toString(36).substring(2, 10);
    this.activeChain = createChain(chainId, goal, steps, currentEnergy);
    return null; // success
  }

  /**
   * Execute the current step of the active chain.
   * @returns Step outcome, or null if no active chain / no step to execute
   */
  async executeCurrentStep(
    agentDid: string,
    roomId: string,
    currentEnergy: number,
  ): Promise<StepOutcome | null> {
    if (!this.activeChain || !isChainActive(this.activeChain)) {
      return null;
    }

    // Energy check
    if (!hasEnergyBudget(currentEnergy)) {
      this.activeChain = { ...this.activeChain, status: 'paused' };
      return { stepResult: null, message: 'Chain paused \u2014 energy too low.', paused: true, chainDone: false };
    }

    const step = currentStep(this.activeChain);
    if (!step) {
      this.activeChain = { ...this.activeChain, status: 'completed' };
      return { stepResult: null, message: 'Chain complete.', paused: false, chainDone: true };
    }

    // Execute the skill
    const startMs = Date.now();
    let success = false;
    let output = `Skill not available: ${step.skillName}`;

    if (this.skillExecutor) {
      try {
        const result = await this.skillExecutor.execute(step.skillName, step.params, agentDid, roomId);
        success = result.success;
        output = result.output;
      } catch (err) {
        success = false;
        output = `Execution error: ${err instanceof Error ? err.message : String(err)}`;
      }
    }

    const elapsed = Date.now() - startMs;

    const stepResult: StepResult = {
      stepIndex: this.activeChain.currentStepIndex,
      skillName: step.skillName,
      success,
      output,
      durationMs: elapsed,
    };

    this.activeChain = advanceStep(this.activeChain, stepResult);
    const chainDone = isChainComplete(this.activeChain);

    return { stepResult, message: null, paused: false, chainDone };
  }

  /** Abort the active chain. */
  abortChain(): boolean {
    if (!this.activeChain || !isChainActive(this.activeChain)) return false;
    this.activeChain = { ...this.activeChain, status: 'aborted' };
    return true;
  }

  /** Get the active chain state (nullable). */
  getActiveChain(): DelegationChain | null {
    return this.activeChain;
  }

  /** Whether a chain is currently active. */
  hasActiveChain(): boolean {
    return this.activeChain != null && isChainActive(this.activeChain);
  }

  /** Build context section for active chain (for Layer 2.7), or null. */
  buildContextSection(): string | null {
    if (!this.activeChain || !isChainActive(this.activeChain)) return null;
    return buildChainContextSection(this.activeChain);
  }
}

/**
 * Per-plan token budget that tracks estimated inference cost (RN port).
 *
 * Informs GoalExecutor decisions: when budget is tight, prefer skip/delegate
 * over retry. When exhausted, escalate immediately.
 */

import type { TaskPlan } from './TaskPlan';

export interface PlanTokenBudget {
  totalTokens: number;
  usedTokens: number;
  estimatedPerGoal: number;
}

const CHARS_PER_TOKEN = 4;

/** Tokens remaining in the budget. */
export function remaining(budget: PlanTokenBudget): number {
  return budget.totalTokens - budget.usedTokens;
}

/** Fraction of budget consumed (0.0 to 1.0+). */
export function utilizationFraction(budget: PlanTokenBudget): number {
  return budget.totalTokens > 0 ? budget.usedTokens / budget.totalTokens : 1.0;
}

/** Check if there are enough tokens for an estimated cost. */
export function canAfford(budget: PlanTokenBudget, estimatedCost: number): boolean {
  return remaining(budget) >= estimatedCost;
}

/** Return a new budget with additional tokens consumed. */
export function withUsed(budget: PlanTokenBudget, additionalTokens: number): PlanTokenBudget {
  return {
    ...budget,
    usedTokens: budget.usedTokens + additionalTokens,
  };
}

/**
 * Create a budget for a plan based on context window size and goal count.
 * Budget = contextWindow * goals * 2 (allow retries).
 */
export function forPlan(plan: TaskPlan, contextWindowTokens: number): PlanTokenBudget {
  const goalsCount = Math.max(1, plan.goals.length);
  const perGoal = contextWindowTokens;
  return {
    totalTokens: perGoal * goalsCount * 2,
    usedTokens: 0,
    estimatedPerGoal: perGoal,
  };
}

/**
 * Estimate tokens from prompt + response character lengths.
 */
export function estimateFromChars(promptChars: number, responseChars: number): number {
  return Math.floor((promptChars + responseChars) / CHARS_PER_TOKEN);
}

/**
 * Build a brief status string for injection into plan prompt context.
 */
export function buildBudgetPromptContext(budget: PlanTokenBudget): string {
  const pct = Math.floor(utilizationFraction(budget) * 100);
  return `Token budget: ${remaining(budget)} remaining of ${budget.totalTokens} (${pct}% used)`;
}

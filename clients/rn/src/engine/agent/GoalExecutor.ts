/**
 * Decision engine for goal-based task execution (RN port).
 * Pure functions. Mirrors Java GoalExecutor.
 */

import {TaskPlan, Goal, currentGoal} from './TaskPlan';

const MAX_SAME_ACTION_REPEATS = 3;
const MAX_TOTAL_PLAN_ATTEMPTS = 15;
const ENERGY_FLOOR = 0.15;

export type Decision =
  | {type: 'advance'; outcome: string}
  | {type: 'retry'; guidance: string}
  | {type: 'delegate'; targetAgent: string; task: string}
  | {type: 'replan'; reason: string}
  | {type: 'skip'; reason: string}
  | {type: 'escalate'; message: string}
  | {type: 'suspend'; reason: string};

export function evaluate(
  plan: TaskPlan,
  actionSuccess: boolean,
  actionResult: string | undefined,
  actionType: string,
  currentEnergy: number,
): Decision {
  const goal = currentGoal(plan);
  if (!goal) return {type: 'advance', outcome: 'No active goal'};

  if (actionSuccess) {
    return {type: 'advance', outcome: actionResult ?? 'completed'};
  }

  if (currentEnergy < ENERGY_FLOOR) {
    return {type: 'suspend', reason: `Energy too low (${currentEnergy.toFixed(2)})`};
  }

  if (plan.totalAttempts >= MAX_TOTAL_PLAN_ATTEMPTS) {
    return {type: 'escalate', message: `Exhausted ${MAX_TOTAL_PLAN_ATTEMPTS} attempts. Last: ${actionResult}`};
  }

  const remaining = Math.max(0, goal.maxAttempts - goal.attempts.length);
  if (remaining > 0) {
    if (detectRepeatLoop(goal, actionType)) {
      return {type: 'retry', guidance: `STOP repeating '${actionType}'. Try completely different approach.`};
    }
    const hint = goal.attempts.length === 1
      ? 'Try a different approach'
      : 'Previous approaches failed. Try completely different strategy';
    return {type: 'retry', guidance: `${actionType} failed: ${actionResult}. ${hint} (${remaining} retries).`};
  }

  if (plan.currentGoalIndex < plan.goals.length - 1) {
    return {type: 'skip', reason: `Failed after ${goal.attempts.length} attempts: ${actionResult}`};
  }

  const tried = goal.attempts.map(a => `${a.actionType}: ${a.result ?? 'failed'}`);
  return {type: 'escalate', message: `Stuck on '${goal.description}'. Tried: ${tried.join(', ')}`};
}

function detectRepeatLoop(goal: Goal, actionType: string): boolean {
  const attempts = goal.attempts;
  if (attempts.length < MAX_SAME_ACTION_REPEATS) return false;
  let count = 0;
  for (let i = attempts.length - 1; i >= 0; i--) {
    if (attempts[i].actionType === actionType) count++;
    else break;
    if (count >= MAX_SAME_ACTION_REPEATS) return true;
  }
  return false;
}

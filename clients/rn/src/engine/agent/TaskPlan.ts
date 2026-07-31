/**
 * Goal-based task plan for autonomous multi-step execution (RN port).
 * Mirrors Java TaskPlan. Goals are descriptions, not actions.
 */

export type PlanStatus = 'active' | 'completed' | 'failed' | 'abandoned' | 'suspended';
export type GoalStatus = 'pending' | 'active' | 'done' | 'failed' | 'skipped';

export interface Attempt {
  actionType: string;
  parameters?: string;
  result?: string;
  success: boolean;
  timestamp: number;
}

export interface Goal {
  description: string;
  status: GoalStatus;
  attempts: Attempt[];
  outcome?: string;
  maxAttempts: number;
}

export interface TaskPlan {
  planId: string;
  description: string;
  requesterId?: string;
  requesterName?: string;
  goals: Goal[];
  currentGoalIndex: number;
  status: PlanStatus;
  createdAt: number;
  deadline?: number;
  totalAttempts: number;
  finalOutcome?: string;
}

export function createTaskPlan(
  planId: string,
  description: string,
  goalDescriptions: string[],
  requesterId?: string,
  requesterName?: string,
): TaskPlan {
  const goals: Goal[] = goalDescriptions.map(d => ({
    description: d,
    status: 'pending' as GoalStatus,
    attempts: [],
    maxAttempts: 3,
  }));
  if (goals.length > 0) goals[0].status = 'active';
  return {
    planId,
    description,
    requesterId,
    requesterName,
    goals,
    currentGoalIndex: 0,
    status: 'active',
    createdAt: Date.now(),
    totalAttempts: 0,
  };
}

export function currentGoal(plan: TaskPlan): Goal | undefined {
  return plan.goals[plan.currentGoalIndex];
}

export function recordAttempt(plan: TaskPlan, actionType: string, params: string | undefined,
                               result: string | undefined, success: boolean): void {
  const goal = currentGoal(plan);
  if (goal) {
    goal.attempts.push({actionType, parameters: params, result, success, timestamp: Date.now()});
    plan.totalAttempts++;
  }
}

export function advanceGoal(plan: TaskPlan, outcome: string): boolean {
  const goal = currentGoal(plan);
  if (goal && goal.status !== 'skipped') {
    goal.status = 'done';
    goal.outcome = outcome;
  }
  plan.currentGoalIndex++;
  if (plan.currentGoalIndex < plan.goals.length) {
    plan.goals[plan.currentGoalIndex].status = 'active';
    return true;
  }
  return false;
}

export function buildPlanPromptContext(plan: TaskPlan): string {
  const lines: string[] = [];
  lines.push('## Active Task');
  let header = plan.description;
  if (plan.requesterName) header += ` (for ${plan.requesterName})`;
  header += ` — goal ${plan.currentGoalIndex + 1}/${plan.goals.length}`;
  lines.push(header);
  lines.push('');

  plan.goals.forEach((goal, i) => {
    const marker = goal.status.toUpperCase();
    let line = `Goal ${i + 1}: ${goal.description} [${marker}`;
    if (goal.outcome) line += ` — ${goal.outcome}`;
    line += ']';
    lines.push(line);

    if (goal.status === 'active' && goal.attempts.length > 0) {
      for (const attempt of goal.attempts) {
        let aLine = `  Attempt: ${attempt.actionType}`;
        if (attempt.parameters) aLine += ` ${attempt.parameters}`;
        aLine += ` → ${attempt.success ? 'OK' : 'FAILED'}`;
        if (attempt.result) aLine += `: ${attempt.result}`;
        lines.push(aLine);
      }
      const remaining = Math.max(0, goal.maxAttempts - goal.attempts.length);
      lines.push(`  Retries remaining: ${remaining}`);
    }
  });

  if (plan.requesterId) {
    lines.push('');
    lines.push(`When all goals are complete, tell ${plan.requesterName ?? plan.requesterId} what you found.`);
  }

  return lines.join('\n');
}

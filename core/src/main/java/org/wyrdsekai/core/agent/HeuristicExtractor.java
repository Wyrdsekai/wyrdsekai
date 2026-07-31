package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Extracts heuristics and skills from completed/failed task plans during Forge.
 *
 * <p>Research: failure-derived heuristics outperform success-derived ones by
 * significant margin. Failure heuristics provide negative constraints that
 * prune ineffective strategies. Success heuristics reinforce proven sequences.</p>
 */
public final class HeuristicExtractor {

    private static final Logger log = LoggerFactory.getLogger(HeuristicExtractor.class);

    private HeuristicExtractor() {}

    /**
     * Extract heuristics from a completed or failed task plan.
     *
     * @param plan the task plan to analyze
     * @return list of heuristics learned from this plan
     */
    public static List<Heuristic> extract(TaskPlan plan) {
        var heuristics = new ArrayList<Heuristic>();

        for (var goal : plan.goals()) {
            // Failed goals → failure avoidance heuristics (highest value)
            if (goal.status() == TaskPlan.GoalStatus.FAILED) {
                heuristics.addAll(extractFailureHeuristics(goal));
            }

            // Successful goals with multiple attempts → retry heuristics
            if (goal.status() == TaskPlan.GoalStatus.DONE && goal.attempts().size() > 1) {
                heuristics.addAll(extractRetryHeuristics(goal));
            }
        }

        log.debug("Extracted {} heuristics from plan '{}'", heuristics.size(), plan.description());
        return heuristics;
    }

    /**
     * Extract a learned skill from a successfully completed plan.
     *
     * @param plan the completed plan
     * @return a learned skill, or null if the plan is too simple to learn from
     */
    public static LearnedSkill extractSkill(TaskPlan plan) {
        if (plan.status() != TaskPlan.PlanStatus.COMPLETED) return null;
        if (plan.goals().size() < 2) return null; // too simple

        var goalTemplates = plan.goals().stream()
            .filter(g -> g.status() == TaskPlan.GoalStatus.DONE)
            .map(TaskPlan.Goal::description)
            .toList();

        if (goalTemplates.size() < 2) return null;

        // Infer domain from goal descriptions
        var domain = inferDomain(plan);

        return new LearnedSkill(
            slugify(plan.description()),
            plan.description(),
            goalTemplates,
            domain,
            1,
            Instant.now()
        );
    }

    // --- Internal ---

    private static List<Heuristic> extractFailureHeuristics(TaskPlan.Goal goal) {
        var heuristics = new ArrayList<Heuristic>();

        for (var attempt : goal.attempts()) {
            if (!attempt.success()) {
                var trigger = attempt.actionType() + " on '" + goal.description() + "'";
                var guidance = "Avoid: " + attempt.actionType();
                if (attempt.parameters() != null) {
                    guidance += " with params '" + attempt.parameters() + "'";
                }
                guidance += " → resulted in: " + (attempt.result() != null ? attempt.result() : "failure");

                var domain = GoalExecutor.inferDomain(attempt.actionType());
                heuristics.add(new Heuristic(
                    domain != null ? domain : "general",
                    trigger,
                    guidance,
                    Heuristic.HeuristicType.FAILURE_AVOIDANCE,
                    0.7,
                    0
                ));
            }
        }

        return heuristics;
    }

    private static List<Heuristic> extractRetryHeuristics(TaskPlan.Goal goal) {
        var heuristics = new ArrayList<Heuristic>();

        // The successful attempt (last one) teaches what worked after failures
        var successAttempt = goal.attempts().stream()
            .filter(TaskPlan.Attempt::success)
            .reduce((first, second) -> second) // last success
            .orElse(null);

        if (successAttempt != null && goal.attempts().size() > 1) {
            var failedApproaches = goal.attempts().stream()
                .filter(a -> !a.success())
                .map(a -> a.actionType() + (a.parameters() != null ? " " + a.parameters() : ""))
                .toList();

            var trigger = "'" + goal.description() + "' failed with: " + failedApproaches;
            var guidance = "What worked: " + successAttempt.actionType()
                + (successAttempt.parameters() != null ? " " + successAttempt.parameters() : "")
                + " → " + (successAttempt.result() != null ? successAttempt.result() : "success");

            var domain = GoalExecutor.inferDomain(successAttempt.actionType());
            heuristics.add(new Heuristic(
                domain != null ? domain : "general",
                trigger,
                guidance,
                Heuristic.HeuristicType.SUCCESS_PATTERN,
                0.8,
                0
            ));
        }

        return heuristics;
    }

    private static String inferDomain(TaskPlan plan) {
        var desc = plan.description().toLowerCase();
        if (desc.contains("search") || desc.contains("find") || desc.contains("book")) return "search";
        if (desc.contains("go") || desc.contains("navigate") || desc.contains("visit")) return "navigation";
        if (desc.contains("tell") || desc.contains("report") || desc.contains("ask")) return "communication";
        return "general";
    }

    private static String slugify(String text) {
        return text.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", "")
            .replaceAll("\\s+", "-")
            .substring(0, Math.min(40, text.length()));
    }

    /**
     * Build prompt context from relevant heuristics.
     *
     * @param heuristics all heuristics
     * @param currentTask current task description (for relevance matching)
     * @param maxHeuristics maximum to include
     * @return formatted prompt context, or null if none relevant
     */
    public static String buildPromptContext(List<Heuristic> heuristics,
                                             String currentTask, int maxHeuristics) {
        if (heuristics == null || heuristics.isEmpty()) return null;

        var taskLower = currentTask != null ? currentTask.toLowerCase() : "";
        var relevant = heuristics.stream()
            .filter(h -> {
                // Match by domain or keyword overlap
                if (taskLower.contains(h.domain())) return true;
                var triggerWords = h.trigger().toLowerCase().split("\\s+");
                for (var word : triggerWords) {
                    if (word.length() > 3 && taskLower.contains(word)) return true;
                }
                return false;
            })
            .sorted(Comparator.comparingDouble(Heuristic::confidence).reversed())
            .limit(maxHeuristics)
            .toList();

        if (relevant.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("## Learned Patterns\n");
        for (var h : relevant) {
            sb.append("- ").append(h.guidance()).append("\n");
        }
        return sb.toString();
    }
}

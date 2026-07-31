package org.wyrdsekai.core.familiar;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Structured failure report when a familiar hits {@code maxTrials} or exhausts
 * tanks.
 *
 * <p>Unlike the narrative {@code summary} string that bubbles back to the
 * parent, this record captures the failure shape so the parent (agent or
 * bunshin) can reason structurally: which approaches were tried, what
 * blockers appeared, and whether the familiar itself has a best-guess
 * suggestion for what to try next.</p>
 *
 * <p>Used by {@link FamiliarActor} on STUCK/TIMEOUT termination. Lives in the
 * {@link Familiar#result()} slot; the parent can {@code instanceof} pattern
 * match to decide whether to re-summon with adjusted parameters, pivot form,
 * or drop the thread (§9 step 4).</p>
 *
 * @param task        original task description
 * @param trialsUsed  how many inference turns the familiar attempted
 * @param attempted   short labels of distinct approaches the familiar tried
 * @param obstacles   error messages, refusals, or blockers encountered
 * @param suggestion  familiar's own guess at what to try next (may be empty)
 */
public record StuckReport(
    String task,
    int trialsUsed,
    List<String> attempted,
    List<String> obstacles,
    Optional<String> suggestion
) {
    public StuckReport {
        if (task == null) task = "";
        if (trialsUsed < 0) trialsUsed = 0;
        attempted = attempted == null ? List.of() : List.copyOf(attempted);
        obstacles = obstacles == null ? List.of() : List.copyOf(obstacles);
        if (suggestion == null) suggestion = Optional.empty();
    }

    /**
     * Construct a {@link StuckReport} from a familiar's turn log + final
     * narrative summary. Approaches and obstacles are heuristically extracted
     * — model-judged extraction is a later refinement (§9 + §23).
     */
    public static StuckReport fromFamiliar(Familiar f, String narrativeSummary) {
        var attempted = new ArrayList<String>();
        var obstacles = new ArrayList<String>();
        if (f.log() != null) {
            for (var turn : f.log()) {
                if (turn == null || turn.content() == null) continue;
                var text = turn.content().toLowerCase();
                // Approach markers
                if (text.startsWith("i'll ") || text.startsWith("let me ")
                        || text.startsWith("trying ") || text.startsWith("attempt")) {
                    attempted.add(truncate(turn.content(), 120));
                }
                // Obstacle markers
                if (text.contains("error") || text.contains("failed")
                        || text.contains("refused") || text.contains("blocked")
                        || text.contains("not found") || text.contains("unavailable")) {
                    obstacles.add(truncate(turn.content(), 120));
                }
            }
        }
        // Dedupe while preserving order; cap length
        var attemptedUnique = attempted.stream().distinct().limit(10).toList();
        var obstaclesUnique = obstacles.stream().distinct().limit(10).toList();
        // Suggestion heuristic: if the final summary contains "try", "next", or
        // "suggest", treat it as the familiar's own best guess.
        Optional<String> suggestion = Optional.empty();
        if (narrativeSummary != null) {
            var low = narrativeSummary.toLowerCase();
            if (low.contains("try") || low.contains("next time")
                    || low.contains("suggest") || low.contains("maybe")) {
                suggestion = Optional.of(truncate(narrativeSummary, 400));
            }
        }
        return new StuckReport(
            f.task(), f.trialsUsed(),
            attemptedUnique, obstaclesUnique, suggestion);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}

package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulItem;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates companion self-assessment.
 *
 * Gathers skill usage data from {@link SkillUsageTracker}, builds a
 * prompt for LLM inference, and parses the structured response into
 * a {@link SelfAssessment}. The CompanionActor owns the inference
 * lifecycle; SelfAssessor is a stateless utility.
 *
 * Triggers:
 * - Sleep cycle (deepest: full history review)
 * - Every 6h if active (lightweight)
 * - After 3 gap accumulations (skill failures / unfulfilled requests)
 */
public final class SelfAssessor {

    private SelfAssessor() {}

    /** Default interval between periodic assessments. */
    public static final Duration PERIODIC_INTERVAL = Duration.ofHours(6);

    /** System prompt for the assessment inference call. */
    static final String SYSTEM_PROMPT = """
        You are a companion reflecting on your own capabilities.
        Analyze the skill usage data below and produce a JSON assessment.

        Output ONLY a JSON object with this structure:
        ```json
        {
          "proficiencies": [{"skill_id": "...", "success_rate": 0.95, "usage_count": 12}],
          "gaps": [{"description": "...", "request_count": 3}],
          "goals": [{"description": "...", "suggested_action": "..."}],
          "narrative": "A brief 1-2 sentence summary of your growth state."
        }
        ```

        Be honest and specific. Identify your strongest skills, biggest gaps,
        and one concrete goal for improvement.
        """;

    // --- Trigger evaluation ---

    /**
     * Whether an assessment should be triggered now.
     *
     * @param tracker         Skill usage tracker
     * @param lastAssessment  When the last assessment was performed (null = never)
     * @param isSleepCycle    Whether this is a sleep cycle trigger
     * @return true if assessment should run
     */
    public static boolean shouldTrigger(SkillUsageTracker tracker,
                                         Instant lastAssessment,
                                         boolean isSleepCycle) {
        // Sleep cycle always triggers
        if (isSleepCycle) return true;

        // Gap accumulation trigger
        if (tracker != null && tracker.shouldTriggerAssessment()) return true;

        // Periodic trigger
        if (lastAssessment == null) return tracker != null && tracker.totalInvocations() > 0;
        return Duration.between(lastAssessment, Instant.now()).compareTo(PERIODIC_INTERVAL) > 0;
    }

    // --- Prompt building ---

    /**
     * Build the user prompt for self-assessment inference.
     * Contains skill usage summary and gap data.
     */
    public static String buildAssessmentPrompt(SkillUsageTracker tracker) {
        if (tracker == null) return "No skill usage data available.";

        var sb = new StringBuilder();
        sb.append("Here is my skill usage data:\n\n");
        sb.append(tracker.buildSummary(10));

        var triggeredGaps = tracker.triggeredGaps();
        if (!triggeredGaps.isEmpty()) {
            sb.append("\nTriggered gaps (recurring failures):\n");
            for (var gap : triggeredGaps) {
                sb.append("- ").append(gap.description())
                  .append(" (").append(gap.occurrences()).append(" occurrences)\n");
            }
        }

        sb.append("\nTotal skills tracked: ").append(tracker.trackedSkillCount());
        sb.append("\nTotal invocations: ").append(tracker.totalInvocations());

        return sb.toString();
    }

    /**
     * Get the system prompt for the assessment inference call.
     */
    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    // --- Response parsing ---

    /**
     * Parse the LLM response into a SelfAssessment record.
     * Extracts JSON from markdown code blocks if present.
     *
     * @param llmOutput Raw LLM output text
     * @return Parsed assessment, or a minimal assessment on parse failure
     */
    public static SelfAssessment parseResponse(String llmOutput) {
        var assessmentId = UUID.randomUUID().toString().substring(0, 8);
        var now = Instant.now();

        if (llmOutput == null || llmOutput.isBlank()) {
            return new SelfAssessment(assessmentId, now, List.of(), List.of(), List.of(),
                "Assessment could not be completed.");
        }

        String json = extractJson(llmOutput);
        if (json == null) {
            // Treat the whole output as narrative
            return new SelfAssessment(assessmentId, now, List.of(), List.of(), List.of(),
                llmOutput.strip());
        }

        try {
            var node = Json.mapper().readTree(json);

            var proficiencies = new ArrayList<SelfAssessment.SkillProficiency>();
            if (node.has("proficiencies") && node.get("proficiencies").isArray()) {
                for (JsonNode p : node.get("proficiencies")) {
                    proficiencies.add(new SelfAssessment.SkillProficiency(
                        p.path("skill_id").asText("unknown"),
                        p.path("success_rate").asDouble(0.0),
                        p.path("usage_count").asInt(0)
                    ));
                }
            }

            var gaps = new ArrayList<SelfAssessment.IdentifiedGap>();
            if (node.has("gaps") && node.get("gaps").isArray()) {
                for (JsonNode g : node.get("gaps")) {
                    gaps.add(new SelfAssessment.IdentifiedGap(
                        g.path("description").asText(""),
                        g.path("request_count").asInt(0)
                    ));
                }
            }

            var goals = new ArrayList<SelfAssessment.GrowthGoal>();
            if (node.has("goals") && node.get("goals").isArray()) {
                for (JsonNode g : node.get("goals")) {
                    goals.add(new SelfAssessment.GrowthGoal(
                        g.path("description").asText(""),
                        g.has("suggested_action")
                            ? g.get("suggested_action").asText(null) : null
                    ));
                }
            }

            String narrative = node.has("narrative")
                ? node.get("narrative").asText(null) : null;

            return new SelfAssessment(assessmentId, now, proficiencies, gaps, goals, narrative);

        } catch (Exception e) {
            return new SelfAssessment(assessmentId, now, List.of(), List.of(), List.of(),
                "Assessment parse error: " + e.getMessage());
        }
    }

    // --- Storage ---

    /**
     * Store a completed assessment as a SoulItem in FamilyLocker.
     * Also clears triggered gaps from the tracker.
     *
     * @return The stored SoulItem, or null if storage failed
     */
    public static SoulItem store(SelfAssessment assessment, String creatorDid,
                                  FamilyLocker locker, SkillUsageTracker tracker) {
        if (locker == null || creatorDid == null) return null;

        var item = assessment.toSoulItem(creatorDid);
        try {
            locker.store(item, creatorDid);
            // Clear triggered gaps now that assessment is stored
            if (tracker != null) {
                tracker.clearTriggeredGaps();
            }
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    // --- Internal ---

    /** Extract JSON from ```json ... ``` blocks or bare JSON. */
    static String extractJson(String text) {
        // Try markdown code block first
        int start = text.indexOf("```json");
        if (start >= 0) {
            int blockStart = text.indexOf('\n', start);
            if (blockStart < 0) return null;
            blockStart++;
            int blockEnd = text.indexOf("```", blockStart);
            if (blockEnd < 0) return null;
            return text.substring(blockStart, blockEnd).strip();
        }

        // Try bare JSON object
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }

        return null;
    }
}

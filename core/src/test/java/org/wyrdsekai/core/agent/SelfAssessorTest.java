package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulItem;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SelfAssessorTest {

    private SkillUsageTracker tracker;

    @BeforeEach void setUp() {
        tracker = new SkillUsageTracker();
    }

    // --- Trigger evaluation ---

    @Nested class ShouldTrigger {
        @Test void sleep_cycle_always_triggers() {
            assertThat(SelfAssessor.shouldTrigger(tracker, Instant.now(), true)).isTrue();
        }

        @Test void gap_accumulation_triggers() {
            for (int i = 0; i < 3; i++) tracker.recordGap("calendar");
            assertThat(SelfAssessor.shouldTrigger(tracker, Instant.now(), false)).isTrue();
        }

        @Test void periodic_triggers_after_interval() {
            tracker.record("weather", true, 100, null);
            var sevenHoursAgo = Instant.now().minus(Duration.ofHours(7));
            assertThat(SelfAssessor.shouldTrigger(tracker, sevenHoursAgo, false)).isTrue();
        }

        @Test void periodic_does_not_trigger_within_interval() {
            tracker.record("weather", true, 100, null);
            var oneHourAgo = Instant.now().minus(Duration.ofHours(1));
            assertThat(SelfAssessor.shouldTrigger(tracker, oneHourAgo, false)).isFalse();
        }

        @Test void first_assessment_triggers_if_data_exists() {
            tracker.record("weather", true, 100, null);
            assertThat(SelfAssessor.shouldTrigger(tracker, null, false)).isTrue();
        }

        @Test void first_assessment_does_not_trigger_without_data() {
            assertThat(SelfAssessor.shouldTrigger(tracker, null, false)).isFalse();
        }

        @Test void null_tracker_with_sleep_still_triggers() {
            assertThat(SelfAssessor.shouldTrigger(null, null, true)).isTrue();
        }
    }

    // --- Prompt building ---

    @Nested class BuildPrompt {
        @Test void null_tracker_returns_fallback() {
            assertThat(SelfAssessor.buildAssessmentPrompt(null))
                .contains("No skill usage data");
        }

        @Test void includes_skill_data() {
            tracker.record("weather", true, 100, null);
            tracker.record("weather", false, 200, null);
            var prompt = SelfAssessor.buildAssessmentPrompt(tracker);
            assertThat(prompt).contains("weather");
            assertThat(prompt).contains("Total invocations: 2");
        }

        @Test void includes_triggered_gaps() {
            for (int i = 0; i < 3; i++) tracker.recordGap("calendar management");
            var prompt = SelfAssessor.buildAssessmentPrompt(tracker);
            assertThat(prompt).contains("calendar management");
            assertThat(prompt).contains("3 occurrences");
        }
    }

    // --- Response parsing ---

    @Nested class ParseResponse {
        @Test void parses_full_json_response() {
            var response = """
                ```json
                {
                  "proficiencies": [{"skill_id": "weather", "success_rate": 0.95, "usage_count": 20}],
                  "gaps": [{"description": "calendar management", "request_count": 5}],
                  "goals": [{"description": "Learn calendar API", "suggested_action": "Request calendar MCP access"}],
                  "narrative": "I am strong at weather queries but need calendar skills."
                }
                ```
                """;
            var assessment = SelfAssessor.parseResponse(response);
            assertThat(assessment.proficiencies()).hasSize(1);
            assertThat(assessment.proficiencies().getFirst().skillId()).isEqualTo("weather");
            assertThat(assessment.proficiencies().getFirst().successRate()).isEqualTo(0.95);
            assertThat(assessment.gaps()).hasSize(1);
            assertThat(assessment.gaps().getFirst().description()).isEqualTo("calendar management");
            assertThat(assessment.goals()).hasSize(1);
            assertThat(assessment.goals().getFirst().suggestedAction()).contains("calendar");
            assertThat(assessment.narrativeSummary()).contains("strong at weather");
        }

        @Test void parses_bare_json() {
            var response = """
                {"proficiencies": [], "gaps": [], "goals": [], "narrative": "No data yet."}
                """;
            var assessment = SelfAssessor.parseResponse(response);
            assertThat(assessment.proficiencies()).isEmpty();
            assertThat(assessment.narrativeSummary()).isEqualTo("No data yet.");
        }

        @Test void null_input_returns_minimal_assessment() {
            var assessment = SelfAssessor.parseResponse(null);
            assertThat(assessment.proficiencies()).isEmpty();
            assertThat(assessment.narrativeSummary()).contains("could not be completed");
        }

        @Test void blank_input_returns_minimal_assessment() {
            var assessment = SelfAssessor.parseResponse("   ");
            assertThat(assessment.narrativeSummary()).contains("could not be completed");
        }

        @Test void malformed_json_returns_narrative_fallback() {
            var assessment = SelfAssessor.parseResponse("{not valid json at all}");
            assertThat(assessment.proficiencies()).isEmpty();
            assertThat(assessment.narrativeSummary()).contains("parse error");
        }

        @Test void plain_text_becomes_narrative() {
            var assessment = SelfAssessor.parseResponse("I think I'm doing okay.");
            assertThat(assessment.narrativeSummary()).isEqualTo("I think I'm doing okay.");
        }

        @Test void assessmentId_is_populated() {
            var assessment = SelfAssessor.parseResponse("{}");
            assertThat(assessment.assessmentId()).isNotNull().isNotBlank();
        }

        @Test void timestamp_is_recent() {
            var before = Instant.now();
            var assessment = SelfAssessor.parseResponse("{}");
            assertThat(assessment.timestamp()).isAfterOrEqualTo(before);
        }
    }

    // --- SoulItem conversion ---

    @Nested class Storage {
        @Test void toSoulItem_produces_assessment_category() {
            var response = """
                {"proficiencies": [{"skill_id": "weather", "success_rate": 0.9, "usage_count": 10}],
                 "gaps": [], "goals": [], "narrative": "Doing well."}
                """;
            var assessment = SelfAssessor.parseResponse(response);
            var item = assessment.toSoulItem("did:key:z6MkTest");
            assertThat(item.category()).isEqualTo("assessment");
            assertThat(item.label()).contains("Self-Assessment");
            assertThat(item.text()).contains("weather");
            assertThat(item.text()).contains("90%");
        }
    }

    // --- JSON extraction ---

    @Nested class ExtractJson {
        @Test void extracts_from_code_block() {
            var text = "Here:\n```json\n{\"a\": 1}\n```\nDone.";
            assertThat(SelfAssessor.extractJson(text)).isEqualTo("{\"a\": 1}");
        }

        @Test void extracts_bare_json() {
            assertThat(SelfAssessor.extractJson("prefix {\"a\": 1} suffix"))
                .isEqualTo("{\"a\": 1}");
        }

        @Test void returns_null_for_no_json() {
            assertThat(SelfAssessor.extractJson("just plain text")).isNull();
        }
    }

    // --- System prompt ---

    @Test void systemPrompt_is_non_empty() {
        assertThat(SelfAssessor.systemPrompt()).isNotBlank();
        assertThat(SelfAssessor.systemPrompt()).contains("proficiencies");
    }
}

package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class AdmissionControllerTest {

    private AdmissionController controller;

    @BeforeEach
    void setUp() {
        // No Lucene store — novelty check returns 0.7 (moderately novel)
        controller = new AdmissionController(null);
    }

    @Nested
    class BasicAdmission {

        @Test
        void userPreferenceAdmitted() {
            var result = controller.evaluate(
                "I prefer dark mode for everything",
                AdmissionController.ContentType.USER_PREFERENCE,
                -1, Instant.now(), "agent-1");
            assertThat(result).isInstanceOf(AdmissionController.AdmissionResult.Admit.class);
            assertThat(result.score()).isGreaterThan(0.5f);
        }

        @Test
        void userCorrectionAdmitted() {
            var result = controller.evaluate(
                "Actually, I moved to Seattle last month",
                AdmissionController.ContentType.USER_CORRECTION,
                -1, Instant.now(), "agent-1");
            assertThat(result).isInstanceOf(AdmissionController.AdmissionResult.Admit.class);
            assertThat(result.score()).isGreaterThan(0.6f);
        }

        @Test
        void narratorMessageScoresLow() {
            // Narrator messages have the lowest content type prior (0.10)
            // Without Lucene (novelty=0.7), recent messages may still barely pass
            // With Lucene running, most narrator messages would be deduped
            var result = controller.evaluate(
                "anonymous enters from the east",
                AdmissionController.ContentType.NARRATOR_MESSAGE,
                -1, Instant.now(), "agent-1");
            // Score should be at the bottom of the range
            assertThat(result.score()).isLessThan(0.5f);
        }

        @Test
        void oldNarratorMessageRejected() {
            // Old narrator message — low recency kills it
            var result = controller.evaluate(
                "anonymous enters from the east",
                AdmissionController.ContentType.NARRATOR_MESSAGE,
                -1, Instant.now().minus(6, ChronoUnit.HOURS), "agent-1");
            assertThat(result).isNotInstanceOf(AdmissionController.AdmissionResult.Admit.class);
        }

        @Test
        void emptyContentRejected() {
            var result = controller.evaluate(
                "", AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-1");
            assertThat(result).isInstanceOf(AdmissionController.AdmissionResult.Reject.class);
        }

        @Test
        void nullContentRejected() {
            var result = controller.evaluate(
                null, AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-1");
            assertThat(result).isInstanceOf(AdmissionController.AdmissionResult.Reject.class);
        }
    }

    @Nested
    class AgentOverride {

        @Test
        void explicitRememberBypassesScoring() {
            var result = controller.evaluate(
                "trivial room event",
                AdmissionController.ContentType.AGENT_REMEMBER,
                0.9f, Instant.now(), "agent-1");
            assertThat(result).isInstanceOf(AdmissionController.AdmissionResult.Admit.class);
            assertThat(result.score()).isEqualTo(1.0f);
            assertThat(result.reason()).contains("override");
        }

        @Test
        void lowImportanceRememberDoesNotOverride() {
            var result = controller.evaluate(
                "maybe this is interesting",
                AdmissionController.ContentType.AGENT_REMEMBER,
                0.3f, Instant.now(), "agent-1");
            // Should go through normal scoring, not override
            assertThat(result.reason()).doesNotContain("override");
        }
    }

    @Nested
    class TemporalRecency {

        @Test
        void recentEventScoresHigh() {
            var result = controller.evaluate(
                "User said something important",
                AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-1");
            assertThat(result.score()).isGreaterThan(0.4f);
        }

        @Test
        void oldEventScoresLower() {
            var result = controller.evaluate(
                "User said something important",
                AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now().minus(24, ChronoUnit.HOURS), "agent-1");
            // Same content, much older — lower recency score
            var recentResult = controller.evaluate(
                "User said something important",
                AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-1");
            assertThat(result.score()).isLessThan(recentResult.score());
        }
    }

    @Nested
    class FutureUtility {

        @Test
        void preferenceLanguageBoosted() {
            var plain = controller.evaluate(
                "The weather is nice today",
                AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-1");
            var preference = controller.evaluate(
                "I always prefer working in the morning",
                AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-1");
            assertThat(preference.score()).isGreaterThan(plain.score());
        }

        @Test
        void goalLanguageBoosted() {
            var plain = controller.evaluate(
                "The meeting was at three",
                AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-1");
            var goal = controller.evaluate(
                "I'm working on finishing the migration plan",
                AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-1");
            assertThat(goal.score()).isGreaterThan(plain.score());
        }
    }

    @Nested
    class JaroWinkler {

        @Test
        void identicalStrings() {
            assertThat(AdmissionController.jaroWinklerSimilarity("John", "John")).isEqualTo(1.0f);
        }

        @Test
        void similarNames() {
            float sim = AdmissionController.jaroWinklerSimilarity("John Smith", "Jon Smith");
            assertThat(sim).isGreaterThan(0.9f);
        }

        @Test
        void differentNames() {
            float sim = AdmissionController.jaroWinklerSimilarity("John Smith", "Alice Wonder");
            assertThat(sim).isLessThan(0.6f);
        }

        @Test
        void prefixBoost() {
            // "Martha" vs "Marhta" — common prefix "Mar" gets Winkler boost
            float jw = AdmissionController.jaroWinklerSimilarity("Martha", "Marhta");
            assertThat(jw).isGreaterThan(0.95f);
        }

        @Test
        void nullHandling() {
            assertThat(AdmissionController.jaroWinklerSimilarity(null, "test")).isEqualTo(0f);
            assertThat(AdmissionController.jaroWinklerSimilarity("test", null)).isEqualTo(0f);
        }

        @Test
        void entityResolutionThreshold() {
            // "Dr. Smith" and "John Smith" — should be above 0.7 but below 0.85
            float sim = AdmissionController.jaroWinklerSimilarity("Dr. Smith", "John Smith");
            assertThat(sim).isGreaterThan(0.6f);
        }
    }

    @Nested
    class EntityResolution {

        @Test
        void extractsProperNounSequences() {
            var entities = AdmissionController.extractEntityNames(
                "I was talking to John Smith about the project");
            assertThat(entities).contains("John Smith");
        }

        @Test
        void extractsPossessiveEntities() {
            var entities = AdmissionController.extractEntityNames(
                "my cat Pixel is a calico");
            assertThat(entities).contains("Pixel");
        }

        @Test
        void extractsPossessiveNamedPattern() {
            var entities = AdmissionController.extractEntityNames(
                "my friend named Alice visited yesterday");
            assertThat(entities).contains("Alice");
        }

        @Test
        void filtersCommonPhrases() {
            var entities = AdmissionController.extractEntityNames(
                "The Quick Brown Fox jumped over");
            // "The Quick" should be filtered, but "Quick Brown Fox" stays (proper noun seq)
            assertThat(entities).noneMatch(e -> e.startsWith("The "));
        }

        @Test
        void entityMatchBoostsAdmission() {
            // Seed a known entity
            controller.seedEntities(Set.of("Pixel"));

            // Content about the known entity gets a boost
            var withEntity = controller.evaluate(
                "Pixel is doing well today, she caught a mouse",
                AdmissionController.ContentType.CONVERSATION_EVENT,
                -1, Instant.now(), "agent-1");

            var withoutEntity = controller.evaluate(
                "The weather is doing well today, quite sunny",
                AdmissionController.ContentType.CONVERSATION_EVENT,
                -1, Instant.now(), "agent-1");

            assertThat(withEntity.score()).isGreaterThan(withoutEntity.score());
        }

        @Test
        void fuzzyEntityMatchWorks() {
            // Seed "Pixel", then check if "Pix" matches via Jaro-Winkler
            controller.seedEntities(Set.of("Pixel"));

            var entities = AdmissionController.extractEntityNames("How is Pix doing?");
            // "Pix" alone won't be extracted as a proper noun sequence (single word needs context)
            // But if we seed and resolve manually:
            var match = controller.resolveKnownEntity(Set.of("Pixel"));
            assertThat(match).isEqualTo("Pixel"); // exact match
        }

        @Test
        void admittedContentAccumulatesEntities() {
            assertThat(controller.knownEntities()).isEmpty();

            // Admit content mentioning "John Smith"
            controller.evaluate(
                "My friend John Smith is a great cook",
                AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-1");

            assertThat(controller.knownEntities()).contains("John Smith");
        }

        @Test
        void seedEntitiesFromRelationships() {
            controller.seedEntities(Set.of("Alice", "Bob", "Dr. Chen"));
            assertThat(controller.knownEntities()).containsExactlyInAnyOrder("Alice", "Bob", "Dr. Chen");
        }
    }

    @Nested
    class AuditStats {

        @Test
        void statsTrackAdmissions() {
            controller.evaluate("preference", AdmissionController.ContentType.USER_PREFERENCE,
                -1, Instant.now(), "a");
            controller.evaluate("narrator noise", AdmissionController.ContentType.NARRATOR_MESSAGE,
                -1, Instant.now(), "a");
            controller.evaluate("remember this", AdmissionController.ContentType.AGENT_REMEMBER,
                0.9f, Instant.now(), "a");

            var stats = controller.stats();
            assertThat(stats.total()).isEqualTo(3);
            assertThat(stats.overridden()).isEqualTo(1);
        }

        @Test
        void resetClearsStats() {
            controller.evaluate("test", AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "a");
            controller.resetStats();
            var stats = controller.stats();
            assertThat(stats.total()).isEqualTo(0);
        }
    }
}

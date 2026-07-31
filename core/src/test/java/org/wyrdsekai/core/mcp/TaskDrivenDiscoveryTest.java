package org.wyrdsekai.core.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TaskDrivenDiscovery (§91.3).
 */
class TaskDrivenDiscoveryTest {

    private McpRegistrySyncer syncer;
    private TaskDrivenDiscovery discovery;

    private static final String CAPABILITIES_JSON = """
        [
            {"id":"weather-api","name":"Weather Service","description":"Real-time weather data and forecasts",
             "endpoint":"http://weather.local","transport":"http",
             "source":"mcp-registry","trustScore":0.9,"version":"1.0","discoveredAt":0},
            {"id":"calendar-sync","name":"Calendar Integration","description":"Sync appointments and schedule events",
             "endpoint":"http://cal.local","transport":"http",
             "source":"smithery","trustScore":0.7,"version":"2.1","discoveredAt":0},
            {"id":"email-sender","name":"Email MCP","description":"Send and receive email messages",
             "endpoint":"http://email.local","transport":"http",
             "source":"pulsemcp","trustScore":0.6,"version":"1.0","discoveredAt":0},
            {"id":"home-automation","name":"Smart Home Control","description":"Control lights thermostats and home devices",
             "endpoint":"http://home.local","transport":"http",
             "source":"mcp-registry","trustScore":0.8,"version":"3.0","discoveredAt":0}
        ]""";

    @BeforeEach
    void setup() throws Exception {
        syncer = new McpRegistrySyncer();
        syncer.processDiscoveries(CAPABILITIES_JSON);
        discovery = new TaskDrivenDiscovery(syncer);
    }

    @Nested
    class DiscoverTests {

        @Test
        void discovers_matching_capabilities() {
            var results = discovery.discover("I need to check the weather forecast");
            assertFalse(results.isEmpty());
            assertEquals("weather-api", results.get(0).serviceId());
        }

        @Test
        void ranks_by_keyword_overlap() {
            var results = discovery.discover("send email messages to schedule appointments");
            assertFalse(results.isEmpty());
            // Both email and calendar should match, but with different scores
            assertTrue(results.size() >= 2);
        }

        @Test
        void returns_empty_for_no_match() {
            var results = discovery.discover("quantum computing simulation");
            assertTrue(results.isEmpty());
        }

        @Test
        void returns_empty_for_null_input() {
            assertTrue(discovery.discover(null).isEmpty());
        }

        @Test
        void returns_empty_for_blank_input() {
            assertTrue(discovery.discover("   ").isEmpty());
        }

        @Test
        void discovers_home_automation() {
            var results = discovery.discover("control the lights and thermostat at home");
            assertFalse(results.isEmpty());
            assertTrue(results.stream().anyMatch(s -> s.serviceId().equals("home-automation")));
        }

        @Test
        void confidence_between_zero_and_one() {
            var results = discovery.discover("weather forecast data");
            for (var r : results) {
                assertTrue(r.confidence() > 0.0, "confidence must be > 0");
                assertTrue(r.confidence() <= 1.0, "confidence must be <= 1.0");
            }
        }

        @Test
        void results_sorted_by_confidence_descending() {
            var results = discovery.discover("send email schedule appointment weather");
            for (int i = 1; i < results.size(); i++) {
                assertTrue(results.get(i - 1).confidence() >= results.get(i).confidence(),
                    "results must be sorted by confidence descending");
            }
        }
    }

    @Nested
    class SuggestTests {

        @Test
        void filters_already_installed_services() throws Exception {
            var registry = new McpServiceRegistry();
            registry.loadFromJson("""
                [{"id":"weather-api","name":"Weather","transport":"http",
                  "endpoint":"http://w","tier":"local","enabled":true}]""");

            var results = discovery.suggest("weather forecast", registry);
            assertTrue(results.stream().noneMatch(s -> s.serviceId().equals("weather-api")),
                "already-installed weather-api should be filtered out");
        }

        @Test
        void returns_all_when_none_installed() {
            var registry = new McpServiceRegistry();
            var results = discovery.suggest("weather forecast data", registry);
            assertFalse(results.isEmpty());
        }

        @Test
        void returns_empty_when_all_installed() throws Exception {
            var registry = new McpServiceRegistry();
            registry.loadFromJson("""
                [
                    {"id":"weather-api","name":"W","transport":"http","endpoint":"x","tier":"local","enabled":true},
                    {"id":"calendar-sync","name":"C","transport":"http","endpoint":"x","tier":"local","enabled":true},
                    {"id":"email-sender","name":"E","transport":"http","endpoint":"x","tier":"local","enabled":true},
                    {"id":"home-automation","name":"H","transport":"http","endpoint":"x","tier":"local","enabled":true}
                ]""");

            var results = discovery.suggest("weather email calendar home", registry);
            assertTrue(results.isEmpty());
        }
    }

    @Nested
    class KeywordExtractionTests {

        @Test
        void extracts_meaningful_keywords() {
            var keywords = TaskDrivenDiscovery.extractKeywords(
                "I need to send an email to my friend");
            assertTrue(keywords.contains("send"));
            assertTrue(keywords.contains("email"));
            assertTrue(keywords.contains("friend"));
            // Stop words removed
            assertFalse(keywords.contains("to"));
            assertFalse(keywords.contains("an"));
            assertFalse(keywords.contains("my"));
        }

        @Test
        void handles_null() {
            assertTrue(TaskDrivenDiscovery.extractKeywords(null).isEmpty());
        }

        @Test
        void handles_blank() {
            assertTrue(TaskDrivenDiscovery.extractKeywords("   ").isEmpty());
        }

        @Test
        void deduplicates_keywords() {
            var keywords = TaskDrivenDiscovery.extractKeywords(
                "weather weather weather forecast forecast");
            assertEquals(2, keywords.size());
        }

        @Test
        void strips_punctuation() {
            var keywords = TaskDrivenDiscovery.extractKeywords(
                "Hello! What's the weather? (forecast)");
            assertTrue(keywords.contains("weather"));
            assertTrue(keywords.contains("forecast"));
        }
    }

    @Nested
    class ScoringTests {

        @Test
        void perfect_match_scores_high() {
            double score = TaskDrivenDiscovery.scoreMatch(
                List.of("weather", "forecast"),
                "Weather Forecast Service",
                "Provides weather forecast data",
                "weather-forecast");
            assertEquals(1.0, score, 0.01);
        }

        @Test
        void no_match_scores_zero() {
            double score = TaskDrivenDiscovery.scoreMatch(
                List.of("quantum", "computing"),
                "Weather Service",
                "Weather data",
                "weather-api");
            assertEquals(0.0, score);
        }

        @Test
        void partial_match_scores_between() {
            double score = TaskDrivenDiscovery.scoreMatch(
                List.of("weather", "quantum"),
                "Weather Service",
                "Weather data",
                "weather-api");
            assertTrue(score > 0.0);
            assertTrue(score < 1.0);
        }

        @Test
        void empty_keywords_scores_zero() {
            double score = TaskDrivenDiscovery.scoreMatch(
                List.of(),
                "Weather Service",
                "Weather data",
                "weather-api");
            assertEquals(0.0, score);
        }
    }

    @Test
    void constructor_rejects_null_syncer() {
        assertThrows(NullPointerException.class, () -> new TaskDrivenDiscovery(null));
    }

    @Test
    void suggest_rejects_null_registry() {
        assertThrows(NullPointerException.class,
            () -> discovery.suggest("weather", null));
    }

    @Test
    void reason_contains_matched_keywords() {
        var results = discovery.discover("weather forecast");
        assertFalse(results.isEmpty());
        var first = results.get(0);
        assertTrue(first.reason().contains("weather") || first.reason().contains("forecast"),
            "reason should mention matched keywords");
    }
}

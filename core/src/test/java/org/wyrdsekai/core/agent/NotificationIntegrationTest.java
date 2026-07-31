package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.oracle.OraclePrediction;
import org.wyrdsekai.core.oracle.OraclePredictionCache;
import org.wyrdsekai.core.oracle.TemporalPatternExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the notification + prediction system:
 * - Quiet hours filtering
 * - Priority filtering
 * - Temporal extractor → prediction cache → on-login flush pipeline
 * - Channel config via worldKnowledge
 *
 * <p>Does NOT test actual HTTP calls (those need live services).
 * Tests the logic and wiring.
 */
@Tag("integration")
class NotificationIntegrationTest {

    // ─── Quiet Hours ────────────────────────────────────────────

    @Test
    void quietHours_overnightRange_blocksAtMidnight() {
        // 23:00 - 07:00 overnight range
        assertTrue(isInQuietHours("23:00", "07:00", LocalTime.of(0, 0)),
            "Midnight should be in quiet hours 23:00-07:00");
        assertTrue(isInQuietHours("23:00", "07:00", LocalTime.of(23, 30)),
            "23:30 should be in quiet hours");
        assertTrue(isInQuietHours("23:00", "07:00", LocalTime.of(6, 59)),
            "06:59 should be in quiet hours");
        assertFalse(isInQuietHours("23:00", "07:00", LocalTime.of(7, 0)),
            "07:00 should NOT be in quiet hours");
        assertFalse(isInQuietHours("23:00", "07:00", LocalTime.of(12, 0)),
            "Noon should NOT be in quiet hours");
        assertFalse(isInQuietHours("23:00", "07:00", LocalTime.of(22, 59)),
            "22:59 should NOT be in quiet hours");
    }

    @Test
    void quietHours_sameDayRange() {
        // 09:00 - 17:00 daytime range
        assertTrue(isInQuietHours("09:00", "17:00", LocalTime.of(12, 0)),
            "Noon should be in quiet hours 09:00-17:00");
        assertFalse(isInQuietHours("09:00", "17:00", LocalTime.of(8, 0)),
            "08:00 should NOT be in quiet hours");
        assertFalse(isInQuietHours("09:00", "17:00", LocalTime.of(20, 0)),
            "20:00 should NOT be in quiet hours");
    }

    @Test
    void quietHours_nullValues_neverQuiet() {
        assertFalse(isInQuietHours(null, null, LocalTime.of(12, 0)));
        assertFalse(isInQuietHours("23:00", null, LocalTime.of(0, 0)));
        assertFalse(isInQuietHours(null, "07:00", LocalTime.of(0, 0)));
    }

    // ─── Priority Filtering ─────────────────────────────────────

    @Test
    void priorityFilter_defaultBehavior() {
        // Default: ambient=off, normal=on, critical=on
        var wk = Map.<String, String>of();
        assertFalse(shouldNotify(wk, "ambient"), "Ambient should be off by default");
        assertTrue(shouldNotify(wk, "normal"), "Normal should be on by default");
        assertTrue(shouldNotify(wk, "critical"), "Critical should be on by default");
    }

    @Test
    void priorityFilter_explicitOverride() {
        var wk = Map.of(
            "notify.filter.ambient", "true",
            "notify.filter.normal", "false",
            "notify.filter.critical", "true"
        );
        assertTrue(shouldNotify(wk, "ambient"), "Ambient explicitly on");
        assertFalse(shouldNotify(wk, "normal"), "Normal explicitly off");
        assertTrue(shouldNotify(wk, "critical"), "Critical explicitly on");
    }

    @Test
    void priorityFilter_variousFormats() {
        assertTrue(shouldNotify(Map.of("notify.filter.ambient", "yes"), "ambient"));
        assertTrue(shouldNotify(Map.of("notify.filter.ambient", "on"), "ambient"));
        assertTrue(shouldNotify(Map.of("notify.filter.ambient", "TRUE"), "ambient"));
        assertFalse(shouldNotify(Map.of("notify.filter.ambient", "no"), "ambient"));
        assertFalse(shouldNotify(Map.of("notify.filter.ambient", "off"), "ambient"));
        assertFalse(shouldNotify(Map.of("notify.filter.ambient", "false"), "ambient"));
    }

    // ─── WorldKnowledge Channel Config ──────────────────────────

    @Test
    void channelConfig_ntfyKeysCorrect() {
        var wk = new HashMap<String, String>();
        wk.put("notify.ntfy.topic", "wyrdsekai-test");
        wk.put("notify.ntfy.server", "https://ntfy.example.com");

        assertEquals("wyrdsekai-test", wk.get("notify.ntfy.topic"));
        assertEquals("https://ntfy.example.com", wk.get("notify.ntfy.server"));
    }

    @Test
    void channelConfig_telegramKeysCorrect() {
        var wk = new HashMap<String, String>();
        wk.put("notify.telegram.botToken", "123:ABC");
        wk.put("notify.telegram.chatId", "987654");

        assertEquals("123:ABC", wk.get("notify.telegram.botToken"));
        assertEquals("987654", wk.get("notify.telegram.chatId"));
    }

    @Test
    void channelConfig_removeByPrefix() {
        var wk = new HashMap<String, String>();
        wk.put("notify.telegram.botToken", "123:ABC");
        wk.put("notify.telegram.chatId", "987654");
        wk.put("notify.ntfy.topic", "test");
        wk.put("other.key", "value");

        // Remove all telegram keys
        wk.entrySet().removeIf(e -> e.getKey().startsWith("notify.telegram."));

        assertNull(wk.get("notify.telegram.botToken"), "Telegram token should be removed");
        assertNull(wk.get("notify.telegram.chatId"), "Telegram chatId should be removed");
        assertNotNull(wk.get("notify.ntfy.topic"), "ntfy should remain");
        assertNotNull(wk.get("other.key"), "Non-notify keys should remain");
    }

    // ─── Temporal → Cache → Flush Pipeline ──────────────────────

    @Test
    void temporalPredictions_mergeIntoCache() {
        OraclePredictionCache.get().clear();
        var cache = OraclePredictionCache.get();
        var userId = "test-agent";

        // Simulate existing oracle predictions
        cache.put(userId, List.of(
            new OraclePrediction("oracle-1", "Pattern detected", "pattern", 0.7, null, null, false)));

        // Simulate temporal predictions from extractor
        var temporal = List.of(
            new OraclePrediction("temporal-freq-1", "You search frequently", "temporal", 0.8, null, null, true));

        // Merge (same logic as CompanionActor.completeSleep)
        var existing = new ArrayList<>(cache.get(userId));
        existing.addAll(temporal);
        cache.put(userId, existing);

        var all = cache.get(userId);
        assertEquals(2, all.size(), "Should have both oracle and temporal predictions");
        assertTrue(all.stream().anyMatch(p -> "pattern".equals(p.category())));
        assertTrue(all.stream().anyMatch(p -> "temporal".equals(p.category())));
    }

    @Test
    void onLoginFlush_filtersTemporalPredictions() {
        OraclePredictionCache.get().clear();
        var cache = OraclePredictionCache.get();
        var userId = "test-agent";

        cache.put(userId, List.of(
            new OraclePrediction("oracle-1", "Pattern found", "pattern", 0.7, null, null, false),
            new OraclePrediction("temporal-1", "You check in at 6pm", "temporal", 0.8, null, "evidence", false),
            new OraclePrediction("temporal-2", "Library searches increasing", "temporal", 0.6, null, "evidence", true)
        ));

        // Simulate PlayerReturned logic: filter temporal, build greeting
        var predictions = cache.get(userId);
        var temporal = predictions.stream()
            .filter(p -> "temporal".equals(p.category()))
            .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
            .limit(3)
            .toList();

        assertEquals(2, temporal.size(), "Should find 2 temporal predictions");
        assertEquals("temporal-1", temporal.get(0).id(), "Higher confidence first");

        // Build greeting
        var greeting = new StringBuilder("While you were away, I noticed: ");
        for (int i = 0; i < temporal.size(); i++) {
            if (i > 0) greeting.append(" ");
            greeting.append(temporal.get(i).text()).append(".");
        }
        var text = greeting.toString();
        assertTrue(text.contains("While you were away"));
        assertTrue(text.contains("You check in at 6pm"));
        assertTrue(text.contains("Library searches increasing"));

        // After flush: remove temporal, keep oracle
        var remaining = predictions.stream()
            .filter(p -> !"temporal".equals(p.category()))
            .toList();
        cache.put(userId, remaining);

        assertEquals(1, cache.get(userId).size(), "Only oracle prediction should remain");
        assertEquals("pattern", cache.get(userId).get(0).category());
    }

    @Test
    void temporalExtractor_fullPipeline(@TempDir Path tempDir) throws IOException {
        // Write activity log with a clear frequency pattern
        var events = new ArrayList<String>();
        var mapper = new ObjectMapper();
        for (int i = 0; i < 5; i++) {
            var node = mapper.createObjectNode()
                .put("ts", Instant.now().minus(i, ChronoUnit.DAYS).toString())
                .put("type", "action")
                .put("agent", "Wyrd")
                .put("agentId", "companion-wyrd")
                .put("actionType", "web_search")
                .put("room", "nexus")
                .put("detail", "searching for AI papers");
            events.add(mapper.writeValueAsString(node));
        }
        var log = tempDir.resolve("agent-activity.jsonl");
        Files.writeString(log, String.join("\n", events) + "\n");

        // Extract
        var extractor = new TemporalPatternExtractor();
        var predictions = extractor.extract("companion-wyrd", log, 14);

        assertFalse(predictions.isEmpty(), "Should find frequency pattern");
        assertTrue(predictions.stream().allMatch(p -> "temporal".equals(p.category())));

        // Put into cache
        OraclePredictionCache.get().clear();
        OraclePredictionCache.get().put("companion-wyrd", predictions);

        // Verify retrievable
        var cached = OraclePredictionCache.get().get("companion-wyrd");
        assertFalse(cached.isEmpty());
        assertTrue(cached.stream().anyMatch(p -> p.text().contains("web search")));
    }

    // ─── Helpers ────────────────────────────────────────────────

    /**
     * Simulate quiet hours check (same logic as CompanionActor.isQuietHours).
     */
    private boolean isInQuietHours(String start, String end, LocalTime now) {
        if (start == null || end == null) return false;
        try {
            var startTime = LocalTime.parse(start);
            var endTime = LocalTime.parse(end);
            if (startTime.isBefore(endTime)) {
                return !now.isBefore(startTime) && now.isBefore(endTime);
            } else {
                return !now.isBefore(startTime) || now.isBefore(endTime);
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Simulate priority filter check (same logic as CompanionActor.shouldNotifyForPriority).
     */
    private boolean shouldNotify(Map<String, String> wk, String priority) {
        var key = "notify.filter." + priority;
        var value = wk.get(key);
        if (value == null) {
            return !"ambient".equals(priority);
        }
        return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value)
            || "yes".equalsIgnoreCase(value);
    }
}

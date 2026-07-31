package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #32 item 6 (closing-verify 8d3a172b): "what's the weather in san francisco
 * TOMORROW?" was answered with the "now" line. Both weather items now honor a
 * day hint — "tomorrow"/"today", an ISO date, M/D, or a month-name date — and
 * lead the answer with THAT day's forecast entry, labeled with the day.
 *
 * <p>Runs the REAL bundled scripts in the production sandbox with stubbed
 * maps/openweather adapters.</p>
 */
class WeatherDayHintTest {

    private ItemScriptExecutor executor;
    private String tripPlannerSource;
    private String morningBriefingSource;

    // The scripts derive "today" from world.time.iso() (UTC instant).
    private final LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
    private final LocalDate tomorrowUtc = todayUtc.plusDays(1);

    @BeforeEach
    void setUp() throws IOException {
        executor = new ItemScriptExecutor();
        tripPlannerSource = Files.readString(locateScript("trip_planner.js"));
        morningBriefingSource = Files.readString(locateScript("morning_briefing.js"));
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private Map<String, Object> forecastDataWithTomorrow() {
        var data = new LinkedHashMap<String, Object>();
        data.put("place", "San Francisco");
        data.put("daily", List.of(
            Map.of("date", todayUtc.toString(), "low_f", 65L, "high_f", 73L,
                "conditions", "overcast clouds"),
            Map.of("date", tomorrowUtc.toString(), "low_f", 58L, "high_f", 78L,
                "conditions", "overcast clouds")));
        data.put("text", "San Francisco — today and tomorrow digest");
        return data;
    }

    // ── trip_planner (forecast-only branch) ──────────────────────────────────

    @Test
    void tripPlannerTomorrowHintLeadsWithTomorrowsEntry() {
        var result = executor.execute("trip_planner", tripPlannerSource,
            Map.of("query", "what's the weather in san francisco tomorrow?"),
            new WeatherStubProvider(forecastDataWithTomorrow()));

        assertEquals(true, result.get("ok"), "forecast-only branch must succeed: " + result);
        var findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("(tomorrow)"),
            "answer must be labeled with the requested day: " + findings);
        assertTrue(findings.contains("low 58F, high 78F"),
            "answer must carry TOMORROW's entry (58/78), not today's (65/73): " + findings);
        assertFalse(findings.startsWith("Forecast for San Francisco, CA: San Francisco —"),
            "the un-hinted digest lead must not be the primary answer: " + findings);
    }

    @Test
    void tripPlannerExplicitIsoDatePicksThatEntry() {
        var data = new LinkedHashMap<String, Object>();
        data.put("place", "San Francisco");
        data.put("daily", List.of(
            Map.of("date", "2099-01-02", "low_f", 40L, "high_f", 52L,
                "conditions", "clear sky")));
        data.put("text", "San Francisco — Fri 1/2: low 40F high 52F, clear sky");

        var result = executor.execute("trip_planner", tripPlannerSource,
            Map.of("query", "weather in san francisco on 2099-01-02"),
            new WeatherStubProvider(data));

        assertEquals(true, result.get("ok"));
        var findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("low 40F, high 52F"),
            "explicit date must select the matching daily entry: " + findings);
    }

    @Test
    void tripPlannerWithoutDayHintKeepsFullDigest() {
        var result = executor.execute("trip_planner", tripPlannerSource,
            Map.of("destination", "san francisco"),
            new WeatherStubProvider(forecastDataWithTomorrow()));

        assertEquals(true, result.get("ok"));
        var findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("today and tomorrow digest"),
            "no hint → unchanged full-digest answer: " + findings);
    }

    @Test
    void tripPlannerDayOutsideWindowIsHonest() {
        var result = executor.execute("trip_planner", tripPlannerSource,
            Map.of("query", "weather in san francisco on 2099-06-30"),
            new WeatherStubProvider(forecastDataWithTomorrow()));

        assertEquals(true, result.get("ok"));
        var findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("no entry for"),
            "a day beyond the forecast window must be named honestly: " + findings);
    }

    // ── morning_briefing ─────────────────────────────────────────────────────

    @Test
    void morningBriefingTomorrowHintLeadsWithTomorrowsEntry() {
        var result = executor.execute("morning_briefing", morningBriefingSource,
            Map.of("query", "san francisco forecast for tomorrow"),
            new WeatherStubProvider(forecastDataWithTomorrow()));

        assertEquals(true, result.get("ok"), "briefing must succeed: " + result);
        var findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("(tomorrow)"),
            "answer must be labeled with the requested day: " + findings);
        assertTrue(findings.contains("low 58F, high 78F"),
            "answer must lead with TOMORROW's entry: " + findings);
        assertFalse(findings.startsWith("Morning briefing for san francisco forecast for tomorrow: now —"),
            "the 'now' line must not lead a tomorrow question: " + findings);
    }

    @Test
    void morningBriefingWithoutHintKeepsNowLead() {
        var result = executor.execute("morning_briefing", morningBriefingSource,
            Map.of("address", "san francisco"),
            new WeatherStubProvider(forecastDataWithTomorrow()));

        assertEquals(true, result.get("ok"));
        var findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("now — overcast clouds, 63F"),
            "no hint → current-conditions lead unchanged: " + findings);
    }

    @Test
    void morningBriefingMissingRequestedDayIsHonest() {
        var data = new LinkedHashMap<String, Object>();
        data.put("place", "San Francisco");
        data.put("daily", List.of(
            Map.of("date", todayUtc.toString(), "low_f", 65L, "high_f", 73L,
                "conditions", "overcast clouds")));
        data.put("text", "San Francisco — today only");

        var result = executor.execute("morning_briefing", morningBriefingSource,
            Map.of("query", "san francisco weather tomorrow"),
            new WeatherStubProvider(data));

        assertEquals(true, result.get("ok"));
        var findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("no forecast entry for"),
            "a requested day the window doesn't cover must be named honestly: " + findings);
    }

    private static Path locateScript(String name) {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            var candidate = dir.resolve("scripts").resolve("items").resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("scripts/items/" + name + " not locatable from test cwd");
    }

    /** Minimal provider: live maps.geocode + openweather.current/forecast stubs. */
    static class WeatherStubProvider implements ItemWorldApiProvider {
        private final Map<String, Object> forecastData;

        WeatherStubProvider(Map<String, Object> forecastData) {
            this.forecastData = forecastData;
        }

        @Override public Set<String> adapterNamespaces() { return Set.of("maps", "openweather"); }

        @Override
        public Map<String, Object> invokeAdapter(String ns, String method, Map<String, Object> args) {
            if ("maps".equals(ns) && "geocode".equals(method)) {
                return Map.of("success", true, "data", Map.of(
                    "formatted_address", "San Francisco, CA",
                    "coords", Map.of("lat", 37.77, "lon", -122.42)));
            }
            if ("openweather".equals(ns) && "forecast".equals(method)) {
                return Map.of("success", true, "data", forecastData);
            }
            if ("openweather".equals(ns) && "current".equals(method)) {
                return Map.of("success", true, "data", Map.of(
                    "text", "overcast clouds, 63F (feels like 63F), humidity 80%, "
                        + "wind 6 mph — San Francisco"));
            }
            return Map.of("success", false, "error", Map.of(
                "code", "adapter_unavailable", "message", ns + "." + method, "retryable", false));
        }

        // ── required (non-default) surface: inert stubs ──
        @Override public List<Map<String, Object>> searchKnowledge(String q, int limit) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int n) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String content) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    }
}

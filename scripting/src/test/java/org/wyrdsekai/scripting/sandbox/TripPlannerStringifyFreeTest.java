package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #31 item 2 (post-restart verify 2137ea49) — trip_planner spoke a literal
 * "{" because it ran {@code JSON.stringify(fc.data)} on a Java host map,
 * which GraalJS cannot serialize. The script now prefers the adapter's
 * preformatted {@code fc.data.text} digest and, when text is absent
 * (older adapter), composes a readable line from the structured daily list —
 * either way the spoken findings are stringify-free.
 *
 * <p>Runs the REAL bundled {@code scripts/items/trip_planner.js} in the
 * production sandbox with stubbed maps/openweather adapters.</p>
 */
class TripPlannerStringifyFreeTest {

    private ItemScriptExecutor executor;
    private String tripPlannerSource;

    @BeforeEach
    void setUp() throws IOException {
        executor = new ItemScriptExecutor();
        var script = locateScript();
        assertNotNull(script, "scripts/items/trip_planner.js must be locatable from the test cwd");
        tripPlannerSource = Files.readString(script);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void weatherOnlyBranchPrefersAdapterTextDigest() {
        var forecastData = new LinkedHashMap<String, Object>();
        forecastData.put("place", "San Francisco");
        forecastData.put("daily", List.of(
            Map.of("date", "2026-07-13", "low_f", 58L, "high_f", 71L, "conditions", "clear sky")));
        forecastData.put("text",
            "San Francisco — Mon 7/13: low 58F high 71F, clear sky");

        var result = executor.execute("trip_planner", tripPlannerSource,
            Map.of("destination", "san francisco"),
            new WeatherStubProvider(forecastData));

        assertEquals(true, result.get("ok"), "weather-only branch must succeed: " + result);
        var findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("Forecast for San Francisco, CA"),
            "findings must carry the geocoder's canonical place: " + findings);
        assertTrue(findings.contains("Mon 7/13: low 58F high 71F, clear sky"),
            "findings must voice the adapter text digest: " + findings);
        assertFalse(findings.contains("{"),
            "findings must never contain stringified host-object braces: " + findings);
    }

    @Test
    void weatherOnlyBranchComposesFromDailyWhenTextAbsent() {
        // Older adapter shape: structured daily list, no text field.
        var forecastData = new LinkedHashMap<String, Object>();
        forecastData.put("place", "San Francisco");
        forecastData.put("daily", List.of(
            Map.of("date", "2026-07-13", "low_f", 58L, "high_f", 71L, "conditions", "clear sky"),
            Map.of("date", "2026-07-14", "low_f", 60L, "high_f", 74L, "conditions", "few clouds")));

        var result = executor.execute("trip_planner", tripPlannerSource,
            Map.of("destination", "san francisco"),
            new WeatherStubProvider(forecastData));

        assertEquals(true, result.get("ok"));
        var findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("2026-07-13: low 58F high 71F, clear sky"),
            "fallback must compose per-day lines from the structured list: " + findings);
        assertTrue(findings.contains("2026-07-14: low 60F high 74F, few clouds"), findings);
        assertFalse(findings.contains("{"),
            "fallback findings must be stringify-free too: " + findings);
    }

    private static Path locateScript() {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            var candidate = dir.resolve("scripts").resolve("items").resolve("trip_planner.js");
            if (Files.isRegularFile(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }

    /** Minimal provider: live maps.geocode + openweather.forecast stubs. */
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

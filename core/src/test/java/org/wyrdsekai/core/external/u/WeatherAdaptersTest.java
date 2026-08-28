package org.wyrdsekai.core.external.u;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.List;

/** §4.41 weather adapter contract tests. */
class WeatherAdaptersTest {

    @BeforeEach
    void setup() { CredentialResolver.get().resetForTests(); }
    @AfterEach
    void teardown() { CredentialResolver.get().resetForTests(); }

    private AdapterRequest req(String ns, String method) {
        return new AdapterRequest(ns, method, Map.of("lat", 40.0, "lon", -74.0),
            ItemCapabilitySet.UNRESTRICTED, null);
    }

    @Test
    void openweather_declares_three_methods() {
        var caps = new OpenWeatherAdapter().capabilities();
        assertEquals(3, caps.size());
        assertTrue(caps.contains("current"));
        assertTrue(caps.contains("forecast"));
        assertTrue(caps.contains("alerts"));
    }

    @Test
    void openweather_credential_slot_matches_spec() {
        assertEquals("openweathermap.api_key", new OpenWeatherAdapter().credentialSlot());
    }

    @Test
    void openweather_without_key_returns_credential_missing() {
        var resp = new OpenWeatherAdapter().invoke(req("openweather", "current"));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void openweather_alerts_with_key_returns_stub() {
        // current + forecast went LIVE on 2026-07-11 (a keyed forecast call now
        // reaches upstream — no longer unit-testable offline); alerts is the
        // remaining scaffolding method and keeps the stub contract.
        CredentialResolver.get().setSafeReader(slot ->
            "openweathermap.api_key".equals(slot) ? Optional.of("k") : Optional.empty());
        var resp = new OpenWeatherAdapter().invoke(req("openweather", "alerts"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void weatherapi_declares_two_methods() {
        var caps = new WeatherAPIAdapter().capabilities();
        assertEquals(2, caps.size());
        assertTrue(caps.contains("current"));
        assertTrue(caps.contains("forecast"));
    }

    @Test
    void weatherapi_without_key_returns_credential_missing() {
        var resp = new WeatherAPIAdapter().invoke(req("weatherapi", "current"));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void visualcrossing_declares_three_methods() {
        var caps = new VisualCrossingAdapter().capabilities();
        assertEquals(3, caps.size());
        assertTrue(caps.contains("current"));
        assertTrue(caps.contains("forecast"));
        assertTrue(caps.contains("history"));
    }

    @Test
    void visualcrossing_credential_slot() {
        assertEquals("visualcrossing.api_key",
            new VisualCrossingAdapter().credentialSlot());
    }

    @Test
    void visualcrossing_with_key_returns_stub() {
        CredentialResolver.get().setSafeReader(slot ->
            "visualcrossing.api_key".equals(slot) ? Optional.of("k") : Optional.empty());
        var resp = new VisualCrossingAdapter().invoke(req("visualcrossing", "history"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void all_weather_namespaces_distinct() {
        assertEquals("openweather", new OpenWeatherAdapter().namespace());
        assertEquals("weatherapi", new WeatherAPIAdapter().namespace());
        assertEquals("visualcrossing", new VisualCrossingAdapter().namespace());
    }

    // ── #31 item 2: preformatted "text" digest field ─────────────────────
    // GraalJS's JSON.stringify can't serialize Java host maps, so scripts
    // (trip_planner, morning_briefing) read data.text instead of stringifying
    // the structured digest. These pin the format the scripts voice.

    @Test
    void openweather_current_text_digest_format() {
        var digest = new LinkedHashMap<String, Object>();
        digest.put("conditions", "clear sky");
        digest.put("temp_f", 70.7);
        digest.put("feels_like_f", 68.9);
        digest.put("humidity_pct", 40);
        digest.put("wind_mph", 5.4);
        digest.put("place", "San Jose");
        assertEquals("clear sky, 71F (feels like 69F), humidity 40%, wind 5 mph — San Jose",
            OpenWeatherAdapter.currentText(digest));
    }

    @Test
    void openweather_current_text_omits_blank_place() {
        var digest = new LinkedHashMap<String, Object>();
        digest.put("conditions", "light rain");
        digest.put("temp_f", 58.0);
        digest.put("feels_like_f", 55.0);
        digest.put("humidity_pct", 88);
        digest.put("wind_mph", 12.0);
        digest.put("place", "");
        var text = OpenWeatherAdapter.currentText(digest);
        assertEquals("light rain, 58F (feels like 55F), humidity 88%, wind 12 mph", text);
    }

    @Test
    void openweather_forecast_text_digest_format() {
        var daily = List.<Map<String, Object>>of(
            Map.of("date", "2026-07-13", "low_f", 58L, "high_f", 71L, "conditions", "clear sky"),
            Map.of("date", "2026-07-14", "low_f", 60L, "high_f", 75L, "conditions", "few clouds"));
        assertEquals("San Jose — Mon 7/13: low 58F high 71F, clear sky; "
                + "Tue 7/14: low 60F high 75F, few clouds",
            OpenWeatherAdapter.forecastText("San Jose", daily));
    }

    @Test
    void openweather_forecast_text_keeps_unparseable_date_verbatim() {
        var daily = List.<Map<String, Object>>of(
            Map.of("date", "someday", "low_f", 50L, "high_f", 60L, "conditions", "?"));
        assertEquals("someday: low 50F high 60F, ?",
            OpenWeatherAdapter.forecastText("", daily));
    }
}

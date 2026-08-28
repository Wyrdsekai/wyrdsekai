package org.wyrdsekai.core.external.u;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A declared return shape must be the real one.
 *
 * <h2>What went wrong</h2>
 * The generated contract named methods and stopped there. A weather tool built on
 * 2026-08-22 read {@code data.temp_c} beside {@code data.temp_f} and spoke
 * "67.06°F (undefined°C)" — a reasonable guess about a shape nothing had stated.
 * Adapters now declare their result keys and the contract prints them, which is only
 * worth anything if the declaration is held to what the code actually returns.
 */
class TheContractNamesWhatComesBackTest {

    private static AdapterResponse body(String json) {
        return AdapterResponse.ok(Map.of("status", 200, "body", json));
    }

    @Test
    @DisplayName("openweather.current returns exactly what it declares")
    void currentMatchesItsDeclaration() {
        var r = OpenWeatherAdapter.digestCurrent(body("""
            {"weather":[{"description":"overcast clouds"}],
             "main":{"temp":67.06,"feels_like":66.0,"humidity":72},
             "wind":{"speed":5.1},"name":"Boston"}"""));
        assertThat(r.success()).isTrue();
        assertThat(keys(r)).containsExactlyInAnyOrderElementsOf(OpenWeatherAdapter.CURRENT_KEYS);
        // The specific guess that broke a working tool.
        assertThat(keys(r)).doesNotContain("temp_c");
    }

    @Test
    @DisplayName("openweather.forecast returns exactly what it declares")
    void forecastMatchesItsDeclaration() {
        var r = OpenWeatherAdapter.digestForecast(body("""
            {"city":{"name":"Boston"},
             "list":[{"dt_txt":"2026-08-22 12:00:00","main":{"temp":70.0},
                      "weather":[{"description":"clear sky"}]}]}"""));
        assertThat(r.success()).isTrue();
        assertThat(keys(r)).containsExactlyInAnyOrderElementsOf(OpenWeatherAdapter.FORECAST_KEYS);
    }

    @Test
    @DisplayName("both geocoders return exactly what they declare")
    void geocodersMatchTheirDeclarations() {
        var osm = OSMNominatimAdapter.digestSearch(
            "[{\"lat\":\"42.36\",\"lon\":\"-71.05\",\"display_name\":\"Boston, MA\"}]", "Boston, MA");
        assertThat(keys(osm)).containsExactlyInAnyOrderElementsOf(OSMNominatimAdapter.GEOCODE_KEYS);

        var google = GoogleMapsAdapter.digestGeocode(
            "{\"status\":\"OK\",\"results\":[{\"formatted_address\":\"Boston, MA\","
                + "\"geometry\":{\"location\":{\"lat\":42.36,\"lng\":-71.05}}}]}", "Boston, MA");
        assertThat(keys(google)).containsExactlyInAnyOrderElementsOf(GoogleMapsAdapter.GEOCODE_KEYS);
    }

    @Test
    @DisplayName("nothing declares a shape for a method it does not advertise")
    void declarationsCoverOnlyAdvertisedMethods() {
        for (ExternalAdapter a : List.of(
                new OpenWeatherAdapter(), new OSMNominatimAdapter(), new GoogleMapsAdapter())) {
            assertThat(a.wiredCapabilities())
                .as("%s declares a return shape for something it does not advertise", a.namespace())
                .containsAll(a.resultKeys().keySet());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> keys(AdapterResponse r) {
        return List.copyOf(((Map<String, Object>) r.data()).keySet());
    }
}

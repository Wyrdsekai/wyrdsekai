package org.wyrdsekai.core.external.u;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Live-network smoke for the 2026-07-11 de-stubbing of the weather chain.
 *  Runs only when real keys are in the environment. */
@Tag("live-network")
class WeatherAdaptersLiveSmokeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_CRED_GOOGLEMAPS_API_KEY", matches = ".+")
    void geocode_san_francisco_returns_real_coords() {
        CredentialResolver.get().setSafeReader(slot -> Optional.ofNullable(
            System.getenv("WYRDSEKAI_CRED_" + slot.toUpperCase().replace('.', '_'))));
        var resp = new GoogleMapsAdapter().invoke(AdapterRequest.of(
            "maps", "geocode", Map.of("address", "San Francisco, CA")));
        assertThat(resp.success()).as("geocode error: %s", resp.error()).isTrue();
        @SuppressWarnings("unchecked")
        var coords = (Map<String, Object>) ((Map<?, ?>) resp.data()).get("coords");
        assertThat(((Number) coords.get("lat")).doubleValue()).isBetween(37.0, 38.5);
        assertThat(((Number) coords.get("lon")).doubleValue()).isBetween(-123.5, -121.5);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_CRED_OPENWEATHERMAP_API_KEY", matches = ".+")
    void forecast_and_current_return_real_weather() {
        CredentialResolver.get().setSafeReader(slot -> Optional.ofNullable(
            System.getenv("WYRDSEKAI_CRED_" + slot.toUpperCase().replace('.', '_'))));
        var ow = new OpenWeatherAdapter();
        var cur = ow.invoke(AdapterRequest.of("openweather", "current",
            Map.of("lat", 37.7749, "lon", -122.4194)));
        assertThat(cur.success()).as("current error: %s", cur.error()).isTrue();
        var curData = (Map<?, ?>) cur.data();
        assertThat(curData.containsKey("conditions")).isTrue();
        assertThat(curData.containsKey("temp_f")).isTrue();

        var fc = ow.invoke(AdapterRequest.of("openweather", "forecast",
            Map.of("lat", 37.7749, "lon", -122.4194)));
        assertThat(fc.success()).as("forecast error: %s", fc.error()).isTrue();
        assertThat((List<?>) ((Map<?, ?>) fc.data()).get("daily")).isNotEmpty();
    }
}

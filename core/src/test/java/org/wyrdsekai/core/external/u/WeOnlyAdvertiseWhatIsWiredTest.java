package org.wyrdsekai.core.external.u;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract handed to an item author must be true.
 *
 * <h2>What went wrong</h2>
 * The items-as-tools contract is generated from the adapter registry, and it read
 * {@code capabilities()} — the surface an adapter MEANS to cover. On 2026-08-22 that
 * meant a steward with a live Google key was told about {@code maps.reverse_geocode},
 * {@code maps.directions} and {@code maps.places}, all of which answer
 * {@code not_yet_wired}; about {@code openweather.alerts}, the same; and about
 * {@code datagov.query}, which answers {@code credential_missing} on every call because
 * its slot is blank. The weather tool built the day before worked only because the model
 * happened to pick the two methods that were real.
 *
 * <p>These assertions are the join: a method may be advertised only if invoking it
 * reaches something. Every wired method here is invoked with no arguments, so a real
 * adapter answers {@code missing_arg}/{@code bad_request} before any network call.
 */
class WeOnlyAdvertiseWhatIsWiredTest {

    private static final List<ExternalAdapter> ADAPTERS = List.of(
        new OSMNominatimAdapter(), new OpenWeatherAdapter(), new GoogleMapsAdapter());

    @Test
    @DisplayName("nothing is advertised that the adapter does not also declare")
    void wiredIsASubsetOfDeclared() {
        for (var a : ADAPTERS) {
            assertThat(a.capabilities())
                .as("%s advertises a method it does not declare", a.namespace())
                .containsAll(a.wiredCapabilities());
        }
    }

    @Test
    @DisplayName("no advertised method answers not_yet_wired")
    void everyAdvertisedMethodReachesSomething() {
        for (var a : ADAPTERS) {
            for (var method : a.wiredCapabilities()) {
                var res = a.invoke(new AdapterRequest(a.namespace(), method, Map.of(), null, "shape-test"));
                if (res.success()) continue;
                assertThat(res.error().code())
                    .as("world.%s.%s is advertised to item authors", a.namespace(), method)
                    .isNotEqualTo("not_yet_wired");
            }
        }
    }

    @Test
    @DisplayName("scaffolding advertises nothing")
    void unwiredMethodsAreNotAdvertised() {
        var maps = new GoogleMapsAdapter();
        assertThat(maps.wiredCapabilities()).doesNotContain("directions", "places", "reverse_geocode");
        assertThat(new OpenWeatherAdapter().wiredCapabilities()).doesNotContain("alerts");
    }

    @Test
    @DisplayName("every geocoder answers in one shape")
    void geocodersAgree() {
        var osm = OSMNominatimAdapter.digestSearch(
            "[{\"lat\":\"42.36\",\"lon\":\"-71.05\",\"display_name\":\"Boston, MA\"}]", "Boston MA");
        var google = GoogleMapsAdapter.digestGeocode(
            "{\"status\":\"OK\",\"results\":[{\"formatted_address\":\"Boston, MA\","
                + "\"geometry\":{\"location\":{\"lat\":42.36,\"lng\":-71.05}}}]}", "Boston MA");

        // The contract shows ONE example. An author writes to it and picks whichever
        // geocoder is available; both must answer the keys that example reads.
        for (var r : List.of(osm, google)) {
            assertThat(r.success()).isTrue();
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) r.data();
            assertThat(data.keySet()).containsAll(Set.of("lat", "lon", "display_name"));
            assertThat(((Number) data.get("lat")).doubleValue()).isEqualTo(42.36);
            assertThat(((Number) data.get("lon")).doubleValue()).isEqualTo(-71.05);
        }
    }

    @Test
    @DisplayName("a geocode that matches nothing says so, instead of handing back a string")
    void noMatchIsAnError() {
        var r = OSMNominatimAdapter.digestSearch("[]", "Nowheresville XX");
        assertThat(r.success()).isFalse();
        assertThat(r.error().code()).isEqualTo("no_match");
    }
}

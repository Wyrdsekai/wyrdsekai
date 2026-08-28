package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The household has keys. An item a person holds must be able to use them.
 *
 * <h2>The live failure</h2>
 * 2026-08-21: the steward asked for a weather tool, and the household has an OpenWeather
 * key with a wired {@code OpenWeatherAdapter} exposing {@code current} and
 * {@code forecast}. The item goose wrote used {@code web.search} + {@code web.fetch} and
 * honestly reported "No live weather data found" — because the real surface was invisible
 * to it in three separate ways at once:
 *
 * <ol>
 *   <li>{@code adapterNamespaces()} is implemented by exactly ONE class — the companion's
 *       provider. Every other provider inherits the interface default, an empty set, and
 *       the proxy resolver returns null for a namespace it cannot find. So
 *       {@code world.openweather} did not exist for a player-held item.</li>
 *   <li>Even visible, the capability {@code openweather.current} was not in the crafted
 *       ceiling, so calling it would have been denied.</li>
 *   <li>The contract handed to every backend never mentioned adapters at all, so no
 *       author could have known to ask.</li>
 * </ol>
 *
 * <p>Three independent reasons for the same silence — which is why the item was written
 * the way it was. Nothing was broken; the capability was simply unreachable.
 */
class AToolCanReachTheHouseholdsKeysTest {

    @AfterEach
    void tearDown() {
        HouseholdItemContent.resetForTests();
    }

    /** A household that has adapters wired, standing in for the real registry. */
    private static final class HouseholdWithKeys extends VisitorItemProvider {
        HouseholdWithKeys() { super("home", "home"); }

        @Override
        public Set<String> adapterNamespaces() {
            return Set.of("openweather", "nominatim");
        }

        @Override
        public Map<String, Object> invokeAdapter(String ns, String method,
                                                 Map<String, Object> args) {
            return Map.of("success", true, "data",
                Map.of("namespace", ns, "method", method, "args", args));
        }
    }

    @Test
    void a_player_held_item_can_see_the_households_adapters() {
        var provider = new VisitorItemProvider("home", "home")
            .withHouseholdContent(new HouseholdWithKeys());
        assertThat(provider.adapterNamespaces())
            .as("world.openweather must exist for an item a person is holding")
            .contains("openweather", "nominatim");
    }

    @Test
    void and_can_actually_call_them() {
        ItemWorldApiProvider provider = new VisitorItemProvider("home", "home")
            .withHouseholdContent(new HouseholdWithKeys());
        var out = provider.invokeAdapter("openweather", "current",
            Map.of("lat", 42.37, "lon", -71.11));
        assertThat(out.get("success")).isEqualTo(true);
    }

    /** Abroad, there are no household keys to offer — and that stays true. */
    @Test
    void a_genuine_foreign_zone_still_has_no_adapters() {
        var provider = new VisitorItemProvider("far", "far");
        assertThat(provider.adapterNamespaces()).isEmpty();
        assertThat(provider.invokeAdapter("openweather", "current", Map.of()))
            .containsEntry("success", false);
    }

    /**
     * Public data a crafted item may read. Each is strictly less exposing than
     * {@code web.fetch_raw}, which the same ceiling already allows and which reaches
     * arbitrary URLs.
     */
    @Test
    void public_data_adapters_are_within_the_crafted_ceiling() {
        var caps = ItemCapabilitySet.craftedDefault();
        assertThat(caps.has("openweather.current")).isTrue();
        assertThat(caps.has("openweather.forecast")).isTrue();
        // "nominatim", not "osmnominatim" — the adapter's own namespace. This test
        // asserted the name I had invented, so it agreed with the ceiling's typo and
        // both were wrong together. A test written from the same memory as the code
        // confirms the memory, not the code; ThePublicDataCeilingNamesRealAdaptersTest
        // checks against the live registry instead.
        assertThat(caps.has("nominatim.geocode")).isTrue();
        assertThat(caps.has("timezone.lookup")).isTrue();
    }

    /**
     * And personal data is NOT — deliberately. A crafted item is authored by a model from
     * a sentence somebody typed. It may read the weather without anyone thinking hard; it
     * may not read a person's heart rate unless someone decided to allow it.
     */
    @Test
    void personal_health_adapters_are_not() {
        var caps = ItemCapabilitySet.craftedDefault();
        assertThat(caps.has("oura.sleep")).isFalse();
        assertThat(caps.has("fitbit.heartrate")).isFalse();
        assertThat(caps.has("whoop.recovery")).isFalse();
        assertThat(caps.has("googlefit.steps")).isFalse();
        assertThat(caps.has("applehealth.read")).isFalse();
    }
}

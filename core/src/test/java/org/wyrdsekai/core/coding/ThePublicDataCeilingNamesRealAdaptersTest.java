package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.core.external.u.PhaseUAdaptersBootstrap;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.ArrayList;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every namespace the crafted ceiling names must be a namespace that exists.
 *
 * <h2>What a typo here actually does</h2>
 * It is not a permission that quietly fails open or shut — it is worse, because the
 * ceiling feeds the GENERATED surface. A namespace the ceiling does not recognise is
 * filtered out of the contract, so the authoring backend is never told the capability
 * exists at all. The failure is invisible on both sides.
 *
 * <p>Live 2026-08-21: the ceiling said {@code osmnominatim} and {@code googlemaps}; the
 * adapters call themselves {@code nominatim} and {@code maps}. Both geocoders vanished
 * from the contract. goose was shown {@code world.openweather.current}, which takes
 * {@code &#123;lat, lon&#125;}, and no way to turn "cambridge ma" into coordinates — so
 * it searched the household LIBRARY for weather and reported what it found. Given what it
 * could see, that was a reasonable thing to build.
 *
 * <p>The irony is the point: {@link ItemApiSurface} exists so nobody hand-maintains a
 * mirror of the runtime, and then the ceiling — the one list still written by hand — was
 * written from memory. This is the check that should have come with it.
 */
class ThePublicDataCeilingNamesRealAdaptersTest {

    @Test
    void every_namespace_in_the_ceiling_is_a_real_registered_adapter() {
        PhaseUAdaptersBootstrap.init();
        var registered = ExternalAdapterRegistry.get().namespaces();
        assertThat(registered)
            .as("the registry must be populated, or this guard proves nothing")
            .isNotEmpty();

        var unknown = new ArrayList<String>();
        for (var cap : ItemCapabilitySet.CRAFTED_ALLOW) {
            if (!cap.endsWith(".*")) continue;
            var ns = cap.substring(0, cap.length() - 2);
            // Only namespaces that look like external adapters — the ceiling also holds
            // in-process families (library.*, room.*, llm.*) that are not adapters.
            if (!registered.contains(ns) && isAdapterShaped(ns)) unknown.add(cap);
        }
        assertThat(unknown)
            .as("a ceiling entry naming no adapter silently REMOVES that capability from "
                + "the generated contract — the author never learns it exists")
            .isEmpty();
    }

    /**
     * The public-data namespaces this ceiling deliberately opens must each resolve, and
     * the methods the adapters declare must actually be permitted by the wildcard.
     */
    @Test
    void the_services_we_meant_to_open_are_actually_open() {
        PhaseUAdaptersBootstrap.init();
        var caps = ItemCapabilitySet.craftedDefault();
        var registry = ExternalAdapterRegistry.get();

        for (var ns : java.util.List.of("openweather", "nominatim", "maps", "timezone")) {
            var adapter = registry.lookup(ns).orElse(null);
            assertThat(adapter).as("adapter '%s' must exist", ns).isNotNull();
            for (var method : adapter.capabilities()) {
                assertThat(caps.has(ns + "." + method))
                    .as("a crafted item must be allowed to call %s.%s", ns, method)
                    .isTrue();
            }
        }
    }

    /** And a weather tool must be able to geocode, or the weather adapter is unusable. */
    @Test
    void weather_and_a_geocoder_are_open_together() {
        PhaseUAdaptersBootstrap.init();
        var caps = ItemCapabilitySet.craftedDefault();
        assertThat(caps.has("openweather.current"))
            .as("weather takes coordinates").isTrue();
        assertThat(caps.has("nominatim.geocode"))
            .as("...so without a geocoder the weather adapter cannot be used at all, "
                + "which is exactly why the last weather item searched the library")
            .isTrue();
    }

    /** Personal data stays shut. */
    @Test
    void health_namespaces_remain_closed() {
        PhaseUAdaptersBootstrap.init();
        var caps = ItemCapabilitySet.craftedDefault();
        for (var ns : java.util.List.of("oura", "fitbit", "whoop", "google_fit",
                "apple_health")) {
            assertThat(caps.has(ns + ".read")).as("%s must stay closed", ns).isFalse();
        }
    }

    /** Families served in-process rather than by an adapter. */
    private static boolean isAdapterShaped(String ns) {
        return !java.util.List.of("math", "regex", "library", "room", "llm", "agent",
                "entity", "memory", "notes", "pinboard", "journal", "tags", "schedule",
                "presence", "bond", "embed", "oracle", "web", "inventory", "grants",
                "federation", "relay").contains(ns.toLowerCase(Locale.ROOT));
    }
}

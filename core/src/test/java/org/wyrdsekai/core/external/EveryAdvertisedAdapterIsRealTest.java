package org.wyrdsekai.core.external;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.wyrdsekai.core.external.o.PhaseOAdaptersBootstrap;
import org.wyrdsekai.core.external.p.PhasePAdaptersBootstrap;
import org.wyrdsekai.core.external.q.PhaseQAdaptersBootstrap;
import org.wyrdsekai.core.external.r.PhaseRAdaptersBootstrap;
import org.wyrdsekai.core.external.s.PhaseSAdaptersBootstrap;
import org.wyrdsekai.core.external.u.PhaseUAdaptersBootstrap;
import org.wyrdsekai.core.external.v.PhaseVAdaptersBootstrap;
import org.wyrdsekai.core.external.w.PhaseWAdaptersBootstrap;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sweep over every registered adapter: what we advertise must be backed by something.
 *
 * <h2>What went wrong</h2>
 * The items-as-tools contract is generated from the adapter registry, and it read
 * {@code capabilities()} — an adapter's INTENT. Of 89 registered adapters, 36 reach no
 * service at all. Thirteen of those (all of Phase V) answer {@code ok({stub:true, ...})}
 * with empty data instead of an error, so an item built on one reports "nothing found"
 * forever and no branch in the item can tell. An author was being handed all of them as
 * fact. The weather tool built on 2026-08-21 worked only because the model happened to
 * pick the two methods that were real.
 */
class EveryAdvertisedAdapterIsRealTest {

    private static List<ExternalAdapter> adapters;

    @BeforeAll
    static void loadRegistry() {
        // Every phase, not one: the bug this guards was found in Phase U and turned out
        // to be worse in Phase V, so a sweep that loads a single phase is not a sweep.
        PhaseOAdaptersBootstrap.init();
        PhasePAdaptersBootstrap.init();
        PhaseQAdaptersBootstrap.init();
        PhaseRAdaptersBootstrap.init();
        PhaseSAdaptersBootstrap.register();
        // Phase T is the inbound webhook listener — it needs a datastore, not a sweep.
        PhaseUAdaptersBootstrap.init();
        PhaseVAdaptersBootstrap.init();
        PhaseWAdaptersBootstrap.init();
        var registry = ExternalAdapterRegistry.get();
        adapters = new ArrayList<>();
        if (registry == null) return;
        for (var ns : registry.namespaces()) registry.lookup(ns).ifPresent(adapters::add);
    }

    @Test
    @DisplayName("the registry is actually populated (a green sweep over nothing proves nothing)")
    void registryIsPopulated() {
        assertThat(adapters).as("no adapters registered — this whole sweep would pass vacuously")
            .hasSizeGreaterThan(50);
    }

    @Test
    @DisplayName("nothing advertises a method it does not declare")
    void wiredIsASubsetOfDeclared() {
        for (var a : adapters) {
            assertThat(a.capabilities())
                .as("%s advertises a method missing from capabilities()", a.namespace())
                .containsAll(a.wiredCapabilities());
        }
    }

    @Test
    @DisplayName("known scaffolding advertises nothing")
    void scaffoldingIsSilent() {
        // Every one of these reaches no service. Phase V answers a fake success; the rest
        // answer not_yet_wired. None may appear in a contract handed to an item author.
        var scaffolding = List.of(
            "uber", "lyft", "zillow", "redfin", "airbnb", "booking", "kayak", "amadeus",
            "google_flights", "indeed", "etsy", "amazon", "shopify", "transit_rt",
            "weatherapi", "visualcrossing", "mapbox", "timezone", "datagov", "usajobs",
            "irs", "congress", "fitbit", "garmin", "whoop", "oura", "google_fit",
            "apple_health");
        for (var ns : scaffolding) {
            var a = byNamespace(ns);
            if (a == null) continue;   // not registered on this build — nothing to advertise
            assertThat(a.wiredCapabilities())
                .as("world.%s.* is scaffolding and must not be offered to an author", ns)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("adapters that do reach a service still advertise it")
    void realAdaptersAreNotMuted() {
        // The other direction: marking scaffolding silent must not silence real work.
        for (var ns : List.of("wikipedia", "nominatim", "openweather", "maps")) {
            var a = byNamespace(ns);
            if (a == null) continue;
            assertThat(a.wiredCapabilities())
                .as("world.%s.* reaches a live service and must stay advertised", ns)
                .isNotEmpty();
        }
    }

    private ExternalAdapter byNamespace(String ns) {
        return adapters.stream().filter(a -> ns.equals(a.namespace())).findFirst().orElse(null);
    }
}

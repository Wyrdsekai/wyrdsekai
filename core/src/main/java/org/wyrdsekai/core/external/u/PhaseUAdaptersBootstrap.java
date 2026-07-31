package org.wyrdsekai.core.external.u;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.ExternalAdapter;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * -§4.41 (Phase U) — registers the
 * health/wearables, gov/civic, maps, and weather adapters with
 * {@link ExternalAdapterRegistry}.
 *
 * <p>Invoked from {@code CoreServices.init(zoneId)} after the per-phase
 * services come online. Idempotent — repeated calls are a no-op so
 * {@code TestServerBootstrap} and the production binary can both reach
 * the same registered surface without race conditions.</p>
 */
public final class PhaseUAdaptersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PhaseUAdaptersBootstrap.class);
    private static final AtomicBoolean BOOTED = new AtomicBoolean(false);

    private PhaseUAdaptersBootstrap() {}

    /** Register every Phase U adapter. Safe to call multiple times. */
    public static void init() {
        if (!BOOTED.compareAndSet(false, true)) return;
        var registry = ExternalAdapterRegistry.get();
        var adapters = phaseUAdapters();
        for (var a : adapters) {
            registry.register(a);
        }
        log.info("Phase U adapters registered: {} namespaces ({})",
            adapters.size(),
            adapters.stream().map(ExternalAdapter::namespace).toList());
    }

    /** Test-only: unregister everything Phase U registered + reset the latch. */
    public static void resetForTests() {
        var registry = ExternalAdapterRegistry.get();
        for (var a : phaseUAdapters()) {
            registry.unregister(a.namespace());
        }
        BOOTED.set(false);
    }

    /**
     * Single source of truth for the Phase U adapter list.
     * Visible for tests so they can iterate without re-registering.
     */
    public static List<ExternalAdapter> phaseUAdapters() {
        return List.of(
            // §4.38 health & wearables
            new OuraAdapter(),
            new FitbitAdapter(),
            new AppleHealthAdapter(),
            new WhoopAdapter(),
            new GarminAdapter(),
            new GoogleFitAdapter(),
            // §4.39 gov & civic
            new USAJobsAdapter(),
            new DataGovAdapter(),
            new CongressAdapter(),
            new IRSAdapter(),
            // §4.40 maps & location
            new GoogleMapsAdapter(),
            new OSMNominatimAdapter(),
            new MapboxAdapter(),
            new TimezoneAdapter(),
            // §4.41 weather
            new OpenWeatherAdapter(),
            new WeatherAPIAdapter(),
            new VisualCrossingAdapter()
        );
    }
}

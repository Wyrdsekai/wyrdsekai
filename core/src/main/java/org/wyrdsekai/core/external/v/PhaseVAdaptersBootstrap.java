package org.wyrdsekai.core.external.v;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

/**
 * -§4.43 / Phase V — bulk-register the
 * travel + commerce adapters into the {@link ExternalAdapterRegistry}.
 *
 * <p>Wired from {@code CoreServices.init()} so test harnesses and the
 * production {@code Main} both pick them up. Idempotent — re-registering
 * the same namespace replaces the prior adapter.</p>
 *
 * <p>Adapters registered:</p>
 * <ul>
 *   <li><b>Travel</b> (§4.42): {@code amadeus}, {@code kayak},
 *       {@code google_flights}, {@code booking}, {@code airbnb},
 *       {@code uber}, {@code lyft}, {@code transit_rt} (public-transit
 *       routes/stops/schedules — see {@link TransitlandAdapter} for why the
 *       namespace is {@code transit_rt} and not {@code transit}).</li>
 *   <li><b>Commerce / real-estate / jobs</b> (§4.43): {@code shopify},
 *       {@code amazon}, {@code etsy}, {@code zillow}, {@code redfin},
 *       {@code indeed}.</li>
 * </ul>
 */
public final class PhaseVAdaptersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PhaseVAdaptersBootstrap.class);

    private static volatile boolean initialised = false;

    private PhaseVAdaptersBootstrap() {}

    /** Register all Phase V adapters. Safe to call multiple times. */
    public static synchronized void init() {
        var registry = ExternalAdapterRegistry.get();

        // Travel & transport (§4.42)
        registry.register(new AmadeusAdapter());
        registry.register(new KayakAdapter());
        registry.register(new GoogleFlightsAdapter());
        registry.register(new BookingComAdapter());
        registry.register(new AirbnbAdapter());
        registry.register(new UberAdapter());
        registry.register(new LyftAdapter());
        registry.register(new TransitlandAdapter());

        // Shopping, commerce, real estate (§4.43)
        registry.register(new ShopifyAdapter());
        registry.register(new AmazonAdapter());
        registry.register(new EtsyAdapter());
        registry.register(new ZillowAdapter());
        registry.register(new RedfinAdapter());
        registry.register(new IndeedAdapter());

        if (!initialised) {
            initialised = true;
            log.info("Phase V external adapters registered: 14 adapters "
                + "(travel: 8, commerce: 6)");
        }
    }

    /** Test-only escape — drops the bootstrap flag so init() re-runs. */
    public static synchronized void resetForTests() {
        initialised = false;
    }

    public static boolean isInitialised() { return initialised; }
}

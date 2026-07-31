package org.wyrdsekai.core.external.s;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

/**
 * single bootstrap entry point that
 * registers all Phase S (financial + telephony) adapters with the global
 * {@link ExternalAdapterRegistry}.
 *
 * <p>Invoked once from {@code CoreServices.init(...)}. Idempotent — repeated
 * calls re-register the same adapters (the registry's {@code put} replaces
 * by namespace and warns).</p>
 */
public final class PhaseSAdaptersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PhaseSAdaptersBootstrap.class);

    private static volatile boolean registered = false;

    private PhaseSAdaptersBootstrap() {}

    public static synchronized void register() {
        if (registered) return;
        var registry = ExternalAdapterRegistry.get();

        // §4.32 financial
        registry.register(new StripeAdapter());
        registry.register(new PlaidAdapter());
        registry.register(new WiseAdapter());
        registry.register(new CoinbaseAdapter());

        // §4.33 telephony
        registry.register(new TwilioAdapter());
        registry.register(new VonageAdapter());
        registry.register(new SignalwireAdapter());

        registered = true;
        log.info("Phase S adapters registered: stripe, plaid, wise, coinbase, twilio, vonage, signalwire");
    }

    /** Test-only — clears the registered flag so the next call re-registers. */
    public static synchronized void resetForTests() {
        registered = false;
    }
}

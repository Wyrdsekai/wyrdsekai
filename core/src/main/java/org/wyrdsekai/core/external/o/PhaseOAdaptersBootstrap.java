package org.wyrdsekai.core.external.o;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.ExternalAdapter;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

import java.util.function.Supplier;

/**
 * / Phase O — registers every Phase O
 * communication adapter against the process-wide
 * {@link ExternalAdapterRegistry}.
 *
 * <p>Called from {@code CoreServices.init()} so all entry points (production
 * Main, test bootstrap, phone node) get the same adapter set without each
 * having to repeat the registration block.</p>
 *
 * <p>Idempotent — calling twice replaces the existing adapters in place
 * (registry.register logs a warn on replacement). The bootstrap intentionally
 * does NOT throw on individual adapter init failures: a missing dep on one
 * surface should not take down the whole adapter wave.</p>
 */
public final class PhaseOAdaptersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PhaseOAdaptersBootstrap.class);

    private static volatile boolean initialised = false;

    private PhaseOAdaptersBootstrap() {}

    /** Register every Phase O adapter. Safe to call from CoreServices.init. */
    public static synchronized void init() {
        if (initialised) {
            log.debug("PhaseOAdaptersBootstrap already initialised — skipping");
            return;
        }
        var registry = ExternalAdapterRegistry.get();
        registerSafely(registry, "email", EmailAdapter::new);
        registerSafely(registry, "slack", SlackAdapter::new);
        registerSafely(registry, "discord", DiscordAdapter::new);
        registerSafely(registry, "telegram", TelegramAdapter::new);
        registerSafely(registry, "signal", SignalAdapter::new);
        registerSafely(registry, "matrix", MatrixAdapter::new);
        registerSafely(registry, "whatsapp", WhatsAppAdapter::new);
        initialised = true;
        log.info("Phase O adapters registered: email, slack, discord, telegram, "
            + "signal, matrix, whatsapp");
    }

    /** Test-only escape. */
    public static void resetForTests() {
        initialised = false;
    }

    private static void registerSafely(ExternalAdapterRegistry registry, String namespace,
                                        Supplier<? extends
                                            ExternalAdapter> factory) {
        try {
            registry.register(factory.get());
        } catch (Throwable t) {
            log.warn("Phase O adapter '{}' failed to register: {}", namespace, t.getMessage());
        }
    }
}

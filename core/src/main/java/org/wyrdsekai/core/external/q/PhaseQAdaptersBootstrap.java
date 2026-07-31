package org.wyrdsekai.core.external.q;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

/**
 * Phase Q registration entry point.
 *
 * <p>Invoked from {@code CoreServices.init()}. Registers the productivity +
 * knowledge adapters with {@link ExternalAdapterRegistry} so item scripts
 * can resolve {@code world.<namespace>.<method>} to the right adapter.</p>
 *
 * <p>Idempotent — calling {@code init()} a second time replaces existing
 * registrations with the same namespace (the registry logs a WARN on
 * replace).</p>
 */
public final class PhaseQAdaptersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PhaseQAdaptersBootstrap.class);

    private static volatile boolean initialised = false;

    private PhaseQAdaptersBootstrap() {}

    public static synchronized void init() {
        if (initialised) {
            log.debug("PhaseQAdaptersBootstrap.init() — already initialised");
            return;
        }
        var reg = ExternalAdapterRegistry.get();

        // §4.27 productivity
        reg.register(new GoogleCalendarAdapter());
        reg.register(new GoogleDriveAdapter());
        reg.register(new NotionAdapter());
        reg.register(new LinearAdapter());
        reg.register(new AsanaAdapter());
        reg.register(new TodoistAdapter());

        // §4.28 knowledge & research
        reg.register(new ArxivAdapter());
        reg.register(new GoogleScholarAdapter());
        reg.register(new WikipediaAdapter());
        reg.register(new StackOverflowAdapter());
        reg.register(new WolframAdapter());

        initialised = true;
        log.info("Phase Q adapters registered: 6 productivity + 5 knowledge");
    }

    /** Test-only: allow tests to re-init after {@link ExternalAdapterRegistry#clearForTests()}. */
    public static synchronized void resetForTests() {
        initialised = false;
    }
}

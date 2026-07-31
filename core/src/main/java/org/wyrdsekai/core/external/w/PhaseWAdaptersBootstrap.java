package org.wyrdsekai.core.external.w;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

/**
 * registers the translation/language
 * photo/asset library, and book adapters with
 * {@link ExternalAdapterRegistry}.
 *
 * <p>Idempotent: each call replaces any prior registration with the
 * same namespace (the registry warn-logs on replacement). Invoked from
 * {@code CoreServices.init(zoneId)} once per JVM. Fully self-contained —
 * no external dependencies beyond the SPI in {@code core/external/}.</p>
 */
public final class PhaseWAdaptersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PhaseWAdaptersBootstrap.class);

    private static volatile boolean initialised = false;

    private PhaseWAdaptersBootstrap() {}

    /** Register every Phase W adapter. Safe to call twice. */
    public static synchronized void init() {
        if (initialised) {
            log.debug("PhaseWAdaptersBootstrap.init() called more than once — skipping");
            return;
        }
        var registry = ExternalAdapterRegistry.get();

        // §4.44 Translation / language / education
        registry.register(new DeepLAdapter());
        registry.register(new GoogleTranslateAdapter());
        registry.register(new LinguaAdapter());
        registry.register(new DuolingoAdapter());
        registry.register(new CoursaAdapter());
        registry.register(new KhanAcademyAdapter());

        // §4.45 Photo / asset libraries
        registry.register(new UnsplashAdapter());
        registry.register(new PixabayAdapter());
        registry.register(new PexelsAdapter());
        registry.register(new IconifyAdapter());
        registry.register(new GoogleFontsAdapter());

        // §4.46 Books / reading / manga
        registry.register(new GoodreadsAdapter());
        registry.register(new OpenLibraryAdapter());
        registry.register(new GoogleBooksAdapter());
        registry.register(new KoboBooksAdapter());
        registry.register(new AudibleAdapter());
        registry.register(new CalibreAdapter());
        registry.register(new MangaDexAdapter());

        initialised = true;
        log.info("PhaseWAdaptersBootstrap registered 18 adapters: "
            + "deepl, translate, lingua, duolingo, coursa, khan, "
            + "unsplash, pixabay, pexels, iconify, fonts, "
            + "goodreads, openlib, gbooks, kobo, audible, calibre, mangadex");
    }

    /** Test-only escape hatch. */
    public static synchronized void resetForTests() {
        initialised = false;
    }

    public static boolean isInitialised() { return initialised; }
}

package org.wyrdsekai.core.external.p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

/**
 * Phase P — bootstrap registers the social + code-platform adapters with
 * the {@link ExternalAdapterRegistry}.
 *
 * <p>Idempotent. Called from {@code CoreServices.init()}.</p>
 *
 * <p>Each adapter is constructed with its default configuration; tests
 * can either {@code unregister} a given namespace and re-register a
 * pre-configured adapter, or grab the registered instance via
 * {@link ExternalAdapterRegistry#lookup(String)} and call its
 * {@code setBaseUrlOverride(...)} hook.</p>
 */
public final class PhasePAdaptersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PhasePAdaptersBootstrap.class);
    private static volatile boolean initialised = false;

    private PhasePAdaptersBootstrap() {}

    public static synchronized void init() {
        if (initialised) {
            log.debug("PhasePAdaptersBootstrap.init called twice — skipping");
            return;
        }
        var registry = ExternalAdapterRegistry.get();
        // Social
        registry.register(new MastodonAdapter());
        registry.register(new RedditAdapter());
        registry.register(new BlueskyAdapter());
        registry.register(new XAdapter());
        registry.register(new HackerNewsAdapter());
        // Code platforms
        registry.register(new GitHubAdapter());
        registry.register(new GitLabAdapter());
        registry.register(new NpmAdapter());
        registry.register(new PyPIAdapter());

        initialised = true;
        log.info("Phase P adapters registered: mastodon, reddit, bluesky, x, hn, "
            + "github, gitlab, npm, pypi");
    }

    /** Test-only: clear the initialised flag so a clean run can re-register. */
    public static void resetForTests() {
        initialised = false;
    }

    public static boolean isInitialised() {
        return initialised;
    }
}

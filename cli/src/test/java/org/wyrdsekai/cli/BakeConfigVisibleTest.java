package org.wyrdsekai.cli;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.GooseRuntimeConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The release bake must be able to read its own configuration.
 *
 * <p>It could not. {@code RecipeBakeMain} lives in the {@code cli} module and calls
 * {@code ConfigFactory.load()}, but the entire {@code wyrdsekai.coding} block — goose's
 * {@code enabled} flag, its provider, the whole backend table — was written into <b>server's</b>
 * {@code application.conf}. {@code cli} depends on {@code core}, not on {@code server}, so that
 * file was never on its classpath. Every release build died with:
 *
 * <pre>
 *   [bake] ERROR: goose disabled in config (wyrdsekai.coding.backends.goose.enabled).
 *          Set in ~/.wyrdsekai/wyrdsekai.conf or $WYRDSEKAI_CONF.
 * </pre>
 *
 * <p>and the advice was unfollowable: the {@code ${?WYRDSEKAI_CODING_GOOSE_ENABLED}} override it
 * points you at was stranded in the same unreachable file, so exporting the env var changed
 * nothing. The default was {@code enabled = true} the whole time. The config was not wrong — it
 * was in a module its own consumer could not see.
 *
 * <p>Fix: the block moved to {@code core/src/main/resources/reference.conf}, next to
 * {@link GooseRuntimeConfig}, the code that reads it. Typesafe merges {@code reference.conf} from
 * every jar on the classpath, so server and cli now resolve it identically.
 *
 * <p><b>Config must ship with its consumer, not with one of its consumers.</b> This test is the
 * guard that was missing: it fails if the bake's config ever drifts back out of reach.
 */
class BakeConfigVisibleTest {

    @Test
    void theBakeCanSeeItsOwnGooseConfig() {
        // Exactly what RecipeBakeMain does — no server classes anywhere on this classpath.
        var base = ConfigFactory.load();

        assertTrue(base.hasPath("wyrdsekai.coding.backends.goose.enabled"),
            "the cli module cannot see wyrdsekai.coding — the config has drifted back into a "
                + "module the release bake does not depend on, and every release build will die "
                + "with 'goose disabled in config'");

        var goose = GooseRuntimeConfig.fromConfig(base);
        assertTrue(goose.enabled(),
            "goose must be enabled by default (application.conf documents 'enabled = true'); if "
                + "this reads false from the cli classpath, the bake is dead again");
    }

    /** The documented env override has to actually reach the bake — it previously could not. */
    @Test
    void theCodingBackendTableIsResolvable() {
        var base = ConfigFactory.load();
        assertEquals("goose", base.getString("wyrdsekai.coding.default-backend"),
            "default-backend must resolve from the cli classpath too");
    }
}

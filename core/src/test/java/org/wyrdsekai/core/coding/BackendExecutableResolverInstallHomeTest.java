package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The install prefix on Windows is wherever the .msi put the app tree; the
 * CLI exports it as WYRDSEKAI_HOME (system property fallback here, since a
 * test cannot set the environment). A bundled backend staged under
 * {@code <home>/data/coding-cli-bundle} must be a candidate, in every tarball
 * shape the resolver already knows.
 */
class BackendExecutableResolverInstallHomeTest {

    @AfterEach
    void clear() {
        System.clearProperty("wyrdsekai.home");
    }

    @Test
    void installHomeBundleIsSearched() {
        System.setProperty("wyrdsekai.home", "/fake/Wyrdsekai/app");
        var cands = BackendExecutableResolver.candidates("codezaiku");
        var slot = Path.of("/fake/Wyrdsekai/app/data/coding-cli-bundle/codezaiku");
        assertTrue(cands.contains(slot.resolve("codezaiku").resolve("bin").resolve("codezaiku")),
            "tarball-with-top-dir shape under the install home must be a candidate: " + cands);
        assertTrue(cands.contains(slot.resolve("bin").resolve("codezaiku")),
            "unpacked shape under the install home must be a candidate");
    }

    @Test
    void withoutInstallHomeNothingChanges() {
        var cands = BackendExecutableResolver.candidates("codezaiku");
        assertTrue(cands.stream().noneMatch(c -> c.toString().contains("/fake/")));
    }
}

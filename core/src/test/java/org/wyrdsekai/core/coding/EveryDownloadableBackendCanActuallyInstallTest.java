package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shipped manifest must be installable on every platform it claims.
 *
 * <p>{@code BundleManifest} already refuses an entry whose
 * {@code sha256_per_platform} is empty — but not one that is non-empty and
 * keyed WRONGLY. A codezaiku entry written with a single {@code "any"} key
 * loaded fine, listed fine in {@code wyrd coding list}, and then failed at the
 * only moment that mattered:</p>
 *
 * <pre>install error: Backend 'codezaiku' has no sha256 entry for platform
 * 'linux-x64'. Manifest may not support this host.</pre>
 *
 * <p>Parsing is not the contract; installing is. The installer keys strictly on
 * {@code "<platform>-<arch>"}, so that is what a downloadable entry has to
 * carry — one artifact serving every platform still means the same sha written
 * under each key, not an invented key meaning "all of them".</p>
 */
class EveryDownloadableBackendCanActuallyInstallTest {

    /** The keys BundleInstaller.currentPlatformArch() can produce. */
    private static final List<String> SUPPORTED = List.of(
        "linux-x64", "linux-arm64", "darwin-arm64", "darwin-x64", "windows-x64");

    private static BundleManifest shipped() throws Exception {
        for (var p : List.of(Path.of("data/coding-cli-bundle/manifest.json"),
                             Path.of("../data/coding-cli-bundle/manifest.json"))) {
            if (Files.isReadable(p)) return BundleManifest.load(p);
        }
        return null;
    }

    @Test
    void a_downloadable_backend_has_a_sha_for_every_supported_platform() throws Exception {
        var manifest = shipped();
        assumeTrue(manifest != null, "shipped manifest not readable from this working dir");

        var problems = new ArrayList<String>();
        manifest.backends().forEach((name, entry) -> {
            // Only entries the installer will actually download and verify.
            if (entry.bundled() || entry.isNpmDistribution() || entry.configOnly()) return;
            if (entry.downloadUrlTemplate() == null || entry.downloadUrlTemplate().isBlank()) return;
            for (var platform : SUPPORTED) {
                var sha = entry.sha256For(platform);
                if (sha == null || sha.isBlank()) {
                    problems.add(name + " has no sha256 for " + platform
                        + " (keys present: " + entry.sha256PerPlatform().keySet() + ")");
                }
            }
        });

        assertThat(problems)
            .as("every downloadable backend must be installable on every platform "
                + "the installer can report — a manifest that parses is not the same "
                + "as a manifest that installs")
            .isEmpty();
    }

    @Test
    void no_entry_invents_a_platform_key_the_installer_will_never_ask_for() throws Exception {
        var manifest = shipped();
        assumeTrue(manifest != null, "shipped manifest not readable from this working dir");

        var stray = new ArrayList<String>();
        manifest.backends().forEach((name, entry) -> {
            var shas = entry.sha256PerPlatform();
            if (shas == null) return;
            shas.keySet().stream()
                .filter(k -> !SUPPORTED.contains(k))
                .forEach(k -> stray.add(name + " -> '" + k + "'"));
        });

        assertThat(stray)
            .as("a key the installer never looks up is dead weight at best and a "
                + "false claim of support at worst")
            .isEmpty();
    }
}

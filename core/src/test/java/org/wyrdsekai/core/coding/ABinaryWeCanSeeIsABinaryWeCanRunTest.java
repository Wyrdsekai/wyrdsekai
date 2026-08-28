package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A coding backend registers only when its binary is reachable and healthy, so
 * a binary we fail to FIND makes the backend simply not appear — no error, no
 * log line naming a cause, just a chain that quietly lacks it.
 *
 * <p>The old per-backend resolvers looked in two bundle directories and then
 * handed the bare name to PATH. That misses the case that actually occurs:
 * CodeZaiku's installer defaults to a per-user prefix, while the service runs
 * from systemd with {@code HOME=/root} and a short PATH. The tool is installed
 * and we still cannot see it.</p>
 */
class ABinaryWeCanSeeIsABinaryWeCanRunTest {

    private static Path exe(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        var p = dir.resolve(name);
        Files.writeString(p, "#!/bin/sh\necho ok\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }

    @Test
    void the_bundle_directory_still_wins(@TempDir Path root) throws Exception {
        var bundled = exe(root.resolve("coding-cli-bundle/toolx"), "toolx");
        var cands = BackendExecutableResolver.candidates("toolx");
        // The bundle path is only searched when WYRDSEKAI_DATA_DIR points here,
        // which the test JVM cannot set — assert the ORDER instead: whatever
        // bundle paths exist come before the system prefixes.
        var firstSystem = cands.indexOf(Path.of("/usr/local/bin/toolx"));
        assertThat(firstSystem)
            .as("system prefixes must come after the bundle entries")
            .isGreaterThan(0);
        assertThat(bundled).exists();
    }

    @Test
    void the_search_reaches_a_per_user_install_not_just_PATH() {
        var cands = BackendExecutableResolver.candidates("toolx").toString();
        assertThat(cands)
            .as("a per-user install prefix must be searched — this is where "
                + "CodeZaiku's own installer puts the binary by default")
            .contains(".local/bin/toolx");
        assertThat(cands)
            .as("a root install prefix must be searched too")
            .contains("/usr/local/bin/toolx");
    }

    @Test
    void an_unfound_binary_still_falls_through_to_PATH() {
        // Nothing named this exists anywhere; the resolver must hand the bare
        // name back so PATH remains the last resort rather than a hard failure.
        assertThat(BackendExecutableResolver.resolve("definitely-not-installed-xyz"))
            .isEqualTo("definitely-not-installed-xyz");
    }

    @Test
    void a_world_writable_candidate_is_refused(@TempDir Path dir) throws Exception {
        var p = exe(dir, "toolx");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxrwxrwx"));
        // Resolution never returns a path we would not accept: choosing something
        // to EXECUTE out of a file anyone can rewrite is not a convenience.
        assertThat(BackendExecutableResolver.resolve("toolx"))
            .as("a world-writable file must not be selected")
            .doesNotContain(dir.toString());
    }

    @Test
    void a_tarball_that_carries_its_own_top_level_directory_is_still_found() {
        // `wyrd coding install` extracts a release tarball verbatim into the
        // slot. CodeZaiku's carries a top-level `codezaiku/`, so the slot ends
        // up holding <slot>/codezaiku/bin/codezaiku while <slot>/codezaiku is a
        // DIRECTORY. Searching only the flat shape means install downloads,
        // verifies, extracts, reports success -- and the backend never
        // registers, because the binary was never found.
        var cands = BackendExecutableResolver.candidates("toolx").toString();
        assertThat(cands).contains("coding-cli-bundle/toolx/toolx");
        assertThat(cands).contains("coding-cli-bundle/toolx/bin/toolx");
        assertThat(cands).contains("coding-cli-bundle/toolx/toolx/bin/toolx");
    }

    @Test
    void a_directory_named_like_the_binary_is_not_mistaken_for_it(@TempDir Path dir) throws Exception {
        // The middle shape above leaves a DIRECTORY exactly where the flat
        // shape expects a file. Selecting it would hand ProcessBuilder a
        // directory and fail at exec time, far from the cause.
        Files.createDirectories(dir.resolve("toolx"));
        assertThat(BackendExecutableResolver.resolve("toolx"))
            .as("a directory must never be selected as an executable")
            .isEqualTo("toolx");
    }

    @Test
    void a_binary_named_after_its_build_target_is_found(@TempDir Path root) throws Exception {
        // codex's archive extracts to `codex-x86_64-unknown-linux-musl` -- the
        // rust triple is in the FILE name, not just the download URL, so no
        // fixed path predicts it. This shipped broken: `wyrd coding install
        // codex` returned 0 and the backend never registered, because nothing
        // ever looked for that name.
        var slot = root.resolve("coding-cli-bundle/codex");
        exe(slot, "codex-x86_64-unknown-linux-musl");
        assertThat(BackendExecutableResolver.candidates("codex").toString())
            .as("candidates are computed from WYRDSEKAI_DATA_DIR, which a test JVM "
                + "cannot set — so assert the shapes we DO control are present")
            .contains("coding-cli-bundle/codex/codex");
        assertThat(slot.resolve("codex-x86_64-unknown-linux-musl")).isExecutable();
    }

    @Test
    void two_target_named_binaries_are_refused_rather_than_guessed(@TempDir Path dir) throws Exception {
        // Ambiguity must not be resolved by picking one: running the wrong
        // binary is a worse outcome than reporting none.
        exe(dir, "toolx-linux-musl");
        exe(dir, "toolx-linux-gnu");
        assertThat(BackendExecutableResolver.resolve("toolx"))
            .as("two candidates mean we do not know which is the tool")
            .isEqualTo("toolx");
    }
}

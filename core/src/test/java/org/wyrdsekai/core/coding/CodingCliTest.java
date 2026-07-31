package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2a — argv-parsing and output-formatting tests for
 * {@link CodingCli}.
 *
 * <p>Tests inject manifest + install-root paths via the
 * {@code wyrdsekai.coding.bundle.manifest} and
 * {@code wyrdsekai.coding.bundle.root} system properties so no real
 * network is touched and {@code ~/.wyrdsekai} is never poked at.</p>
 */
class CodingCliTest {

    private static final String PROP_MANIFEST = "wyrdsekai.coding.bundle.manifest";
    private static final String PROP_ROOT     = "wyrdsekai.coding.bundle.root";

    @TempDir Path tmp;

    private ByteArrayOutputStream outBuf;
    private ByteArrayOutputStream errBuf;
    private CodingCli cli;
    private String prevManifestProp;
    private String prevRootProp;

    @BeforeEach
    void setUp() throws IOException {
        outBuf = new ByteArrayOutputStream();
        errBuf = new ByteArrayOutputStream();
        cli = new CodingCli(new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                            new PrintStream(errBuf, true, StandardCharsets.UTF_8));

        Path manifestPath = tmp.resolve("manifest.json");
        Files.writeString(manifestPath, """
            { "manifest_version": 1, "backends": {
                "shipped": {
                    "bundled": true,
                    "version": "1.0.0",
                    "path": "data/coding-cli-bundle/shipped/"
                },
                "ghost": {
                    "bundled": false,
                    "version": "1.0.0",
                    "download_url_template": "https://fake.example/{version}.tgz",
                    "sha256_per_platform": {
                        "linux-x64":    "%1$s",
                        "linux-arm64":  "%1$s",
                        "darwin-arm64": "%1$s",
                        "darwin-x64":   "%1$s",
                        "windows-x64":  "%1$s"
                    },
                    "size_mb": 10
                },
                "phantom": { "bundled": false, "config_only": true,
                             "tos_warning": "Cloud SaaS — code leaves your home." }
            }}""".formatted(sha256("hi")));

        Path rootDir = tmp.resolve("install-root");
        Files.createDirectories(rootDir);

        prevManifestProp = System.getProperty(PROP_MANIFEST);
        prevRootProp     = System.getProperty(PROP_ROOT);
        System.setProperty(PROP_MANIFEST, manifestPath.toString());
        System.setProperty(PROP_ROOT,     rootDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (prevManifestProp == null) System.clearProperty(PROP_MANIFEST);
        else System.setProperty(PROP_MANIFEST, prevManifestProp);
        if (prevRootProp == null) System.clearProperty(PROP_ROOT);
        else System.setProperty(PROP_ROOT, prevRootProp);
    }

    @Test void no_args_prints_usage_and_exits_zero() {
        int rc = cli.run(new String[0]);
        assertThat(rc).isZero();
        assertThat(stdout()).contains("usage: wyrd coding").contains("install").contains("update");
    }

    @Test void unknown_subcommand_emits_stderr_and_usage() {
        int rc = cli.run(new String[]{"flarble"});
        assertThat(rc).isZero(); // printUsage is the fall-through
        assertThat(stderr()).contains("unknown subcommand 'flarble'");
        assertThat(stdout()).contains("usage: wyrd coding");
    }

    @Test void list_renders_bundled_and_downloadable_and_config_only() {
        int rc = cli.run(new String[]{"list"});
        assertThat(rc).isZero();
        String out = stdout();
        assertThat(out).contains("shipped");
        assertThat(out).contains("ghost");
        assertThat(out).contains("phantom");
        // Bundled marker.
        assertThat(out).containsPattern("\\* +shipped");
        // Config-only entry surfaces ToS warning.
        assertThat(out).contains("Cloud SaaS");
    }

    @Test void status_prints_tabular_columns() {
        int rc = cli.run(new String[]{"status"});
        assertThat(rc).isZero();
        String out = stdout();
        assertThat(out).contains("BACKEND");
        assertThat(out).contains("STATE");
        assertThat(out).contains("VERSION");
        // Each of the manifest backends shows up.
        assertThat(out).contains("shipped").contains("ghost").contains("phantom");
        // shipped → bundled, phantom → config, ghost → available.
        assertThat(out).contains("bundled");
        assertThat(out).contains("config");
        assertThat(out).contains("available");
    }

    @Test void install_without_arg_returns_2_and_prints_usage_to_stderr() {
        int rc = cli.run(new String[]{"install"});
        assertThat(rc).isEqualTo(2);
        assertThat(stderr()).contains("usage: wyrd coding install");
    }

    @Test void install_unknown_backend_surfaces_actionable_error() {
        int rc = cli.run(new String[]{"install", "nonesuch"});
        assertThat(rc).isEqualTo(1);
        assertThat(stderr())
                .contains("install error")
                .contains("Unknown backend")
                .contains("wyrd coding list");
    }

    @Test void install_bundled_backend_refuses() {
        int rc = cli.run(new String[]{"install", "shipped"});
        assertThat(rc).isEqualTo(1);
        assertThat(stderr()).contains("bundled");
    }

    @Test void install_config_only_backend_refuses_with_key_chest_hint() {
        int rc = cli.run(new String[]{"install", "phantom"});
        assertThat(rc).isEqualTo(1);
        assertThat(stderr()).contains("config-only").contains("API key");
    }

    @Test void uninstall_without_arg_returns_2() {
        int rc = cli.run(new String[]{"uninstall"});
        assertThat(rc).isEqualTo(2);
        assertThat(stderr()).contains("usage: wyrd coding uninstall");
    }

    @Test void uninstall_unknown_backend_returns_zero_with_friendly_message() {
        int rc = cli.run(new String[]{"uninstall", "nonesuch"});
        assertThat(rc).isZero();
        assertThat(stdout()).contains("nonesuch was not installed");
    }

    @Test void uninstall_existing_backend_removes_directory() throws IOException {
        Path dir = Path.of(System.getProperty(PROP_ROOT)).resolve("ghost");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".version"), "1.0.0");
        int rc = cli.run(new String[]{"uninstall", "ghost"});
        assertThat(rc).isZero();
        assertThat(stdout()).contains("uninstalled ghost");
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test void update_without_arg_returns_2() {
        int rc = cli.run(new String[]{"update"});
        assertThat(rc).isEqualTo(2);
        assertThat(stderr()).contains("usage: wyrd coding update");
    }

    @Test void manifest_missing_surfaces_as_typed_error() {
        Path missing = tmp.resolve("missing.json");
        System.setProperty(PROP_MANIFEST, missing.toString());
        int rc = cli.run(new String[]{"list"});
        assertThat(rc).isEqualTo(1);
        assertThat(stderr()).contains("manifest error").contains("not found");
    }

    @Test void unknown_flag_to_install_returns_2() {
        int rc = cli.run(new String[]{"install", "ghost", "--bogus"});
        assertThat(rc).isEqualTo(2);
        assertThat(stderr()).contains("unknown flag");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String stdout() { return outBuf.toString(StandardCharsets.UTF_8); }
    private String stderr() { return errBuf.toString(StandardCharsets.UTF_8); }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { throw new AssertionError(e); }
    }
}

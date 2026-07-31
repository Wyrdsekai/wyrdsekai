package org.wyrdsekai.core.coding;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@code wyrd coding download-bundle}
 * the air-gap pre-fetch path.
 *
 * <p>Uses the JDK's built-in {@link HttpServer} as a stub origin so the
 * {@link BundleInstaller}'s real HTTP client exercises the real network
 * code path. The manifest's {@code download_url_template} is rewritten
 * to point at the in-process server, and per-(backend,platform) bytes
 * + sha256 hashes are pre-computed so the installer's verify step has
 * a real target to lock onto.</p>
 */
class CodingCliDownloadBundleTest {

    private static final String PROP_MANIFEST = "wyrdsekai.coding.bundle.manifest";
    private static final String PROP_ROOT     = "wyrdsekai.coding.bundle.root";

    @TempDir Path tmp;

    private ByteArrayOutputStream outBuf;
    private ByteArrayOutputStream errBuf;
    private CodingCli cli;
    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final Map<String, byte[]> served = new HashMap<>();
    private String prevManifestProp;
    private String prevRootProp;

    @BeforeEach void setUp() throws IOException {
        outBuf = new ByteArrayOutputStream();
        errBuf = new ByteArrayOutputStream();
        cli = new CodingCli(new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                            new PrintStream(errBuf, true, StandardCharsets.UTF_8));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            try {
                requestCount.incrementAndGet();
                String key = ex.getRequestURI().getPath();
                byte[] body = served.get(key);
                if (body == null) {
                    ex.sendResponseHeaders(404, 0);
                    return;
                }
                ex.getResponseHeaders().add("content-type", "application/octet-stream");
                ex.sendResponseHeaders(200, body.length);
                try (var os = ex.getResponseBody()) { os.write(body); }
            } finally { ex.close(); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        prevManifestProp = System.getProperty(PROP_MANIFEST);
        prevRootProp     = System.getProperty(PROP_ROOT);

        Path rootDir = tmp.resolve("install-root");
        Files.createDirectories(rootDir);
        System.setProperty(PROP_ROOT, rootDir.toString());
    }

    @AfterEach void tearDown() {
        if (server != null) server.stop(0);
        if (prevManifestProp == null) System.clearProperty(PROP_MANIFEST);
        else System.setProperty(PROP_MANIFEST, prevManifestProp);
        if (prevRootProp == null) System.clearProperty(PROP_ROOT);
        else System.setProperty(PROP_ROOT, prevRootProp);
    }

    /**
     * Build a manifest that pins a single downloadable backend "ghost" with
     * one (or more) platforms, plus a bundled / docker / config-only entry
     * to verify they're correctly skipped.
     *
     * <p>Each platform gets its own asset URL + body so we can verify the
     * URL-template substitution + per-platform sha256 + per-platform hit
     * counts.</p>
     */
    private void writeManifest(String[] platforms) throws IOException {
        Map<String, byte[]> bodies = new HashMap<>();
        Map<String, String> shas = new HashMap<>();
        for (String pa : platforms) {
            byte[] body = ("wyrdsekai-test-" + pa + "\n").getBytes(StandardCharsets.UTF_8);
            bodies.put(pa, body);
            shas.put(pa, sha256Hex(body));
            // Server path mirrors the URL template: /ghost/1.0.0/<platform>-<arch>.tgz
            served.put("/ghost/1.0.0/" + pa + ".tgz", body);
        }

        StringBuilder shaJson = new StringBuilder("{");
        boolean first = true;
        for (var e : shas.entrySet()) {
            if (!first) shaJson.append(",");
            first = false;
            shaJson.append("\"").append(e.getKey()).append("\":\"")
                   .append(e.getValue()).append("\"");
        }
        shaJson.append("}");

        String json = """
            { "manifest_version": 1, "backends": {
                "ghost": {
                    "bundled": false,
                    "version": "1.0.0",
                    "download_url_template": "%s/ghost/{version}/{platform}-{arch}.tgz",
                    "sha256_per_platform": %s,
                    "size_mb": 1
                },
                "shipped": {
                    "bundled": true,
                    "version": "1.0.0",
                    "path": "data/coding-cli-bundle/shipped/"
                },
                "kraken": {
                    "bundled": false,
                    "docker_image": "ghcr.io/example/kraken:1.0",
                    "setup_command": "wyrd setup kraken"
                },
                "phantom": {
                    "bundled": false,
                    "config_only": true
                }
            }}""".formatted(baseUrl, shaJson.toString());

        Path manifest = tmp.resolve("manifest.json");
        Files.writeString(manifest, json);
        System.setProperty(PROP_MANIFEST, manifest.toString());
    }

    @Test void download_bundle_fetches_all_platforms_and_verifies_sha() throws IOException {
        writeManifest(new String[]{"linux-x64", "darwin-arm64"});
        Path cacheDir = tmp.resolve("cache-out");

        int rc = cli.run(new String[]{
                "download-bundle", "--cache-dir", cacheDir.toString()});
        assertThat(rc).withFailMessage("stderr=%s", stderr()).isZero();

        // Two platforms requested → two HTTP hits.
        assertThat(requestCount.get()).isEqualTo(2);
        // Cache contains the canonical filenames.
        assertThat(cacheDir.resolve("ghost-1.0.0-linux-x64.tar.gz")).exists();
        assertThat(cacheDir.resolve("ghost-1.0.0-darwin-arm64.tar.gz")).exists();
        // No leftover .partial files.
        try (var s = Files.list(cacheDir)) {
            assertThat(s.filter(p -> p.getFileName().toString().endsWith(".partial")))
                    .isEmpty();
        }
        String out = stdout();
        assertThat(out).contains("[1/2]").contains("[2/2]");
        assertThat(out).contains("ghost").contains("OK");
        // Bundled + docker + config-only are reported as skipped, not fetched.
        assertThat(out).contains("skipped: shipped");
        assertThat(out).contains("skipped: kraken");
        assertThat(out).contains("skipped: phantom");
    }

    @Test void download_bundle_skips_already_cached_archives_on_rerun() throws IOException {
        writeManifest(new String[]{"linux-x64"});
        Path cacheDir = tmp.resolve("cache-out");

        int rc1 = cli.run(new String[]{
                "download-bundle", "--cache-dir", cacheDir.toString()});
        assertThat(rc1).isZero();
        assertThat(requestCount.get()).isEqualTo(1);

        // Second run with the cache populated must NOT touch the network.
        outBuf.reset(); errBuf.reset();
        cli = new CodingCli(new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                            new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        int rc2 = cli.run(new String[]{
                "download-bundle", "--cache-dir", cacheDir.toString()});
        assertThat(rc2).isZero();
        assertThat(requestCount.get()).isEqualTo(1); // still 1 — no new hit
        assertThat(stdout()).contains("cache-hit");
    }

    @Test void sha_mismatch_fails_with_exit_1_and_does_not_leave_corrupt_cache()
            throws IOException {
        writeManifest(new String[]{"linux-x64"});
        // Now corrupt the served body so its sha256 no longer matches
        // the manifest entry — installer must refuse + delete the partial.
        served.put("/ghost/1.0.0/linux-x64.tgz", "GARBAGE\n".getBytes(StandardCharsets.UTF_8));

        Path cacheDir = tmp.resolve("cache-out");
        int rc = cli.run(new String[]{
                "download-bundle", "--cache-dir", cacheDir.toString()});
        assertThat(rc).isEqualTo(1);
        assertThat(stderr()).contains("sha256 mismatch");
        assertThat(stderr()).contains("refusing");
        // The corrupt download must not survive.
        assertThat(cacheDir.resolve("ghost-1.0.0-linux-x64.tar.gz")).doesNotExist();
        try (var s = Files.list(cacheDir)) {
            assertThat(s.filter(p -> p.getFileName().toString().endsWith(".partial")))
                    .isEmpty();
        }
    }

    @Test void platform_filter_scopes_downloads_to_subset() throws IOException {
        writeManifest(new String[]{"linux-x64", "linux-arm64", "darwin-arm64"});
        Path cacheDir = tmp.resolve("cache-out");

        int rc = cli.run(new String[]{
                "download-bundle",
                "--platforms", "linux-x64,darwin-arm64",
                "--cache-dir", cacheDir.toString()});
        assertThat(rc).isZero();
        // Only the two requested platforms are fetched.
        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(cacheDir.resolve("ghost-1.0.0-linux-x64.tar.gz")).exists();
        assertThat(cacheDir.resolve("ghost-1.0.0-darwin-arm64.tar.gz")).exists();
        assertThat(cacheDir.resolve("ghost-1.0.0-linux-arm64.tar.gz")).doesNotExist();
    }

    @Test void backend_filter_scopes_to_named_backend() throws IOException {
        writeManifest(new String[]{"linux-x64"});
        Path cacheDir = tmp.resolve("cache-out");

        int rc = cli.run(new String[]{
                "download-bundle",
                "--backends", "ghost",
                "--cache-dir", cacheDir.toString()});
        assertThat(rc).isZero();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test void unknown_backend_in_filter_fails_fast() throws IOException {
        writeManifest(new String[]{"linux-x64"});
        Path cacheDir = tmp.resolve("cache-out");

        int rc = cli.run(new String[]{
                "download-bundle",
                "--backends", "no-such-backend",
                "--cache-dir", cacheDir.toString()});
        assertThat(rc).isEqualTo(1);
        assertThat(stderr())
                .contains("no-such-backend")
                .contains("not in the manifest");
        assertThat(requestCount.get()).isZero();
    }

    @Test void usage_help_listed_on_help_flag() throws IOException {
        writeManifest(new String[]{"linux-x64"});
        int rc = cli.run(new String[]{"download-bundle", "--help"});
        assertThat(rc).isZero();
        assertThat(stdout()).contains("--platforms").contains("--backends").contains("--cache-dir");
    }

    @Test void unknown_flag_returns_2() throws IOException {
        writeManifest(new String[]{"linux-x64"});
        int rc = cli.run(new String[]{"download-bundle", "--bogus"});
        assertThat(rc).isEqualTo(2);
        assertThat(stderr()).contains("unknown flag");
    }

    @Test void missing_value_for_platforms_returns_2() throws IOException {
        writeManifest(new String[]{"linux-x64"});
        int rc = cli.run(new String[]{"download-bundle", "--platforms"});
        assertThat(rc).isEqualTo(2);
        assertThat(stderr()).contains("usage").contains("--platforms");
    }

    @Test void usage_listed_in_top_level_help() {
        int rc = cli.run(new String[]{"help"});
        assertThat(rc).isZero();
        assertThat(stdout()).contains("download-bundle");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String stdout() { return outBuf.toString(StandardCharsets.UTF_8); }
    private String stderr() { return errBuf.toString(StandardCharsets.UTF_8); }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { throw new AssertionError(e); }
    }
}

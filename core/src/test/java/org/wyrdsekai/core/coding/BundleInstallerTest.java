package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2a — install / uninstall / sha256-verify flow tests for
 * {@link BundleInstaller}.
 *
 * <p>HTTP is mocked via a fake {@link HttpClient} so the tests are
 * hermetic. Archive extraction is replaced with a no-op {@link
 * BundleInstaller.Archiver} (production extraction is exercised by
 * end-to-end smoke tests, not unit tests).</p>
 */
class BundleInstallerTest {

    @TempDir Path tmp;

    private static final String FAKE_BYTES = "wyrdsekai test archive\n";
    private static final String FAKE_SHA256 = sha256(FAKE_BYTES);

    private Path destRoot;
    private Path manifestPath;
    private BundleManifest manifest;

    @BeforeEach
    void setUp() throws IOException {
        destRoot = tmp.resolve("install-root");
        manifestPath = tmp.resolve("manifest.json");
        Files.writeString(manifestPath, """
            { "manifest_version": 1, "backends": {
                "ghost": {
                    "bundled": false,
                    "version": "1.0.0",
                    "download_url_template": "https://fake.example/{version}/{platform}-{arch}.tgz",
                    "sha256_per_platform": {
                        "linux-x64":    "%1$s",
                        "linux-arm64":  "%1$s",
                        "darwin-arm64": "%1$s",
                        "darwin-x64":   "%1$s",
                        "windows-x64":  "%1$s"
                    },
                    "size_mb": 10
                },
                "shipped": {
                    "bundled": true,
                    "version": "1.0.0",
                    "path": "data/coding-cli-bundle/shipped/"
                },
                "todo-backend": {
                    "bundled": false,
                    "version": "1.0.0",
                    "download_url_template": "https://fake.example/{version}/{platform}-{arch}.tgz",
                    "sha256_per_platform": {
                        "linux-x64":    "TODO_RUN_BUILD_HELPER",
                        "linux-arm64":  "TODO_RUN_BUILD_HELPER",
                        "darwin-arm64": "TODO_RUN_BUILD_HELPER",
                        "darwin-x64":   "TODO_RUN_BUILD_HELPER",
                        "windows-x64":  "TODO_RUN_BUILD_HELPER"
                    }
                },
                "kraken": {
                    "bundled": false,
                    "version": "1.0.0",
                    "docker_image": "ghcr.io/example/kraken:1.0",
                    "setup_command": "wyrd setup kraken"
                },
                "phantom": {
                    "bundled": false,
                    "config_only": true
                }
            }}""".formatted(FAKE_SHA256));
        manifest = BundleManifest.load(manifestPath);
    }

    @Test void install_downloads_verifies_and_extracts_atomically() throws IOException {
        var http = new FakeHttpClient((url, req) -> okResponse(FAKE_BYTES));
        var cache = new AirGapBundleCache(destRoot.resolve("cache"));
        var archiver = new RecordingArchiver();
        var installer = new BundleInstaller(manifest, cache, http, archiver);

        Path installed = installer.installBackend("ghost", destRoot, /*force=*/false);

        assertThat(installed).isEqualTo(destRoot.resolve("ghost"));
        assertThat(Files.isDirectory(installed)).isTrue();
        assertThat(Files.readString(installed.resolve(".version"))).isEqualTo("1.0.0");
        assertThat(archiver.calls.get()).isEqualTo(1);
        // Cache populated as a side-effect.
        assertThat(Files.list(cache.root()).findAny()).isPresent();
        // No leftover .partial / .tmp.
        assertThat(Files.exists(destRoot.resolve("ghost.partial"))).isFalse();
        assertThat(Files.exists(destRoot.resolve("ghost.tmp"))).isFalse();
        // .version metadata round-trips through getStatus.
        var status = installer.getStatus("ghost", destRoot);
        assertThat(status.installed()).isTrue();
        assertThat(status.version()).isEqualTo("1.0.0");
    }

    @Test void sha256_mismatch_aborts_install_with_actionable_error() throws IOException {
        var http = new FakeHttpClient((url, req) -> okResponse("WRONG BYTES"));
        var cache = new AirGapBundleCache(destRoot.resolve("cache"));
        var installer = new BundleInstaller(manifest, cache, http, new RecordingArchiver());

        assertThatThrownBy(() -> installer.installBackend("ghost", destRoot, false))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("sha256 mismatch")
                .hasMessageContaining("ghost")
                .hasMessageContaining("1.0.0");
        // No directory left behind on mismatch.
        assertThat(Files.exists(destRoot.resolve("ghost"))).isFalse();
    }

    @Test void install_refuses_overwrite_without_force() throws IOException {
        Files.createDirectories(destRoot.resolve("ghost"));
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());

        assertThatThrownBy(() -> installer.installBackend("ghost", destRoot, false))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("already installed")
                .hasMessageContaining("--force");
    }

    @Test void install_force_overwrites_existing_directory() throws IOException {
        Files.createDirectories(destRoot.resolve("ghost"));
        Files.writeString(destRoot.resolve("ghost").resolve("old-marker.txt"), "old");
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());

        Path installed = installer.installBackend("ghost", destRoot, /*force=*/true);
        assertThat(installed).exists();
        assertThat(installed.resolve("old-marker.txt")).doesNotExist();
        assertThat(installed.resolve(".version")).exists();
    }

    @Test void install_refuses_placeholder_sha256() throws IOException {
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());

        assertThatThrownBy(() -> installer.installBackend("todo-backend", destRoot, false))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("placeholder sha256")
                .hasMessageContaining("build-coding-cli-manifest.sh");
    }

    @Test void install_rejects_bundled_backend() {
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());

        assertThatThrownBy(() -> installer.installBackend("shipped", destRoot, false))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("bundled");
    }

    @Test void install_rejects_docker_backend_with_setup_command_hint() {
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());

        assertThatThrownBy(() -> installer.installBackend("kraken", destRoot, false))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("wyrd setup kraken");
    }

    @Test void install_rejects_config_only_backend() {
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());

        assertThatThrownBy(() -> installer.installBackend("phantom", destRoot, false))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("config-only")
                .hasMessageContaining("API key");
    }

    @Test void install_rejects_unknown_backend() {
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());

        assertThatThrownBy(() -> installer.installBackend("nonesuch", destRoot, false))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("Unknown backend")
                .hasMessageContaining("wyrd coding list");
    }

    @Test void install_short_circuits_through_air_gap_cache() throws IOException {
        // Pre-populate the cache; the network must not be touched.
        Path cacheDir = destRoot.resolve("cache");
        Files.createDirectories(cacheDir);
        String pa = BundleInstaller.currentPlatformArch();
        Path cached = cacheDir.resolve("ghost-1.0.0-" + pa + ".tar.gz");
        Files.writeString(cached, FAKE_BYTES);

        AtomicInteger httpCalls = new AtomicInteger();
        var http = new FakeHttpClient((u, r) -> {
            httpCalls.incrementAndGet();
            return okResponse("SHOULD NOT BE CALLED");
        });
        var installer = new BundleInstaller(manifest, new AirGapBundleCache(cacheDir),
                http, new RecordingArchiver());

        installer.installBackend("ghost", destRoot, false);
        assertThat(httpCalls.get()).isZero();
    }

    @Test void uninstall_removes_directory() throws IOException {
        Path dir = destRoot.resolve("ghost");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".version"), "1.0.0");
        Files.writeString(dir.resolve("nested.txt"), "x");

        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());
        boolean removed = installer.uninstallBackend("ghost", destRoot);
        assertThat(removed).isTrue();
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test void uninstall_returns_false_when_not_installed() throws IOException {
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());
        assertThat(installer.uninstallBackend("ghost", destRoot)).isFalse();
    }

    @Test void list_installed_walks_directory_skipping_hidden_and_cache() throws IOException {
        Files.createDirectories(destRoot.resolve("ghost"));
        Files.createDirectories(destRoot.resolve("kraken"));
        Files.createDirectories(destRoot.resolve("cache"));        // skipped
        Files.createDirectories(destRoot.resolve(".hidden"));      // skipped
        Files.writeString(destRoot.resolve("loose-file.txt"), "x"); // skipped (not a dir)

        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());
        assertThat(installer.listInstalled(destRoot)).containsExactly("ghost", "kraken");
    }

    @Test void update_is_no_op_when_versions_match() throws IOException {
        Path dir = destRoot.resolve("ghost");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".version"), "1.0.0");

        AtomicInteger httpCalls = new AtomicInteger();
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> { httpCalls.incrementAndGet(); return okResponse(FAKE_BYTES); }),
                new RecordingArchiver());

        Optional<Path> result = installer.updateBackend("ghost", destRoot);
        assertThat(result).isEmpty();
        assertThat(httpCalls.get()).isZero();
    }

    @Test void update_reinstalls_when_versions_differ() throws IOException {
        Path dir = destRoot.resolve("ghost");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".version"), "0.9.0");

        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());
        Optional<Path> result = installer.updateBackend("ghost", destRoot);
        assertThat(result).isPresent();
        assertThat(Files.readString(result.get().resolve(".version"))).isEqualTo("1.0.0");
    }

    @Test void getStatus_returns_not_installed_when_directory_missing() {
        var installer = new BundleInstaller(manifest,
                new AirGapBundleCache(destRoot.resolve("cache")),
                new FakeHttpClient((u, r) -> okResponse(FAKE_BYTES)),
                new RecordingArchiver());
        var status = installer.getStatus("ghost", destRoot);
        assertThat(status.installed()).isFalse();
        assertThat(status.version()).isNull();
    }

    @Test void currentPlatformArch_returns_known_shape() {
        String pa = BundleInstaller.currentPlatformArch();
        // Format: <platform>-<arch>; both halves are non-empty.
        assertThat(pa).matches("(linux|darwin|windows)-(x64|arm64|arm|.*)");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static HttpResponse<InputStream> okResponse(String body) {
        return new FakeHttpResponse(200,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Minimal {@link HttpClient} stub — only {@code send(InputStream)} is called by the installer. */
    static final class FakeHttpClient extends HttpClient {
        private final BiFunction<URI, HttpRequest, HttpResponse<InputStream>> handler;

        FakeHttpClient(BiFunction<URI, HttpRequest, HttpResponse<InputStream>> handler) {
            this.handler = handler;
        }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> bh) {
            return (HttpResponse<T>) handler.apply(req.uri(), req);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> bh) {
            return CompletableFuture.completedFuture(send(r, bh));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> bh, HttpResponse.PushPromiseHandler<T> p) {
            return sendAsync(r, bh);
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NORMAL; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    static final class FakeHttpResponse implements HttpResponse<InputStream> {
        private final int status;
        private final InputStream body;
        FakeHttpResponse(int status, InputStream body) { this.status = status; this.body = body; }
        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
        @Override public InputStream body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://fake.example/"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    /** Records that {@code extract} was called; otherwise no-op (creates an empty dir). */
    static final class RecordingArchiver implements BundleInstaller.Archiver {
        final AtomicInteger calls = new AtomicInteger();
        @Override public void extract(Path archive, Path targetDir) throws IOException {
            calls.incrementAndGet();
            Files.createDirectories(targetDir);
        }
    }
}

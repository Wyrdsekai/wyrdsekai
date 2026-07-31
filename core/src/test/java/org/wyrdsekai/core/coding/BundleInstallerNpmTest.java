package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 / 2026-05-04 reconciliation — install-from-npm path tests for
 * {@link BundleInstaller}. Pinned by the manifest schema v2 bump that
 * added {@code distribution: "npm"} + {@code npm_package} for Cline,
 * Continue, Claude SDK, Gemini CLI.
 *
 * <p>Tests are hermetic: a stub {@link BundleInstaller.NpmInstaller}
 * records calls and returns canned results so we never shell out to a
 * real npm.</p>
 */
class BundleInstallerNpmTest {

    @TempDir Path tmp;

    private Path destRoot;
    private BundleManifest manifestWithNpm;

    @BeforeEach
    void setUp() throws IOException {
        destRoot = tmp.resolve("install-root");
        Path manifestPath = tmp.resolve("manifest.json");
        Files.writeString(manifestPath, """
            { "manifest_version": 2, "backends": {
                "cline": {
                    "bundled": false,
                    "version": "2.18.0",
                    "distribution": "npm",
                    "npm_package": "cline"
                },
                "continue": {
                    "bundled": false,
                    "version": "1.5.45",
                    "distribution": "npm",
                    "npm_package": "@continuedev/cli"
                }
            }}""");
        manifestWithNpm = BundleManifest.load(manifestPath);
    }

    @Test void install_npm_distributed_invokes_npm_install_g_with_versioned_spec() throws IOException {
        var npm = new RecordingNpm();
        npm.installResult = new BundleInstaller.NpmInstaller.NpmInstallResult(0, "added 1 package", "");
        var installer = new BundleInstaller(manifestWithNpm,
            new AirGapBundleCache(destRoot.resolve("cache")),
            new ZeroHttpClient(), new BundleInstallerTest.RecordingArchiver(),
            npm);

        Path result = installer.installBackend("cline", destRoot, /*force=*/false);

        // Marker path: npm:global:<package>.
        assertThat(result.toString()).isEqualTo("npm:global:cline");
        assertThat(npm.installPackage).isEqualTo("cline");
        assertThat(npm.installVersion).isEqualTo("2.18.0");
        // Stub .npm-version marker is left in destinationRoot for tooling
        // that walks the install dir.
        assertThat(Files.readString(destRoot.resolve("cline").resolve(".npm-version")))
                .isEqualTo("2.18.0");
    }

    @Test void install_npm_scoped_package_passes_scope_through() throws IOException {
        var npm = new RecordingNpm();
        npm.installResult = new BundleInstaller.NpmInstaller.NpmInstallResult(0, "ok", "");
        var installer = new BundleInstaller(manifestWithNpm,
            new AirGapBundleCache(destRoot.resolve("cache")),
            new ZeroHttpClient(), new BundleInstallerTest.RecordingArchiver(),
            npm);

        installer.installBackend("continue", destRoot, false);
        assertThat(npm.installPackage).isEqualTo("@continuedev/cli");
        assertThat(npm.installVersion).isEqualTo("1.5.45");
    }

    @Test void install_refuses_when_npm_absent_with_actionable_error() {
        var npm = new RecordingNpm();
        npm.available = false;
        var installer = new BundleInstaller(manifestWithNpm,
            new AirGapBundleCache(destRoot.resolve("cache")),
            new ZeroHttpClient(), new BundleInstallerTest.RecordingArchiver(),
            npm);

        assertThatThrownBy(() -> installer.installBackend("cline", destRoot, false))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("npm install -g cline@2.18.0")
                .hasMessageContaining("Install Node.js 20+")
                .hasMessageContaining("nodejs.org");
    }

    @Test void install_surfaces_npm_install_failure_clearly() {
        var npm = new RecordingNpm();
        npm.installResult = new BundleInstaller.NpmInstaller.NpmInstallResult(1, "",
                "npm ERR! 404 Not Found - GET https://registry.npmjs.org/cline");
        var installer = new BundleInstaller(manifestWithNpm,
            new AirGapBundleCache(destRoot.resolve("cache")),
            new ZeroHttpClient(), new BundleInstallerTest.RecordingArchiver(),
            npm);

        assertThatThrownBy(() -> installer.installBackend("cline", destRoot, false))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("npm install")
                .hasMessageContaining("exit 1")
                .hasMessageContaining("npm ERR! 404");
    }

    @Test void getStatus_uses_npm_ls_for_npm_distributed_entries() throws IOException {
        var npm = new RecordingNpm();
        npm.listResult = new BundleInstaller.NpmInstaller.NpmListResult(true, "2.18.0");
        var installer = new BundleInstaller(manifestWithNpm,
            new AirGapBundleCache(destRoot.resolve("cache")),
            new ZeroHttpClient(), new BundleInstallerTest.RecordingArchiver(),
            npm);

        var status = installer.getStatus("cline", destRoot);
        assertThat(status.installed()).isTrue();
        assertThat(status.version()).isEqualTo("2.18.0");
        assertThat(status.path().toString()).isEqualTo("npm:global:cline");
        assertThat(npm.listPackage).isEqualTo("cline");
    }

    @Test void getStatus_returns_not_installed_when_npm_ls_reports_empty() {
        var npm = new RecordingNpm();
        npm.listResult = new BundleInstaller.NpmInstaller.NpmListResult(false, null);
        var installer = new BundleInstaller(manifestWithNpm,
            new AirGapBundleCache(destRoot.resolve("cache")),
            new ZeroHttpClient(), new BundleInstallerTest.RecordingArchiver(),
            npm);

        var status = installer.getStatus("cline", destRoot);
        assertThat(status.installed()).isFalse();
        assertThat(status.version()).isNull();
    }

    @Test void download_only_refuses_npm_distributed_entries() {
        var installer = new BundleInstaller(manifestWithNpm,
            new AirGapBundleCache(destRoot.resolve("cache")),
            new ZeroHttpClient(), new BundleInstallerTest.RecordingArchiver(),
            new RecordingNpm());

        assertThatThrownBy(() -> installer.downloadOnly("cline", "linux-x64"))
                .isInstanceOf(BundleInstaller.InstallException.class)
                .hasMessageContaining("npm-distributed")
                .hasMessageContaining("wyrd coding install cline");
    }

    @Test void manifest_v2_is_loadable() throws IOException {
        // Direct schema regression — v2 manifests must load cleanly.
        Path m = tmp.resolve("v2.json");
        Files.writeString(m, """
            { "manifest_version": 2, "backends": {
                "cline": {
                    "bundled": false,
                    "version": "2.18.0",
                    "distribution": "npm",
                    "npm_package": "cline"
                }
            }}""");
        BundleManifest manifest = BundleManifest.load(m);
        assertThat(manifest.manifestVersion()).isEqualTo(2);
        assertThat(manifest.get("cline")).isPresent();
        var entry = manifest.get("cline").orElseThrow();
        assertThat(entry.isNpmDistribution()).isTrue();
        assertThat(entry.npmPackage()).isEqualTo("cline");
        assertThat(entry.effectiveDistribution())
                .isEqualTo(BackendBundleEntry.DISTRIBUTION_NPM);
    }

    @Test void manifest_v1_still_loads_with_default_distribution() throws IOException {
        // Back-compat: an older v1 manifest with no `distribution` field
        // must still load and default to `github_release`.
        Path m = tmp.resolve("v1.json");
        Files.writeString(m, """
            { "manifest_version": 1, "backends": {
                "ghost": {
                    "bundled": false,
                    "version": "1.0.0",
                    "download_url_template": "https://example.com/{version}.tgz",
                    "sha256_per_platform": {"linux-x64": "abc"}
                }
            }}""");
        BundleManifest manifest = BundleManifest.load(m);
        assertThat(manifest.manifestVersion()).isEqualTo(1);
        var entry = manifest.get("ghost").orElseThrow();
        assertThat(entry.effectiveDistribution())
                .isEqualTo(BackendBundleEntry.DISTRIBUTION_GITHUB_RELEASE);
        assertThat(entry.isNpmDistribution()).isFalse();
    }

    @Test void manifest_v2_npm_entry_must_declare_npm_package() throws IOException {
        // Validation: an npm entry without npm_package is rejected.
        Path m = tmp.resolve("bad.json");
        Files.writeString(m, """
            { "manifest_version": 2, "backends": {
                "ghost": {
                    "bundled": false,
                    "version": "1.0.0",
                    "distribution": "npm"
                }
            }}""");
        assertThatThrownBy(() -> BundleManifest.load(m))
                .isInstanceOf(BundleManifest.ManifestValidationException.class)
                .hasMessageContaining("npm_package");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Records install/list calls and returns canned results. */
    static final class RecordingNpm implements BundleInstaller.NpmInstaller {
        boolean available = true;
        NpmInstallResult installResult = new NpmInstallResult(0, "", "");
        NpmListResult listResult = new NpmListResult(false, null);
        String installPackage;
        String installVersion;
        String listPackage;
        AtomicBoolean installed = new AtomicBoolean(false);

        @Override public boolean isAvailable() { return available; }

        @Override public NpmInstallResult installGlobal(String pkg, String version) {
            installPackage = pkg;
            installVersion = version;
            installed.set(true);
            return installResult;
        }

        @Override public NpmListResult list(String pkg) {
            listPackage = pkg;
            return listResult;
        }
    }

    /** Throws on every HTTP call — npm-distributed install must not touch HTTP. */
    static final class ZeroHttpClient extends HttpClient {
        @Override public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> bh) {
            throw new AssertionError("HTTP send must not be called for npm-distributed entries");
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> bh) {
            return CompletableFuture.failedFuture(
                new AssertionError("HTTP sendAsync must not be called for npm-distributed entries"));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> bh, HttpResponse.PushPromiseHandler<T> p) {
            return sendAsync(r, bh);
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NORMAL; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }
}

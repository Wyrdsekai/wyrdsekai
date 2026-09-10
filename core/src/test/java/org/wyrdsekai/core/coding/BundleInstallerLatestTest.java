package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code wyrd coding update <backend>}: the backend's own newest release, verified against that release's sums. */
class BundleInstallerLatestTest {

    @TempDir Path tmp;

    private static String sha256(String s) throws Exception {
        var d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        var sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b)); return sb.toString();
    }

    private BundleManifest manifest() throws Exception {
        var p = tmp.resolve("manifest.json");
        Files.writeString(p, """
            { "manifest_version": 1, "backends": {
                "codezaiku": {
                    "bundled": true,
                    "version": "0.2.0",
                    "path": "data/coding-cli-bundle/codezaiku/",
                    "distribution": "github_release",
                    "download_url_template": "https://github.com/Wyrdsekai/codezaiku/releases/download/v0.2.0/codezaiku-0.2.0.tar.gz",
                    "sha256_per_platform": { "linux-x64": "%1$s", "linux-arm64": "%1$s", "darwin-arm64": "%1$s", "darwin-x64": "%1$s", "windows-x64": "%1$s" }
                },
                "ghost": {
                    "bundled": false, "version": "1.0.0",
                    "download_url_template": "https://fake.example/{version}/{platform}-{arch}.tgz",
                    "sha256_per_platform": { "linux-x64": "%1$s", "linux-arm64": "%1$s", "darwin-arm64": "%1$s", "darwin-x64": "%1$s", "windows-x64": "%1$s" }
                }
            }}""".formatted(sha256("old")));
        return BundleManifest.load(p);
    }

    private static BundleInstallerTest.FakeHttpResponse ok(String body) {
        return new BundleInstallerTest.FakeHttpResponse(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void the_latest_release_is_read_from_the_backends_repository_and_verified_against_its_own_sums() throws Exception {
        var tarball = "codezaiku 0.3.0 bytes";
        var sums = sha256(tarball) + "  codezaiku-0.3.0.tar.gz\n" + sha256("x") + "  codezaiku_0.3.0_all.deb\n";
        var http = new BundleInstallerTest.FakeHttpClient((uri, req) -> {
            var u = uri.toString();
            if (u.endsWith("/repos/Wyrdsekai/codezaiku/releases/latest")) return ok("{\"tag_name\": \"v0.3.0\", \"name\": \"CodeZaiku 0.3.0\"}");
            if (u.endsWith("/v0.3.0/SHA256SUMS")) return ok(sums);
            if (u.endsWith("/v0.3.0/codezaiku-0.3.0.tar.gz")) return ok(tarball);
            return new BundleInstallerTest.FakeHttpResponse(404, new ByteArrayInputStream(new byte[0]));
        });
        var dest = tmp.resolve("data").resolve("coding-cli-bundle");
        var installer = new BundleInstaller(manifest(), new AirGapBundleCache(tmp.resolve("cache")), http, new BundleInstallerTest.RecordingArchiver());

        var installed = installer.installLatest("codezaiku", dest);
        assertThat(installed).contains(dest.resolve("codezaiku"));
        assertThat(Files.readString(dest.resolve("codezaiku").resolve(".version"))).isEqualTo("0.3.0");
        assertThat(Files.exists(dest.resolve("codezaiku.SHA256SUMS"))).isFalse();
        assertThat(Files.exists(dest.resolve("codezaiku.partial"))).isFalse();
        // already at the latest: nothing to do
        assertThat(installer.installLatest("codezaiku", dest)).isEmpty();
    }

    @Test
    void a_release_whose_sums_do_not_match_or_do_not_exist_is_refused() throws Exception {
        var wrongSums = sha256("something else") + "  codezaiku-0.3.0.tar.gz\n";
        var http = new BundleInstallerTest.FakeHttpClient((uri, req) -> {
            var u = uri.toString();
            if (u.endsWith("/releases/latest")) return ok("{\"tag_name\": \"v0.3.0\"}");
            if (u.endsWith("/SHA256SUMS")) return ok(wrongSums);
            if (u.endsWith(".tar.gz")) return ok("codezaiku 0.3.0 bytes");
            return new BundleInstallerTest.FakeHttpResponse(404, new ByteArrayInputStream(new byte[0]));
        });
        var dest = tmp.resolve("d");
        var installer = new BundleInstaller(manifest(), new AirGapBundleCache(tmp.resolve("cache")), http, new BundleInstallerTest.RecordingArchiver());
        assertThatThrownBy(() -> installer.installLatest("codezaiku", dest))
            .isInstanceOf(BundleInstaller.InstallException.class).hasMessageContaining("sha256 mismatch");
        assertThat(Files.exists(dest.resolve("codezaiku"))).isFalse();

        var noSums = new BundleInstallerTest.FakeHttpClient((uri, req) -> uri.toString().endsWith("/releases/latest")
            ? ok("{\"tag_name\": \"v0.3.0\"}") : new BundleInstallerTest.FakeHttpResponse(404, new ByteArrayInputStream(new byte[0])));
        var installer2 = new BundleInstaller(manifest(), new AirGapBundleCache(tmp.resolve("cache2")), noSums, new BundleInstallerTest.RecordingArchiver());
        assertThatThrownBy(() -> installer2.installLatest("codezaiku", dest))
            .isInstanceOf(BundleInstaller.InstallException.class).hasMessageContaining("SHA256SUMS");

        // not a GitHub release: the caller falls back to the manifest
        assertThatThrownBy(() -> installer2.installLatest("ghost", dest))
            .isInstanceOf(BundleInstaller.InstallException.class).hasMessageContaining("not distributed as a GitHub release");
    }
}

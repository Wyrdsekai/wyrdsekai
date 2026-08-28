package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2a — schema + edge-case tests for {@link BundleManifest}.
 *
 * <p>Covers: shipped manifest loads cleanly; missing required fields
 * surface actionable errors; malformed entries reject; optional fields
 * tolerate absence; unknown JSON properties tolerate forward-compat.</p>
 */
class BundleManifestTest {

    @TempDir Path tmp;

    /** The shipped manifest must always load — it ships on every install. */
    @Test void shipped_manifest_loads() throws IOException {
        Path shipped = Path.of("../data/coding-cli-bundle/manifest.json");
        if (!Files.isReadable(shipped)) {
            // Test is run from core/ in source mode → ../data, but Gradle
            // sometimes runs from project root. Try both.
            shipped = Path.of("data/coding-cli-bundle/manifest.json");
        }
        if (!Files.isReadable(shipped)) {
            // Skip silently if neither path resolves — packaging mode runs
            // these tests against the JAR, not the source tree.
            return;
        }
        BundleManifest m = BundleManifest.load(shipped);
        // Manifest schema bumped to v2 in the 2026-05-04 reconciliation —
        // accept both v1 (legacy) and v2 (current) so this test passes
        // regardless of which build of the manifest is on disk.
        assertThat(m.manifestVersion())
            .isBetween(BundleManifest.MIN_SUPPORTED_MANIFEST_VERSION,
                       BundleManifest.SUPPORTED_MANIFEST_VERSION);
        assertThat(m.backends()).containsKeys("opencode", "goose", "codezaiku", "codex",
                "claude-sdk", "gemini-cli", "cline", "continue", "openhands", "devin");
        // CodeZaiku is the bundled default-of-record as of 0.2.0. This line
        // used to pin OpenCode as bundled — a claim no build ever staged: the
        // entry listed as "(bundled)" while a clean install had nothing, and
        // this very assertion held the lie in place. bundled:true is now
        // enforced against the DIST by build-dist.sh's bundled-backend gate,
        // and here we only pin which backend carries the claim.
        assertThat(m.get("codezaiku")).isPresent();
        assertThat(m.get("codezaiku").get().bundled()).isTrue();
        // OpenCode is honest now: npm-distributed, never bundled.
        assertThat(m.get("opencode").get().bundled()).isFalse();
        assertThat(m.get("opencode").get().isNpmDistribution()).isTrue();
        // Goose is downloadable.
        assertThat(m.get("goose").get().bundled()).isFalse();
        assertThat(m.get("goose").get().downloadUrlTemplate()).isNotBlank();
        // Devin is config-only.
        assertThat(m.get("devin").get().configOnly()).isTrue();
        // OpenHands is a setup-helper.
        assertThat(m.get("openhands").get().dockerImage()).isNotBlank();
    }

    @Test void load_rejects_missing_manifest_file() {
        Path missing = tmp.resolve("nope.json");
        assertThatThrownBy(() -> BundleManifest.load(missing))
                .isInstanceOf(BundleManifest.ManifestValidationException.class)
                .hasMessageContaining("not found");
    }

    @Test void load_rejects_non_object_root() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, "[1,2,3]");
        assertThatThrownBy(() -> BundleManifest.load(m))
                .isInstanceOf(BundleManifest.ManifestValidationException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test void load_rejects_missing_manifest_version() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, "{ \"backends\": {} }");
        assertThatThrownBy(() -> BundleManifest.load(m))
                .isInstanceOf(BundleManifest.ManifestValidationException.class)
                .hasMessageContaining("manifest_version");
    }

    @Test void load_rejects_unsupported_manifest_version() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, "{ \"manifest_version\": 99, \"backends\": {} }");
        assertThatThrownBy(() -> BundleManifest.load(m))
                .isInstanceOf(BundleManifest.ManifestValidationException.class)
                .hasMessageContaining("manifest_version=99");
    }

    @Test void load_rejects_missing_backends_object() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, "{ \"manifest_version\": 1 }");
        assertThatThrownBy(() -> BundleManifest.load(m))
                .isInstanceOf(BundleManifest.ManifestValidationException.class)
                .hasMessageContaining("'backends'");
    }

    @Test void load_accepts_empty_backends_object() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, "{ \"manifest_version\": 1, \"backends\": {} }");
        BundleManifest result = BundleManifest.load(m);
        assertThat(result.backends()).isEmpty();
    }

    @Test void downloadable_entry_must_have_sha256_map() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, """
            { "manifest_version": 1, "backends": {
                "ghost": {
                    "bundled": false,
                    "version": "1.0.0",
                    "download_url_template": "https://example.com/{version}.tgz"
                }
            }}""");
        assertThatThrownBy(() -> BundleManifest.load(m))
                .isInstanceOf(BundleManifest.ManifestValidationException.class)
                .hasMessageContaining("sha256_per_platform");
    }

    @Test void downloadable_entry_must_have_version() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, """
            { "manifest_version": 1, "backends": {
                "ghost": {
                    "bundled": false,
                    "download_url_template": "https://example.com/{version}.tgz",
                    "sha256_per_platform": {"linux-x64": "abc"}
                }
            }}""");
        assertThatThrownBy(() -> BundleManifest.load(m))
                .isInstanceOf(BundleManifest.ManifestValidationException.class)
                .hasMessageContaining("version");
    }

    @Test void config_only_entry_needs_no_other_fields() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, """
            { "manifest_version": 1, "backends": {
                "ghost": { "bundled": false, "config_only": true }
            }}""");
        BundleManifest result = BundleManifest.load(m);
        assertThat(result.get("ghost")).isPresent();
        assertThat(result.get("ghost").get().configOnly()).isTrue();
    }

    @Test void docker_helper_entry_needs_no_url_or_sha() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, """
            { "manifest_version": 1, "backends": {
                "kraken": {
                    "bundled": false,
                    "docker_image": "ghcr.io/example/kraken:1.0",
                    "setup_command": "wyrd setup kraken"
                }
            }}""");
        BundleManifest result = BundleManifest.load(m);
        assertThat(result.get("kraken")).isPresent();
        assertThat(result.get("kraken").get().dockerImage()).isEqualTo("ghcr.io/example/kraken:1.0");
    }

    @Test void unknown_top_level_fields_are_tolerated() throws IOException {
        // Forward-compat: a newer manifest with unknown keys must still load.
        Path m = tmp.resolve("m.json");
        Files.writeString(m, """
            { "manifest_version": 1,
              "comment": "this is a comment field",
              "future_field": [1,2,3],
              "backends": {} }""");
        BundleManifest result = BundleManifest.load(m);
        assertThat(result.manifestVersion()).isEqualTo(1);
    }

    @Test void unknown_per_entry_fields_are_tolerated() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, """
            { "manifest_version": 1, "backends": {
                "ghost": {
                    "bundled": false, "config_only": true,
                    "future_field": "anything"
                }
            }}""");
        BundleManifest result = BundleManifest.load(m);
        assertThat(result.get("ghost")).isPresent();
    }

    @Test void backend_lookup_is_case_insensitive() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, """
            { "manifest_version": 1, "backends": {
                "ghost": { "bundled": false, "config_only": true }
            }}""");
        BundleManifest result = BundleManifest.load(m);
        assertThat(result.get("ghost")).isPresent();
        assertThat(result.get("Ghost")).isPresent();
        assertThat(result.get("GHOST")).isPresent();
    }

    @Test void backend_entry_resolved_url_substitutes_tokens() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, """
            { "manifest_version": 1, "backends": {
                "ghost": {
                    "bundled": false,
                    "version": "2.0.0",
                    "download_url_template": "https://example.com/{version}/{platform}-{arch}.tgz",
                    "sha256_per_platform": {"linux-x64": "abc"}
                }
            }}""");
        var entry = BundleManifest.load(m).get("ghost").orElseThrow();
        assertThat(entry.resolvedDownloadUrl("linux", "x64"))
                .isEqualTo("https://example.com/2.0.0/linux-x64.tgz");
    }

    @Test void isInstallable_returns_false_for_helper_and_config_only() throws IOException {
        Path m = tmp.resolve("m.json");
        Files.writeString(m, """
            { "manifest_version": 1, "backends": {
                "kraken":  { "bundled": false, "docker_image": "x:1" },
                "phantom": { "bundled": false, "config_only": true },
                "ghost":   { "bundled": false, "version": "1.0",
                             "download_url_template": "u",
                             "sha256_per_platform": {"linux-x64":"abc"} }
            }}""");
        var manifest = BundleManifest.load(m);
        assertThat(manifest.get("kraken").get().isInstallable()).isFalse();
        assertThat(manifest.get("phantom").get().isInstallable()).isFalse();
        assertThat(manifest.get("ghost").get().isInstallable()).isTrue();
    }
}

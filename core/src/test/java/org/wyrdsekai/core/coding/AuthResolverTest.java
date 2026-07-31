package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DefaultAuthResolver}. Exercises the three branches
 * of the dual-path matrix (OAuth → ApiKey → AuthMissing) without
 * touching real CLI subprocesses or real Key Chest storage.
 */
class AuthResolverTest {

    @TempDir Path tmp;

    private static final String FIXTURE_MANIFEST = """
        { "manifest_version": 1, "backends": {
            "codex": {
              "bundled": false,
              "version": "1.0.0",
              "download_url_template": "https://example.test/codex-{platform}-{arch}.tar.gz",
              "sha256_per_platform": {
                "linux-x64": "deadbeef",
                "linux-arm64": "deadbeef",
                "darwin-arm64": "deadbeef",
                "darwin-x64": "deadbeef",
                "windows-x64": "deadbeef"
              },
              "auth": {
                "oauth": {
                  "command": "codex login --device-auth",
                  "credential_path": "~/.codex/auth.json",
                  "headless_supported": true
                },
                "api_key": {
                  "env_var": "OPENAI_API_KEY",
                  "key_chest_slot": "OPENAI_API_KEY"
                }
              }
            },
            "goose": {
              "bundled": false,
              "version": "1.0.0",
              "download_url_template": "https://example.test/goose.tar.gz",
              "sha256_per_platform": {
                "linux-x64": "deadbeef",
                "linux-arm64": "deadbeef",
                "darwin-arm64": "deadbeef",
                "darwin-x64": "deadbeef",
                "windows-x64": "deadbeef"
              },
              "auth": {
                "oauth": null,
                "api_key": {
                  "env_var": "GOOSE_PROVIDER_KEY",
                  "key_chest_slot": "GOOSE_PROVIDER_KEY"
                }
              }
            },
            "opencode": {
              "bundled": true,
              "version": "1.14.0",
              "path": "data/coding-cli-bundle/opencode/"
            }
        }}
        """;

    private BundleManifest fixtureManifest() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return BundleManifest.parse(mapper.readTree(FIXTURE_MANIFEST), "fixture");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Build a resolver whose credential-path resolution maps the
     * "~/.<dir>/..." manifest paths into the test tmpdir, so the
     * OAuth probe can be exercised without writing to a real home.
     */
    private DefaultAuthResolver resolverWith(Map<String, String> keyChest) {
        return new DefaultAuthResolver(
                fixtureManifest(),
                slot -> keyChest.get(slot),
                raw -> {
                    if (raw == null) return null;
                    String s = raw.startsWith("~/") ? raw.substring(2) : raw;
                    return tmp.resolve(s);
                });
    }

    @Test void oauth_session_present_returns_OAuthSession() throws IOException {
        // Simulate the credential file existing and being non-empty.
        Path credPath = tmp.resolve(".codex/auth.json");
        Files.createDirectories(credPath.getParent());
        Files.writeString(credPath, "{\"access_token\":\"sk-fake\"}");

        AuthMode mode = resolverWith(Map.of()).resolveAuth("codex");

        assertThat(mode).isInstanceOf(AuthMode.OAuthSession.class);
    }

    @Test void empty_oauth_file_falls_through_to_key_chest() throws IOException {
        // OAuth file exists but is empty — the resolver must not treat
        // an empty token store as a live session.
        Path credPath = tmp.resolve(".codex/auth.json");
        Files.createDirectories(credPath.getParent());
        Files.writeString(credPath, "");

        Map<String, String> keys = new HashMap<>();
        keys.put("OPENAI_API_KEY", "sk-real");

        AuthMode mode = resolverWith(keys).resolveAuth("codex");

        assertThat(mode).isInstanceOf(AuthMode.ApiKey.class);
        assertThat(((AuthMode.ApiKey) mode).value()).isEqualTo("sk-real");
    }

    @Test void no_oauth_session_falls_back_to_api_key() {
        Map<String, String> keys = new HashMap<>();
        keys.put("OPENAI_API_KEY", "sk-fallback");

        AuthMode mode = resolverWith(keys).resolveAuth("codex");

        assertThat(mode).isInstanceOf(AuthMode.ApiKey.class);
        assertThat(((AuthMode.ApiKey) mode).value()).isEqualTo("sk-fallback");
    }

    @Test void api_key_only_backend_skips_oauth_and_uses_key_chest() {
        Map<String, String> keys = new HashMap<>();
        keys.put("GOOSE_PROVIDER_KEY", "goose-key");

        AuthMode mode = resolverWith(keys).resolveAuth("goose");

        assertThat(mode).isInstanceOf(AuthMode.ApiKey.class);
        assertThat(((AuthMode.ApiKey) mode).value()).isEqualTo("goose-key");
    }

    @Test void no_oauth_and_no_key_returns_AuthMissing_with_recovery() {
        AuthMode mode = resolverWith(Map.of()).resolveAuth("codex");

        assertThat(mode).isInstanceOf(AuthMode.AuthMissing.class);
        AuthMode.AuthMissing m = (AuthMode.AuthMissing) mode;
        assertThat(m.backend()).isEqualTo("codex");
        assertThat(m.recoveryCommand()).isEqualTo("wyrd coding login codex");
        assertThat(m.reason()).contains("No OAuth session")
                              .contains("OPENAI_API_KEY");
    }

    @Test void api_key_only_with_no_key_recovery_points_at_key_chest() {
        AuthMode mode = resolverWith(Map.of()).resolveAuth("goose");

        assertThat(mode).isInstanceOf(AuthMode.AuthMissing.class);
        AuthMode.AuthMissing m = (AuthMode.AuthMissing) mode;
        assertThat(m.recoveryCommand()).contains("Key Chest");
        assertThat(m.reason()).contains("GOOSE_PROVIDER_KEY");
    }

    @Test void blank_value_in_key_chest_is_treated_as_missing() {
        Map<String, String> keys = new HashMap<>();
        keys.put("OPENAI_API_KEY", "   ");

        AuthMode mode = resolverWith(keys).resolveAuth("codex");

        assertThat(mode).isInstanceOf(AuthMode.AuthMissing.class);
    }

    @Test void unknown_backend_returns_AuthMissing() {
        AuthMode mode = resolverWith(Map.of()).resolveAuth("nonesuch");

        assertThat(mode).isInstanceOf(AuthMode.AuthMissing.class);
        assertThat(((AuthMode.AuthMissing) mode).reason())
                .contains("not found in manifest");
    }

    @Test void null_backend_name_returns_AuthMissing() {
        AuthMode mode = resolverWith(Map.of()).resolveAuth(null);

        assertThat(mode).isInstanceOf(AuthMode.AuthMissing.class);
    }

    @Test void backend_with_no_auth_block_returns_AuthMissing() {
        // OpenCode has no auth block in the fixture — local-only backend.
        AuthMode mode = resolverWith(Map.of()).resolveAuth("opencode");

        assertThat(mode).isInstanceOf(AuthMode.AuthMissing.class);
        assertThat(((AuthMode.AuthMissing) mode).reason())
                .contains("no auth block");
    }

    @Test void backend_name_is_normalised_to_lowercase() throws IOException {
        Path credPath = tmp.resolve(".codex/auth.json");
        Files.createDirectories(credPath.getParent());
        Files.writeString(credPath, "{}");

        AuthMode mode = resolverWith(Map.of()).resolveAuth("CODEX");

        assertThat(mode).isInstanceOf(AuthMode.OAuthSession.class);
    }

    @Test void oauth_credential_directory_with_files_is_live() throws IOException {
        // Claude Code uses ~/.config/claude/ as a directory — the
        // probe must treat a directory with any contents as live.
        // Use a hand-built manifest so we can point at a directory.
        String dirManifest = """
            { "manifest_version": 1, "backends": {
                "claude-sdk": {
                  "bundled": false,
                  "version": "1.0.0",
                  "download_url_template": "https://example.test/x.tgz",
                  "sha256_per_platform": { "linux-x64": "deadbeef" },
                  "auth": {
                    "oauth": {
                      "command": "claude login",
                      "credential_path": "~/.config/claude/",
                      "headless_supported": false
                    },
                    "api_key": { "env_var": "ANTHROPIC_API_KEY", "key_chest_slot": "ANTHROPIC_API_KEY" }
                  }
                }
            }}
            """;
        ObjectMapper mapper = new ObjectMapper();
        BundleManifest mf = BundleManifest.parse(mapper.readTree(dirManifest), "fixture");

        Path credDir = tmp.resolve(".config/claude");
        Files.createDirectories(credDir);
        Files.writeString(credDir.resolve("config.json"), "{}");

        DefaultAuthResolver r = new DefaultAuthResolver(
                mf,
                slot -> null,
                raw -> {
                    String s = raw.startsWith("~/") ? raw.substring(2) : raw;
                    return tmp.resolve(s);
                });

        assertThat(r.resolveAuth("claude-sdk")).isInstanceOf(AuthMode.OAuthSession.class);
    }

    @Test void oauth_credential_empty_directory_is_not_live() throws IOException {
        Path credDir = tmp.resolve(".codex");
        Files.createDirectories(credDir);
        // No auth.json file — directory exists but credential file does not.

        AuthMode mode = resolverWith(Map.of()).resolveAuth("codex");

        // Falls through to AuthMissing (no key in chest either).
        assertThat(mode).isInstanceOf(AuthMode.AuthMissing.class);
    }
}

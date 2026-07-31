package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@code auth} JSON sub-tree on {@link BackendBundleEntry}
 * — added in the SPEC §9.2 dual-path foundation work. The fixtures
 * mirror the shape used in the shipped {@code data/coding-cli-bundle/manifest.json}
 * so a regression in the schema will fail here instead of at install
 * time.
 */
class BackendBundleEntryAuthTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void oauth_and_api_key_roundtrip_for_codex_shaped_entry() throws IOException {
        String json = """
            {
              "bundled": false,
              "version": "1.0.0",
              "download_url_template": "https://example.test/codex-{platform}-{arch}.tar.gz",
              "sha256_per_platform": { "linux-x64": "deadbeef" },
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
            }
            """;
        BackendBundleEntry e = mapper.readValue(json, BackendBundleEntry.class);

        assertThat(e.auth()).isNotNull();
        assertThat(e.auth().oauth()).isNotNull();
        assertThat(e.auth().oauth().command()).isEqualTo("codex login --device-auth");
        assertThat(e.auth().oauth().credentialPath()).isEqualTo("~/.codex/auth.json");
        assertThat(e.auth().oauth().headlessSupported()).isTrue();
        assertThat(e.auth().apiKey()).isNotNull();
        assertThat(e.auth().apiKey().envVar()).isEqualTo("OPENAI_API_KEY");
        assertThat(e.auth().apiKey().keyChestSlot()).isEqualTo("OPENAI_API_KEY");
    }

    @Test void api_key_only_entry_has_null_oauth() throws IOException {
        // Goose / Devin / OpenHands shape — OAuth deliberately null.
        String json = """
            {
              "bundled": false,
              "version": "1.0.0",
              "auth": {
                "oauth": null,
                "api_key": {
                  "env_var": "GOOSE_PROVIDER_KEY",
                  "key_chest_slot": "GOOSE_PROVIDER_KEY",
                  "note": "Provider-pluggable"
                }
              }
            }
            """;
        BackendBundleEntry e = mapper.readValue(json, BackendBundleEntry.class);

        assertThat(e.auth()).isNotNull();
        assertThat(e.auth().oauth()).isNull();
        assertThat(e.auth().apiKey()).isNotNull();
        assertThat(e.auth().apiKey().envVar()).isEqualTo("GOOSE_PROVIDER_KEY");
        assertThat(e.auth().apiKey().note()).isEqualTo("Provider-pluggable");
    }

    @Test void browser_only_oauth_entry_keeps_headless_supported_false() throws IOException {
        // Claude Code SDK — no headless flow.
        String json = """
            {
              "bundled": false,
              "version": "1.0.0",
              "auth": {
                "oauth": {
                  "command": "claude login",
                  "credential_path": "~/.config/claude/",
                  "headless_supported": false,
                  "note": "Existing pattern"
                },
                "api_key": {
                  "env_var": "ANTHROPIC_API_KEY",
                  "key_chest_slot": "ANTHROPIC_API_KEY"
                }
              }
            }
            """;
        BackendBundleEntry e = mapper.readValue(json, BackendBundleEntry.class);

        assertThat(e.auth().oauth().headlessSupported()).isFalse();
        assertThat(e.auth().oauth().note()).isEqualTo("Existing pattern");
    }

    @Test void entry_with_no_auth_block_loads_with_null_auth() throws IOException {
        // Bundled OpenCode shape — needs no auth.
        String json = """
            {
              "bundled": true,
              "version": "1.14.0",
              "path": "data/coding-cli-bundle/opencode/"
            }
            """;
        BackendBundleEntry e = mapper.readValue(json, BackendBundleEntry.class);

        assertThat(e.auth()).isNull();
    }

    @Test void unknown_fields_inside_auth_block_are_ignored() throws IOException {
        // Forward-compat: a future Wyrdsekai release may add new auth
        // fields; older clients must still load the manifest cleanly.
        String json = """
            {
              "bundled": false,
              "version": "1.0.0",
              "auth": {
                "oauth": {
                  "command": "x login",
                  "credential_path": "~/.x/auth.json",
                  "headless_supported": true,
                  "future_field": "ignored"
                },
                "api_key": {
                  "env_var": "X_KEY",
                  "key_chest_slot": "X_KEY",
                  "another_future": 42
                },
                "future_top": null
              }
            }
            """;
        BackendBundleEntry e = mapper.readValue(json, BackendBundleEntry.class);

        assertThat(e.auth().oauth().command()).isEqualTo("x login");
        assertThat(e.auth().apiKey().envVar()).isEqualTo("X_KEY");
    }

    @Test void shipped_manifest_carries_auth_blocks_for_paid_backends() throws IOException {
        // Smoke check the real shipped manifest: every paid-tier
        // entry in the canonical list must declare an auth block, so
        // login + AuthResolver have something to read.
        Path shipped = Path.of("../data/coding-cli-bundle/manifest.json");
        if (!Files.isReadable(shipped)) {
            shipped = Path.of("data/coding-cli-bundle/manifest.json");
        }
        if (!Files.isReadable(shipped)) {
            return; // Test mode where manifest isn't on disk — not a failure.
        }
        BundleManifest m = BundleManifest.load(shipped);
        for (String name : new String[]{"codex", "claude-sdk", "gemini-cli",
                                         "cline", "continue", "goose",
                                         "devin", "openhands"}) {
            BackendBundleEntry entry = m.get(name).orElseThrow(
                () -> new AssertionError("missing manifest entry: " + name));
            assertThat(entry.auth())
                    .as("backend '%s' must declare an auth block", name)
                    .isNotNull();
            assertThat(entry.auth().apiKey())
                    .as("backend '%s' must declare an api_key block", name)
                    .isNotNull();
        }

        // Backends that support OAuth in the spec matrix.
        for (String name : new String[]{"codex", "claude-sdk", "gemini-cli",
                                         "cline", "continue"}) {
            assertThat(m.get(name).orElseThrow().auth().oauth())
                    .as("backend '%s' must declare oauth", name)
                    .isNotNull();
        }
        // Backends that are API-key-only.
        for (String name : new String[]{"goose", "devin", "openhands"}) {
            assertThat(m.get(name).orElseThrow().auth().oauth())
                    .as("backend '%s' must have oauth=null", name)
                    .isNull();
        }
    }
}

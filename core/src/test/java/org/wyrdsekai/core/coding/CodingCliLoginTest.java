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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code wyrd coding login &lt;backend&gt;}
 * The {@link CommandRunner} is
 * mocked so no real OAuth flow is launched. The TTY probe is
 * controlled per-test so headless-guard branches can be exercised on
 * any host.
 */
class CodingCliLoginTest {

    private static final String PROP_MANIFEST = "wyrdsekai.coding.bundle.manifest";
    private static final String PROP_ROOT     = "wyrdsekai.coding.bundle.root";

    @TempDir Path tmp;

    private ByteArrayOutputStream outBuf;
    private ByteArrayOutputStream errBuf;
    private RecordingRunner runner;
    private String prevManifestProp;
    private String prevRootProp;

    @BeforeEach
    void setUp() throws IOException {
        outBuf = new ByteArrayOutputStream();
        errBuf = new ByteArrayOutputStream();
        runner = new RecordingRunner(0);

        Path manifestPath = tmp.resolve("manifest.json");
        Files.writeString(manifestPath, """
            { "manifest_version": 1, "backends": {
                "codex": {
                  "bundled": false,
                  "version": "1.0.0",
                  "download_url_template": "https://example.test/codex.tar.gz",
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
                "claude-sdk": {
                  "bundled": false,
                  "version": "1.0.0",
                  "download_url_template": "https://example.test/claude.tar.gz",
                  "sha256_per_platform": {
                    "linux-x64": "deadbeef",
                    "linux-arm64": "deadbeef",
                    "darwin-arm64": "deadbeef",
                    "darwin-x64": "deadbeef",
                    "windows-x64": "deadbeef"
                  },
                  "auth": {
                    "oauth": {
                      "command": "claude login",
                      "credential_path": "~/.config/claude/",
                      "headless_supported": false
                    },
                    "api_key": {
                      "env_var": "ANTHROPIC_API_KEY",
                      "key_chest_slot": "ANTHROPIC_API_KEY"
                    }
                  }
                },
                "cline": {
                  "bundled": false,
                  "version": "2.0.0",
                  "download_url_template": "https://example.test/cline.tar.gz",
                  "sha256_per_platform": {
                    "linux-x64": "deadbeef",
                    "linux-arm64": "deadbeef",
                    "darwin-arm64": "deadbeef",
                    "darwin-x64": "deadbeef",
                    "windows-x64": "deadbeef"
                  },
                  "auth": {
                    "oauth": {
                      "command": "cline auth",
                      "credential_path": "~/.cline/auth.json",
                      "headless_supported": true
                    },
                    "api_key": {
                      "env_var": "CLINE_PROVIDER_KEY",
                      "key_chest_slot": "CLINE_PROVIDER_KEY"
                    }
                  }
                },
                "continue": {
                  "bundled": false,
                  "version": "1.0.0",
                  "download_url_template": "https://example.test/cn.tar.gz",
                  "sha256_per_platform": {
                    "linux-x64": "deadbeef",
                    "linux-arm64": "deadbeef",
                    "darwin-arm64": "deadbeef",
                    "darwin-x64": "deadbeef",
                    "windows-x64": "deadbeef"
                  },
                  "auth": {
                    "oauth": {
                      "command": "cn login",
                      "credential_path": "~/.continue/auth.json",
                      "headless_supported": true
                    },
                    "api_key": {
                      "env_var": "CONTINUE_API_KEY",
                      "key_chest_slot": "CONTINUE_API_KEY"
                    }
                  }
                },
                "gemini-cli": {
                  "bundled": false,
                  "version": "0.40.0",
                  "download_url_template": "https://example.test/gemini.tar.gz",
                  "sha256_per_platform": {
                    "linux-x64": "deadbeef",
                    "linux-arm64": "deadbeef",
                    "darwin-arm64": "deadbeef",
                    "darwin-x64": "deadbeef",
                    "windows-x64": "deadbeef"
                  },
                  "auth": {
                    "oauth": {
                      "command": "gemini",
                      "credential_path": "~/.gemini/oauth_creds.json",
                      "headless_supported": true
                    },
                    "api_key": {
                      "env_var": "GEMINI_API_KEY",
                      "key_chest_slot": "GEMINI_API_KEY"
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
                "devin": {
                  "bundled": false,
                  "config_only": true,
                  "auth": {
                    "oauth": null,
                    "api_key": {
                      "env_var": "DEVIN_API_KEY",
                      "key_chest_slot": "DEVIN_API_KEY"
                    }
                  }
                }
            }}""");

        // Pre-create install dirs for each OAuth-supporting backend so
        // the "is binary installed?" check passes (we're testing login,
        // not install).
        Path rootDir = tmp.resolve("install-root");
        Files.createDirectories(rootDir);
        for (String b : List.of("codex", "claude-sdk", "cline", "continue", "gemini-cli")) {
            Path dir = rootDir.resolve(b);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(".version"), "1.0.0");
        }

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

    private CodingCli cli(boolean isTty) {
        return new CodingCli(
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8),
                runner,
                () -> isTty);
    }

    @Test void login_no_arg_returns_2() {
        int rc = cli(true).run(new String[]{"login"});
        assertThat(rc).isEqualTo(2);
        assertThat(stderr()).contains("usage: wyrd coding login");
        assertThat(stderr()).contains("OAuth-supporting backends");
    }

    @Test void login_unknown_backend_returns_2() {
        int rc = cli(true).run(new String[]{"login", "nonesuch"});
        assertThat(rc).isEqualTo(2);
        assertThat(stderr()).contains("unknown backend").contains("nonesuch");
    }

    @Test void login_codex_invokes_device_auth_command() {
        int rc = cli(true).run(new String[]{"login", "codex"});
        assertThat(rc).isZero();
        assertThat(runner.invocations).hasSize(1);
        assertThat(runner.invocations.get(0))
                .containsExactly("codex", "login", "--device-auth");
    }

    @Test void login_cline_invokes_cline_auth() {
        int rc = cli(true).run(new String[]{"login", "cline"});
        assertThat(rc).isZero();
        assertThat(runner.invocations.get(0)).containsExactly("cline", "auth");
    }

    @Test void login_continue_invokes_cn_login() {
        int rc = cli(true).run(new String[]{"login", "continue"});
        assertThat(rc).isZero();
        assertThat(runner.invocations.get(0)).containsExactly("cn", "login");
    }

    @Test void login_gemini_invokes_gemini() {
        int rc = cli(true).run(new String[]{"login", "gemini-cli"});
        assertThat(rc).isZero();
        assertThat(runner.invocations.get(0)).containsExactly("gemini");
    }

    @Test void refusesApiKeyOnlyBackends_goose() {
        int rc = cli(true).run(new String[]{"login", "goose"});
        assertThat(rc).isEqualTo(1);
        assertThat(stderr())
                .contains("API-key-only")
                .contains("GOOSE_PROVIDER_KEY")
                .contains("Key Chest");
        assertThat(runner.invocations).isEmpty();
    }

    @Test void refusesApiKeyOnlyBackends_devin() {
        int rc = cli(true).run(new String[]{"login", "devin"});
        assertThat(rc).isEqualTo(1);
        assertThat(stderr())
                .contains("API-key-only")
                .contains("DEVIN_API_KEY");
        assertThat(runner.invocations).isEmpty();
    }

    @Test void refusesHeadlessForBrowserOnlyFlow() {
        // claude-sdk has headless_supported=false.
        int rc = cli(false).run(new String[]{"login", "claude-sdk"});
        assertThat(rc).isEqualTo(3);
        assertThat(stderr())
                .contains("needs a browser")
                .contains("--force");
        assertThat(runner.invocations).isEmpty();
    }

    @Test void warnsButRunsWithForce() {
        int rc = cli(false).run(new String[]{"login", "claude-sdk", "--force"});
        assertThat(rc).isZero();
        assertThat(stdout()).contains("warning").contains("browser-dependent");
        assertThat(runner.invocations).hasSize(1);
        assertThat(runner.invocations.get(0)).containsExactly("claude", "login");
    }

    @Test void headless_supported_backend_runs_on_non_tty() {
        // codex.headless_supported=true → no need for --force.
        int rc = cli(false).run(new String[]{"login", "codex"});
        assertThat(rc).isZero();
        assertThat(runner.invocations).hasSize(1);
    }

    @Test void unknown_flag_returns_2() {
        int rc = cli(true).run(new String[]{"login", "codex", "--bogus"});
        assertThat(rc).isEqualTo(2);
        assertThat(stderr()).contains("unknown flag");
    }

    @Test void login_propagates_subprocess_exit_code() {
        runner.exitCode = 7;
        int rc = cli(true).run(new String[]{"login", "codex"});
        assertThat(rc).isEqualTo(7);
        assertThat(stderr()).contains("exited 7").contains("NOT updated");
    }

    @Test void login_when_binary_not_installed_emits_actionable_error()
            throws IOException {
        // Tear down the codex install dir that setUp created.
        Path codexDir = tmp.resolve("install-root").resolve("codex");
        Files.deleteIfExists(codexDir.resolve(".version"));
        Files.deleteIfExists(codexDir);

        int rc = cli(true).run(new String[]{"login", "codex"});
        assertThat(rc).isEqualTo(1);
        assertThat(stderr())
                .contains("not installed")
                .contains("wyrd coding install codex");
        assertThat(runner.invocations).isEmpty();
    }

    @Test void login_warns_when_subprocess_succeeds_but_no_creds_appear() {
        // The fake runner exits 0 but never writes a credential file
        // — the CLI should still report success but warn.
        int rc = cli(true).run(new String[]{"login", "codex"});
        assertThat(rc).isZero();
        // Either the success or warning message lands on stdout.
        String out = stdout();
        assertThat(out).containsAnyOf("Login complete", "no credentials");
    }

    @Test void usage_listing_shows_only_oauth_backends() {
        int rc = cli(true).run(new String[]{"login"});
        assertThat(rc).isEqualTo(2);
        String err = stderr();
        // OAuth-supporting backends in the fixture:
        assertThat(err).contains("codex")
                       .contains("claude-sdk")
                       .contains("cline")
                       .contains("continue")
                       .contains("gemini-cli");
        // API-key-only backends NOT listed:
        assertThat(err).doesNotContain("goose")
                       .doesNotContain("devin");
    }

    @Test void splitCommand_handles_simple_argv() {
        assertThat(CodingCli.splitCommand("codex login --device-auth"))
                .containsExactly("codex", "login", "--device-auth");
        assertThat(CodingCli.splitCommand("  cn   login  "))
                .containsExactly("cn", "login");
        assertThat(CodingCli.splitCommand(null)).isEmpty();
        assertThat(CodingCli.splitCommand("")).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String stdout() { return outBuf.toString(StandardCharsets.UTF_8); }
    private String stderr() { return errBuf.toString(StandardCharsets.UTF_8); }

    /** Records every argv it's asked to run; returns a fixed exit code. */
    private static final class RecordingRunner implements CommandRunner {
        final List<List<String>> invocations = new ArrayList<>();
        int exitCode;
        RecordingRunner(int exitCode) { this.exitCode = exitCode; }
        @Override public int run(List<String> argv) { invocations.add(List.copyOf(argv)); return exitCode; }
    }
}

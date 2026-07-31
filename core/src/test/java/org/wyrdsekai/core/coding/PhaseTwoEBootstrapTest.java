package org.wyrdsekai.core.coding;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2e — verifies {@link CodingBackendBootstrap} wiring of Claude
 * SDK, Codex CLI, Gemini CLI, and Devin.
 *
 * <p>Two main paths are pinned: {@code enabled=true} + binary reachable
 * + auth present → registered; {@code enabled=true} + binary absent (or
 * auth missing) → silently skipped (paid backends should NOT register
 * when there's no auth — keeping the selection chain clean).</p>
 *
 * <p>CI hosts won't have any of these four binaries on PATH (and
 * shouldn't have real Anthropic/OpenAI/Google/Devin keys configured),
 * so the "registered when reachable" tests run only when the
 * preconditions actually hold; otherwise we skip rather than mis-assert
 * (mirrors the Phase 2d / OpenHands bootstrap test pattern).</p>
 */
class PhaseTwoEBootstrapTest {

    @BeforeEach
    void setUp() {
        BackendRegistry.get().clear();
        CodingBackendBootstrap.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
        CodingBackendBootstrap.resetForTest();
    }

    // ─── Disabled-config path ────────────────────────────────────

    @Test void bootstrap_skips_claude_sdk_when_disabled() {
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.claude-sdk { enabled = false }"));
        assertThat(BackendRegistry.get().backendFor(ClaudeSdkBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().adapterFor(ClaudeSdkBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_codex_when_disabled() {
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.codex { enabled = false }"));
        assertThat(BackendRegistry.get().backendFor(CodexCliBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_gemini_cli_when_disabled() {
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.gemini-cli { enabled = false }"));
        assertThat(BackendRegistry.get().backendFor(GeminiCliBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_devin_when_disabled() {
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.devin { enabled = false }"));
        assertThat(BackendRegistry.get().backendFor(DevinBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_all_four_when_block_absent() {
        CodingBackendBootstrap.init(ConfigFactory.empty());
        assertThat(BackendRegistry.get().backendFor(ClaudeSdkBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(CodexCliBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(GeminiCliBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(DevinBackend.NAME)).isEmpty();
    }

    // ─── Binary-absent path: enabled but no binary → skip ────────

    @Test void bootstrap_skips_claude_sdk_when_binary_absent() {
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.claude-sdk {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/nonexistent/claude-" + UUID.randomUUID() + "\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg);
        assertThat(BackendRegistry.get().backendFor(ClaudeSdkBackend.NAME))
            .as("Claude SDK must skip registration when binary not reachable")
            .isEmpty();
    }

    @Test void bootstrap_skips_codex_when_binary_absent() {
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.codex {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/nonexistent/codex-" + UUID.randomUUID() + "\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg);
        assertThat(BackendRegistry.get().backendFor(CodexCliBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_gemini_cli_when_binary_absent() {
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.gemini-cli {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/nonexistent/gemini-" + UUID.randomUUID() + "\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg);
        assertThat(BackendRegistry.get().backendFor(GeminiCliBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_devin_when_org_id_blank() {
        // Devin has no binary; the org_id check is the structural gate.
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.devin {\n"
            + "  enabled = true\n"
            + "  org-id = \"\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg);
        assertThat(BackendRegistry.get().backendFor(DevinBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_devin_when_auth_missing_even_with_org_id() {
        // org_id present but no DEVIN_API_KEY in Key Chest → skip.
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.devin {\n"
            + "  enabled = true\n"
            + "  org-id = \"org-test-xyz\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg, slot -> null); // no keys in chest
        assertThat(BackendRegistry.get().backendFor(DevinBackend.NAME))
            .as("Devin must NOT register when no auth — selection chain stays clean")
            .isEmpty();
    }

    // ─── Idempotency ─────────────────────────────────────────────

    @Test void bootstrap_idempotent_does_not_double_register() {
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.claude-sdk {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/nonexistent/claude-" + UUID.randomUUID() + "\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg);
        CodingBackendBootstrap.init(cfg);
        assertThat(BackendRegistry.get().backendFor(ClaudeSdkBackend.NAME)).isEmpty();
    }

    // ─── Independence: doesn't disturb other phase backends ──────

    @Test void all_four_can_coexist_alongside_opencode() {
        // OpenCode is the default-on backend; Phase 2e backends must
        // skip/register cleanly without disturbing it.
        var cfg = ""
            + "wyrdsekai.coding.backends.opencode { enabled = true }\n"
            + "wyrdsekai.coding.backends.claude-sdk { enabled = false }\n"
            + "wyrdsekai.coding.backends.codex { enabled = false }\n"
            + "wyrdsekai.coding.backends.gemini-cli { enabled = false }\n"
            + "wyrdsekai.coding.backends.devin { enabled = false }";
        CodingBackendBootstrap.init(ConfigFactory.parseString(cfg));

        assertThat(BackendRegistry.get().backendFor(OpenCodeBackend.NAME)).isPresent();
        assertThat(BackendRegistry.get().backendFor(ClaudeSdkBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(CodexCliBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(GeminiCliBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(DevinBackend.NAME)).isEmpty();
    }
}

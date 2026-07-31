package org.wyrdsekai.core.coding;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2d — verifies {@link CodingBackendBootstrap} wiring of Goose,
 * Cline, and Continue.
 *
 * <p>Two main paths are pinned: {@code enabled=true} + binary reachable
 * → registered; {@code enabled=true} + binary absent → silently skipped
 * (so the selection chain falls through cleanly).</p>
 *
 * <p>Most CI hosts won't have any of these three binaries on PATH. The
 * tests that pin "registered when reachable" run only when
 * {@link CodingBackendBootstrap#binaryReachable} reports true; otherwise
 * we skip rather than mis-assert (mirrors the OpenHands bootstrap test
 * pattern).</p>
 */
class PhaseTwoDBootstrapTest {

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

    @Test void bootstrap_skips_goose_when_disabled() {
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.goose { enabled = false }"));
        assertThat(BackendRegistry.get().backendFor(GooseBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().adapterFor(GooseBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_cline_when_disabled() {
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.cline { enabled = false }"));
        assertThat(BackendRegistry.get().backendFor(ClineBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().adapterFor(ClineBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_continue_when_disabled() {
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.continue { enabled = false }"));
        assertThat(BackendRegistry.get().backendFor(ContinueBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().adapterFor(ContinueBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_all_three_when_block_absent() {
        // Default config (no blocks at all). All three default to
        // enabled=false so the bootstrap should not register anything.
        CodingBackendBootstrap.init(ConfigFactory.empty());
        assertThat(BackendRegistry.get().backendFor(GooseBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(ClineBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(ContinueBackend.NAME)).isEmpty();
    }

    // ─── Binary-absent path: enabled but no binary → skip ────────

    @Test void bootstrap_skips_goose_when_binary_absent() {
        // Use an unreachable absolute path so binaryReachable() returns
        // false even on a host that has goose on PATH.
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.goose {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/nonexistent/goose-" + UUID.randomUUID() + "\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg);
        assertThat(BackendRegistry.get().backendFor(GooseBackend.NAME))
            .as("Goose must skip registration when binary not reachable")
            .isEmpty();
    }

    @Test void bootstrap_skips_cline_when_binary_absent() {
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.cline {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/nonexistent/cline-" + UUID.randomUUID() + "\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg);
        assertThat(BackendRegistry.get().backendFor(ClineBackend.NAME)).isEmpty();
    }

    @Test void bootstrap_skips_continue_when_binary_absent() {
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.continue {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/nonexistent/cn-" + UUID.randomUUID() + "\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg);
        assertThat(BackendRegistry.get().backendFor(ContinueBackend.NAME)).isEmpty();
    }

    // ─── Binary-reachable path: enabled + binary present → register

    @Test void bootstrap_registers_goose_when_reachable() {
        if (!CodingBackendBootstrap.binaryReachable(GooseBackend.NAME, "goose")) return;
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.goose { enabled = true }"));
        assertThat(BackendRegistry.get().backendFor(GooseBackend.NAME)).isPresent();
        assertThat(BackendRegistry.get().adapterFor(GooseBackend.NAME)).isPresent();
    }

    @Test void bootstrap_registers_cline_when_reachable() {
        if (!CodingBackendBootstrap.binaryReachable(ClineBackend.NAME, "cline")) return;
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.cline { enabled = true }"));
        assertThat(BackendRegistry.get().backendFor(ClineBackend.NAME)).isPresent();
        assertThat(BackendRegistry.get().adapterFor(ClineBackend.NAME)).isPresent();
    }

    @Test void bootstrap_registers_continue_when_reachable() {
        if (!CodingBackendBootstrap.binaryReachable(ContinueBackend.NAME, "cn")) return;
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.continue { enabled = true }"));
        assertThat(BackendRegistry.get().backendFor(ContinueBackend.NAME)).isPresent();
        assertThat(BackendRegistry.get().adapterFor(ContinueBackend.NAME)).isPresent();
    }

    // ─── Idempotency ─────────────────────────────────────────────

    @Test void bootstrap_idempotent_does_not_double_register_goose() {
        // Use a known-fake binary so registration always skips, and verify
        // that a second init doesn't accidentally re-add an entry.
        var cfg = ConfigFactory.parseString(""
            + "wyrdsekai.coding.backends.goose {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/nonexistent/goose-" + UUID.randomUUID() + "\"\n"
            + "}");
        CodingBackendBootstrap.init(cfg);
        CodingBackendBootstrap.init(cfg);
        assertThat(BackendRegistry.get().backendFor(GooseBackend.NAME)).isEmpty();
    }

    // ─── Independence: one backend's failure must not block others

    @Test void all_three_can_coexist_alongside_opencode() {
        // OpenCode is the default-on backend; the Phase 2d trio must
        // register alongside it (or skip silently) without disturbing
        // the OpenCode registration.
        var cfg = ""
            + "wyrdsekai.coding.backends.opencode { enabled = true }\n"
            + "wyrdsekai.coding.backends.goose { enabled = false }\n"
            + "wyrdsekai.coding.backends.cline { enabled = false }\n"
            + "wyrdsekai.coding.backends.continue { enabled = false }";
        CodingBackendBootstrap.init(ConfigFactory.parseString(cfg));

        assertThat(BackendRegistry.get().backendFor(OpenCodeBackend.NAME)).isPresent();
        assertThat(BackendRegistry.get().backendFor(GooseBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(ClineBackend.NAME)).isEmpty();
        assertThat(BackendRegistry.get().backendFor(ContinueBackend.NAME)).isEmpty();
    }
}

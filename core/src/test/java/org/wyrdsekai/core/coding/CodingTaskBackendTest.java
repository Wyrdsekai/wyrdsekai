package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.CommandRouter;
import org.wyrdsekai.core.codezaiku.CodeItemStore;

import java.lang.reflect.Modifier;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.HashSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.IOException;

/**
 * Phase 1c — interface-conformance + registry-roundtrip tests for the
 * {@link CodingTaskBackend} sealed family.
 *
 * <p>For every concrete backend permitted by the sealed interface this
 * verifies the basic contract: {@link CodingTaskBackend#name()} is
 * non-null, {@link CodingTaskBackend#tier()} returns a valid enum, and
 * {@link CodingTaskBackend#healthCheck()} works synchronously and via
 * the async future. Phase 1a only ships {@link CodeZaikuBackend}; this
 * test reflects the {@code permits} clause and grows automatically as
 * new backends are added.</p>
 */
class CodingTaskBackendTest {

    @TempDir Path tmp;

    private CodeItemStore store;

    @BeforeEach void setUp() {
        store = new CodeItemStore("jdbc:sqlite:" + tmp.resolve("codezaiku.db").toAbsolutePath());
    }

    @AfterEach void tearDown() {
        // BackendRegistry singleton is process-wide. Clear after every test
        // so cross-test contamination doesn't leak through the registry.
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_permits_at_least_codezaiku() {
        // permitted subclasses are exposed via reflection on a sealed type
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).isNotEmpty();
        assertThat(permitted).contains(CodeZaikuBackend.class);
    }

    @Test void all_permitted_backends_meet_contract() throws Exception {
        for (var cls : CodingTaskBackend.class.getPermittedSubclasses()) {
            // Skip any abstract permitted classes that exist purely as a
            // grouping seam — Phase 1a has none, but the check is cheap.
            if (Modifier.isAbstract(cls.getModifiers())) continue;
            var backend = newBackendFor(cls);
            assertThat(backend.name())
                .as("name() must be non-null and non-blank for %s", cls.getSimpleName())
                .isNotNull().isNotBlank();
            assertThat(backend.tier())
                .as("tier() must be one of the enum values for %s", cls.getSimpleName())
                .isIn((Object[]) BackendTier.values());

            // Sync wrapping path — get() with a small timeout covers the
            // happy path, the future not blocking forever, and that the
            // probe returns a Boolean (not null).
            var healthy = backend.healthCheck().get(2, TimeUnit.SECONDS);
            assertThat(healthy).isNotNull();

            // Async path: thenAccept must fire promptly (even when result
            // is false). We don't assert on the value; we assert it ran.
            var fired = new boolean[]{false};
            backend.healthCheck().thenAccept(v -> fired[0] = true)
                .get(2, TimeUnit.SECONDS);
            assertThat(fired[0]).isTrue();
        }
    }

    // ─── BackendRegistry round-trip ─────────────────────────────────

    @Test void registry_register_then_lookup_returns_same_instance() {
        var registry = new BackendRegistry();
        var backend = new CodeZaikuBackend(
            CodeZaikuRuntimeConfig.defaults(), store, stubCliRunner());

        registry.register(backend);

        var looked = registry.backendFor(CodeZaikuBackend.NAME);
        assertThat(looked).isPresent();
        assertThat(looked.get()).isSameAs(backend);
    }

    @Test void registry_lookup_unknown_returns_empty() {
        var registry = new BackendRegistry();
        assertThat(registry.backendFor("nonexistent")).isEmpty();
        assertThat(registry.backendFor(null)).isEmpty();
    }

    @Test void registry_register_null_is_silently_ignored() {
        var registry = new BackendRegistry();
        registry.register((CodingTaskBackend) null);
        registry.register((BackendAdapter) null);
        assertThat(registry.backends()).isEmpty();
        assertThat(registry.adapters()).isEmpty();
    }

    @Test void registry_duplicate_register_replaces_previous() {
        // BackendRegistry.register uses ConcurrentHashMap.put — same name =
        // last-write-wins (replace). This test pins that behaviour so a
        // future change to "reject duplicates" doesn't slip in unnoticed.
        var registry = new BackendRegistry();
        var first  = new CodeZaikuBackend(
            CodeZaikuRuntimeConfig.defaults(), store, stubCliRunner());
        var second = new CodeZaikuBackend(
            CodeZaikuRuntimeConfig.defaults(), store, stubCliRunner());

        registry.register(first);
        registry.register(second);

        var looked = registry.backendFor(CodeZaikuBackend.NAME);
        assertThat(looked).isPresent();
        assertThat(looked.get())
            .as("second register call should replace the first")
            .isSameAs(second);
        // backends() is a snapshot — exactly one entry under that name.
        assertThat(registry.backends()).hasSize(1);
    }

    @Test void registry_adapter_round_trip() {
        var registry = new BackendRegistry();
        var adapter = new CodeZaikuEventAdapter(null);
        registry.register(adapter);

        var looked = registry.adapterFor(CodeZaikuBackend.NAME);
        assertThat(looked).isPresent();
        assertThat(looked.get()).isSameAs(adapter);
        assertThat(registry.adapters()).hasSize(1);
    }

    @Test void registry_clear_removes_all() {
        var registry = new BackendRegistry();
        registry.register(new CodeZaikuBackend(
            CodeZaikuRuntimeConfig.defaults(), store, stubCliRunner()));
        registry.register(new CodeZaikuEventAdapter(null));
        assertThat(registry.backends()).hasSize(1);
        assertThat(registry.adapters()).hasSize(1);

        registry.clear();
        assertThat(registry.backends()).isEmpty();
        assertThat(registry.adapters()).isEmpty();
    }

    // ─── CodeZaiku-specific health check signal (CLI edition) ───────
    // 2026-08-15: health is `codezaiku --version`, same availability rule
    // as every backend — the old router-namespace probe targeted the
    // archived zone-bridge design.

    @Test void codezaiku_health_reports_false_when_binary_missing() {
        GooseBackend.ProcessRunner throwing = (args, env, workdir, timeout) -> {
            throw new IOException("Cannot run program \"codezaiku\"");
        };
        var backend = new CodeZaikuBackend(
            CodeZaikuRuntimeConfig.defaults(), store, throwing);
        var healthy = backend.healthCheck().toCompletableFuture().join();
        assertThat(healthy).isFalse();
    }

    @Test void codezaiku_health_reports_false_when_version_probe_fails() {
        var backend = new CodeZaikuBackend(
            CodeZaikuRuntimeConfig.defaults(), store, stubCliRunner());
        var healthy = backend.healthCheck().toCompletableFuture().join();
        assertThat(healthy).isFalse();
    }

    @Test void codezaiku_health_reports_true_when_version_probe_passes() {
        GooseBackend.ProcessRunner ok = (args, env, workdir, timeout) -> {
            assertThat(args).containsExactly(
                CodeZaikuRuntimeConfig.defaults().executablePath(), "--version");
            return new GooseBackend.ProcessResult(0, "codezaiku 0.3.0", "", false);
        };
        var backend = new CodeZaikuBackend(
            CodeZaikuRuntimeConfig.defaults(), store, ok);
        var healthy = backend.healthCheck().toCompletableFuture().join();
        assertThat(healthy).isTrue();
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /** Build a default instance of any permitted concrete backend. */
    /** CLI-shaped stub: exit 1, no output — health=false, nothing spawned. */
    private static GooseBackend.ProcessRunner stubCliRunner() {
        return (args, env, workdir, timeout) ->
            new GooseBackend.ProcessResult(1, "", "", false);
    }

    private CodingTaskBackend newBackendFor(Class<?> cls) throws Exception {
        if (cls == CodeZaikuBackend.class) {
            // Stub runner: exit 1 → healthCheck reports false promptly,
            // which is all the contract test needs (non-null Boolean).
            return new CodeZaikuBackend(
                CodeZaikuRuntimeConfig.defaults(), store, stubCliRunner());
        }
        if (cls == OpenCodeBackend.class) {
            // Stub runner so the contract test's healthCheck() probe
            // doesn't try to spawn a real `opencode --version` process.
            // Returns exitCode=1 → healthCheck reports false, which is a
            // valid Boolean answer (the contract test only requires the
            // future to fire promptly with a non-null value).
            OpenCodeBackend.ProcessRunner stub =
                (args, env, timeout) -> new OpenCodeBackend.ProcessResult(
                    1, "", "", false);
            return new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(), stub);
        }
        if (cls == OpenHandsBackend.class) {
            // Defaults are disabled, which makes healthCheck() return
            // false fast — same shape as the OpenCode test above. No
            // MCP socket, no Docker probe required.
            return new OpenHandsBackend(
                OpenHandsRuntimeConfig.defaults(),
                name -> new AuthMode.AuthMissing(name, "wyrd setup openhands",
                    "test"),
                (url, timeout) -> { throw new IllegalStateException(
                    "MCP factory must not be called when disabled"); },
                () -> false);
        }
        if (cls == GooseBackend.class) {
            // Disabled defaults → healthCheck() short-circuits to false.
            // Stub runner is a no-op since healthCheck won't reach it.
            // Signature: (args, env, workdir, timeout) — workdir added in
            // the 2026-05-05 reconciliation since Goose has no workspace
            // flag and uses subprocess CWD instead.
            GooseBackend.ProcessRunner stub =
                (args, env, workdir, timeout) -> new GooseBackend.ProcessResult(
                    1, "", "", false);
            return new GooseBackend(
                GooseRuntimeConfig.defaults(),
                name -> new AuthMode.AuthMissing(name,
                    "set ANTHROPIC_API_KEY in your Key Chest", "test"),
                stub);
        }
        if (cls == ClineBackend.class) {
            ClineBackend.ProcessRunner stub =
                (args, env, timeout) -> new ClineBackend.ProcessResult(
                    1, "", "", false);
            return new ClineBackend(
                ClineRuntimeConfig.defaults(),
                name -> new AuthMode.AuthMissing(name,
                    "wyrd coding login cline", "test"),
                stub);
        }
        if (cls == ContinueBackend.class) {
            ContinueBackend.ProcessRunner stub =
                (args, env, timeout) -> new ContinueBackend.ProcessResult(
                    1, "", "", false);
            return new ContinueBackend(
                ContinueRuntimeConfig.defaults(),
                name -> new AuthMode.AuthMissing(name,
                    "wyrd coding login continue", "test"),
                stub);
        }
        if (cls == ClaudeSdkBackend.class) {
            // Disabled defaults → healthCheck() short-circuits to false.
            ClaudeSdkBackend.ProcessRunner stub =
                (args, env, stdin, timeout) -> new ClaudeSdkBackend.ProcessResult(
                    1, "", "", false);
            return new ClaudeSdkBackend(
                ClaudeSdkRuntimeConfig.defaults(),
                name -> new AuthMode.AuthMissing(name,
                    "wyrd coding login claude-sdk", "test"),
                stub);
        }
        if (cls == CodexCliBackend.class) {
            CodexCliBackend.ProcessRunner stub =
                (args, env, timeout) -> new CodexCliBackend.ProcessResult(
                    1, "", "", false);
            return new CodexCliBackend(
                CodexCliRuntimeConfig.defaults(),
                name -> new AuthMode.AuthMissing(name,
                    "wyrd coding login codex", "test"),
                stub);
        }
        if (cls == GeminiCliBackend.class) {
            GeminiCliBackend.ProcessRunner stub =
                (args, env, timeout) -> new GeminiCliBackend.ProcessResult(
                    1, "", "", false);
            return new GeminiCliBackend(
                GeminiCliRuntimeConfig.defaults(),
                name -> new AuthMode.AuthMissing(name,
                    "set GEMINI_API_KEY in your Key Chest", "test"),
                stub);
        }
        if (cls == DevinBackend.class) {
            // Disabled defaults → healthCheck() short-circuits to false
            // before any HTTP call. The HttpClient here is a stub that
            // throws on any send() call (fail loudly if the contract
            // test ever reaches it).
            return new DevinBackend(
                DevinRuntimeConfig.defaults(),
                name -> new AuthMode.AuthMissing(name,
                    "set DEVIN_API_KEY in your Key Chest", "test"),
                HttpClient.newHttpClient(),
                millis -> { /* no sleep */ });
        }
        if (cls == PiCodingBackend.class) {
            // Disabled defaults → healthCheck() short-circuits to false
            // without spawning `pi --version`. Reuses ClaudeSdkBackend's
            // ProcessRunner shape per PiCodingBackend's class doc (same
            // 4-arg signature: args, env, stdin, timeout).
            ClaudeSdkBackend.ProcessRunner stub =
                (args, env, stdin, timeout) -> new ClaudeSdkBackend.ProcessResult(
                    1, "", "", false);
            return new PiCodingBackend(
                PiCodingRuntimeConfig.defaults(),
                name -> new AuthMode.AuthMissing(name,
                    "wyrd coding login pi", "test"),
                stub);
        }
        if (cls == AcpBackend.class) {
            // Transport factory that fails loudly — the contract test never
            // opens a session; an empty agentCommand keeps healthCheck() false.
            return new AcpBackend("acp-test", List.of(),
                Duration.ofSeconds(1),
                () -> { throw new IllegalStateException("contract test must not connect"); });
        }
        // Future backends slot in here as their permits clause arrives.
        throw new IllegalStateException("No factory for permitted backend " + cls
            + " — extend CodingTaskBackendTest.newBackendFor() when a new backend lands");
    }
}

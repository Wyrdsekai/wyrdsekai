package org.wyrdsekai.core.coding;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.typesafe.config.ConfigFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * coding-backend egress gate unit coverage. Proves the
 * scrub drops ambient credentials, keeps the allowlisted vars + backend env,
 * and that the config default is ENFORCING.
 */
final class EgressGateTest {

    /** Simulates a parent env: what ProcessBuilder.environment() starts as. */
    private static Map<String, String> parentEnv() {
        var env = new HashMap<String, String>();
        env.put("PATH", "/usr/bin:/bin");
        env.put("HOME", "/home/you");
        env.put("LANG", "en_US.UTF-8");
        env.put("SSH_AUTH_SOCK", "/tmp/ssh-XXXX/agent.1234");   // the credential we must drop
        env.put("ANTHROPIC_API_KEY", "sk-ant-should-not-leak");
        env.put("AWS_SECRET_ACCESS_KEY", "very-secret");
        env.put("GITHUB_TOKEN", "ghp_xxx");
        return env;
    }

    /** The backend's resolved overlay (Goose local-llama routing). */
    private static Map<String, String> backendEnv() {
        return Map.of(
            "GOOSE_PROVIDER", "openai",
            "GOOSE_MODEL", "wyrdsekai-3.5-9b-drive-v6-q4km.gguf",
            "OPENAI_HOST", "http://localhost:8200",
            "OPENAI_API_KEY", "not-required");
    }

    /**
     * Phase 0.1 — the SHARED SEAM every subprocess backend now spawns through.
     * Proves {@code gatedProcessBuilder} produces a ProcessBuilder whose env has
     * been scrubbed to the allowlist + backend overlay, so no coding backend
     * (OpenCode/Codex/Gemini/Cline/Continue/ClaudeSdk/Pi — previously ungated)
     * can inherit the daemon's ambient credentials.
     */
    @Test
    void gatedProcessBuilder_scrubs_ambient_env_via_shared_seam() {
        EgressGate.resetDefaultForTest(); // resolve enforcing gate from config default
        var backend = backendEnv();
        var pb = EgressGate.gatedProcessBuilder(List.of("echo", "hi"), backend);
        var env = pb.environment();

        // backend overlay applied
        assertEquals("openai", env.get("GOOSE_PROVIDER"));
        assertEquals("http://localhost:8200", env.get("OPENAI_HOST"));

        // every surviving key is allowlisted or from the backend overlay — proves
        // the REAL parent JVM env was scrubbed (no arbitrary ambient var survives).
        var permitted = new HashSet<>(EgressGate.DEFAULT_ENV_ALLOWLIST);
        permitted.addAll(backend.keySet());
        for (var k : env.keySet()) {
            assertTrue(permitted.contains(k),
                "ambient env var '" + k + "' survived the shared gate — should be scrubbed");
        }
        // the specific credential a daemon holds must be gone regardless
        assertNull(env.get("SSH_AUTH_SOCK"), "SSH_AUTH_SOCK must be scrubbed");
    }

    @Test
    void enforcing_drops_ambient_credentials_keeps_allowlist_and_backend() {
        var env = parentEnv();
        EgressGate.enforcing().applyEnv(env, backendEnv());

        // Credentials scrubbed.
        assertFalse(env.containsKey("SSH_AUTH_SOCK"), "SSH agent socket must be dropped");
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"), "cloud key must be dropped");
        assertFalse(env.containsKey("AWS_SECRET_ACCESS_KEY"), "AWS secret must be dropped");
        assertFalse(env.containsKey("GITHUB_TOKEN"), "GH token must be dropped");

        // Allowlisted parent vars survive (subprocess must still run).
        assertEquals("/usr/bin:/bin", env.get("PATH"));
        assertEquals("/home/you", env.get("HOME"));
        assertEquals("en_US.UTF-8", env.get("LANG"));

        // Backend overlay applied (local-llama routing intact).
        assertEquals("openai", env.get("GOOSE_PROVIDER"));
        assertEquals("http://localhost:8200", env.get("OPENAI_HOST"));
        assertEquals("not-required", env.get("OPENAI_API_KEY"));
    }

    @Test
    void disabled_inherits_everything_then_layers_backend() {
        var env = parentEnv();
        EgressGate.disabled().applyEnv(env, backendEnv());

        // Legacy behavior — nothing scrubbed.
        assertEquals("/tmp/ssh-XXXX/agent.1234", env.get("SSH_AUTH_SOCK"));
        assertEquals("sk-ant-should-not-leak", env.get("ANTHROPIC_API_KEY"));
        // Backend overlay still applied on top.
        assertEquals("openai", env.get("GOOSE_PROVIDER"));
    }

    @Test
    void backend_env_overrides_a_scrubbed_then_readded_key() {
        // If a key is in BOTH the allowlist and the backend env, the backend
        // value wins (it's layered last). OPENAI_API_KEY is allowlisted; the
        // backend supplies the sentinel.
        var env = parentEnv();
        env.put("OPENAI_API_KEY", "stale-parent-value");
        EgressGate.enforcing().applyEnv(env, backendEnv());
        assertEquals("not-required", env.get("OPENAI_API_KEY"));
    }

    @Test
    void config_default_is_enforcing_when_block_absent() {
        var gate = EgressGate.fromConfig(ConfigFactory.parseString("wyrdsekai {}"));
        assertTrue(gate.enabled(), "missing egress-gate block ⇒ enforcing (default ON)");
    }

    @Test
    void config_reads_enabled_and_custom_allowlist() {
        var cfg = ConfigFactory.parseString("""
            wyrdsekai.coding.egress-gate {
              enabled = false
              env-allowlist = ["PATH", "CUSTOM_VAR"]
            }
            """);
        var gate = EgressGate.fromConfig(cfg);
        assertFalse(gate.enabled());
        assertTrue(gate.envAllowlist().contains("CUSTOM_VAR"));
        assertFalse(gate.envAllowlist().contains("HOME"), "custom allowlist replaces the default");
    }

    @Test
    void config_enabled_true_scrubs() {
        var cfg = ConfigFactory.parseString("wyrdsekai.coding.egress-gate.enabled = true");
        var gate = EgressGate.fromConfig(cfg);
        var env = parentEnv();
        gate.applyEnv(env, Map.of());
        assertFalse(env.containsKey("SSH_AUTH_SOCK"));
        assertTrue(env.containsKey("PATH"));
    }

    @Test
    void null_process_env_is_a_safe_noop() {
        assertDoesNotThrow(() -> EgressGate.enforcing().applyEnv(null, Map.of()));
    }
}

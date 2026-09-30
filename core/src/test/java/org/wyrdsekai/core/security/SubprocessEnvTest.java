package org.wyrdsekai.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A program the node starts sees only what its path needs (SECURITY_MODEL.md, Credential
 * isolation). Before 2026-09-28, Python skills, recipe steps, MCP stdio servers, keybase,
 * signal-cli and the Claude CLI inherited the daemon's whole environment.
 */
class SubprocessEnvTest {

    private static Map<String, String> daemonEnv() {
        var env = new HashMap<String, String>();
        env.put("PATH", "/usr/bin:/bin");
        env.put("HOME", "/var/lib/wyrdsekai");
        env.put("LANG", "C.UTF-8");
        env.put("WYRDSEKAI_DATA_DIR", "/var/lib/wyrdsekai");
        env.put("WYRDSEKAI_CRED_TWILIO_AUTH_TOKEN", "tw-secret");
        env.put("WYRDSEKAI_MCP_KEY_RESEARCHZOSHO", "rz-secret");
        env.put("WYRDSEKAI_LIBRARY_WEBHOOK_SECRET_RESEARCHZOSHO", "hook-secret");
        env.put("OPENAI_API_KEY", "sk-secret");
        env.put("AWS_SECRET_ACCESS_KEY", "aws-secret");
        env.put("SSH_AUTH_SOCK", "/tmp/ssh-agent.sock");
        env.put("XDG_RUNTIME_DIR", "/run/user/1000");
        return env;
    }

    @Test
    void the_base_keeps_what_a_program_needs_and_no_credential() {
        var env = daemonEnv();
        SubprocessEnv.of().apply(env);
        assertThat(env).containsOnlyKeys("PATH", "HOME", "LANG");
    }

    @Test
    void a_path_adds_only_the_names_it_declares() {
        var env = daemonEnv();
        SubprocessEnv.of("XDG_RUNTIME_DIR").apply(env);
        assertThat(env).containsOnlyKeys("PATH", "HOME", "LANG", "XDG_RUNTIME_DIR");
    }

    @Test
    void a_prefix_rule_never_passes_a_secret_shaped_name() {
        var env = daemonEnv();
        SubprocessEnv.of().withPrefix("WYRDSEKAI_").apply(env);
        assertThat(env).containsOnlyKeys("PATH", "HOME", "LANG", "WYRDSEKAI_DATA_DIR");
        assertThat(SubprocessEnv.secretShaped("WYRDSEKAI_JDBC_URL")).isFalse();
        assertThat(SubprocessEnv.secretShaped("WYRDSEKAI_RELAY_PASSWORD")).isTrue();
    }

    @Test
    void the_transition_setting_restores_full_inheritance() {
        var env = daemonEnv();
        SubprocessEnv.of().withSettings(name -> SubprocessEnv.FULL_ENV_SETTING.equals(name) ? "true" : null)
            .apply(env);
        assertThat(env).isEqualTo(daemonEnv());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_started_program_does_not_see_the_daemons_secrets() throws Exception {
        var pb = new ProcessBuilder("/usr/bin/env");
        // As if the daemon held these: the builder starts as a copy of its environment.
        pb.environment().putAll(daemonEnv());
        SubprocessEnv.of().apply(pb.environment());
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out).contains("PATH=/usr/bin:/bin")
            .doesNotContain("tw-secret").doesNotContain("rz-secret").doesNotContain("hook-secret")
            .doesNotContain("sk-secret").doesNotContain("aws-secret").doesNotContain("SSH_AUTH_SOCK");
    }
}

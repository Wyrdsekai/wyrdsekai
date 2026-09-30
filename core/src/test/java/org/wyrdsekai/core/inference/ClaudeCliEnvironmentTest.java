package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The Claude CLI keeps its own key; no other daemon credential reaches it (2026-09-28 audit). */
class ClaudeCliEnvironmentTest {

    @Test
    void the_cli_keeps_its_own_key_and_nothing_else_secret() {
        var env = new HashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("HOME", "/home/steward");
        env.put("ANTHROPIC_API_KEY", "its-own");
        env.put("CLAUDECODE", "1");
        env.put("OPENAI_API_KEY", "sk-secret");
        env.put("WYRDSEKAI_CRED_TWILIO_AUTH_TOKEN", "tw-secret");
        env.put("CODEZAIKU_AUTH_TOKEN", "cz-secret");
        ClaudeCliInference.ENV.apply(env);
        assertThat(env).containsOnlyKeys("PATH", "HOME", "ANTHROPIC_API_KEY");
    }
}

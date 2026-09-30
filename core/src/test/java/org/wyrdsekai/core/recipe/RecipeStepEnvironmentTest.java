package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recipe's shell step ran as {@code bash -c} with the daemon's whole environment
 * (2026-09-28 audit). It now gets the node's own non-secret settings and nothing else.
 */
class RecipeStepEnvironmentTest {

    /** Variables bash sets for itself in every shell. */
    private static final Set<String> SHELL_OWN = Set.of("PWD", "OLDPWD", "SHLVL", "_");

    @TempDir Path dir;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_step_sees_only_what_a_recipe_needs() {
        var result = new ProcessCommandRunner(dir.toFile(), Duration.ofSeconds(20)).run("env");
        assertThat(result.exitCode()).isZero();
        var leaked = result.stdout().lines()
            .filter(l -> l.contains("="))
            .map(l -> l.substring(0, l.indexOf('=')))
            .filter(name -> !SHELL_OWN.contains(name) && !ProcessCommandRunner.ENV.allows(name))
            .toList();
        assertThat(leaked).as("the step saw variables no recipe needs").isEmpty();
    }

    @Test
    void the_nodes_settings_pass_and_its_secrets_do_not() {
        var env = new HashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("WYRDSEKAI_DATA_DIR", "/var/lib/wyrdsekai");
        env.put("WYRDSEKAI_JDBC_URL", "jdbc:sqlite:/var/lib/wyrdsekai/world.db");
        env.put("CUDA_VISIBLE_DEVICES", "0");
        env.put("WYRDSEKAI_CRED_GOOGLEMAPS_API_KEY", "g-secret");
        env.put("WYRDSEKAI_MCP_KEY_RESEARCHZOSHO", "rz-secret");
        env.put("HF_TOKEN", "hf-secret");
        env.put("SSH_AUTH_SOCK", "/tmp/agent.sock");
        ProcessCommandRunner.ENV.apply(env);
        assertThat(env).containsOnlyKeys("PATH", "WYRDSEKAI_DATA_DIR", "WYRDSEKAI_JDBC_URL", "CUDA_VISIBLE_DEVICES");
    }
}

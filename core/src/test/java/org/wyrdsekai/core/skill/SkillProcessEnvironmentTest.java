package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.skill.impl.KeybaseSkillExecutor;
import org.wyrdsekai.core.skill.impl.SignalSkillExecutor;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python skills, keybase and signal-cli ran with the daemon's whole environment (2026-09-28
 * audit). Each now gets a clean environment plus the few names its program needs.
 */
class SkillProcessEnvironmentTest {

    private static final String AGENT = "did:key:z6Mkenv";

    @TempDir Path workspace;

    static boolean pythonAvailable() {
        return PythonSkillExecutor.isAvailable();
    }

    @Test
    @EnabledIf("pythonAvailable")
    void a_python_skill_sees_only_its_own_environment() {
        var bud = SoulBud.original(AGENT, "z6Mkkey", "family-env", "locker://test", "test-node", "m");
        var executor = new PythonSkillExecutor(FamilyLocker.create("family-env", "locker://test", bud), AGENT, workspace);
        var code = "import os\nprint('\\n'.join(sorted(os.environ)))";
        var def = SkillItemCodec.create("python", code, null, "env", null, null);
        executor.register("env", SkillItemCodec.toSoulItem("env", def, AGENT), def);

        var result = executor.execute("workbench.env", Map.of(), SkillContext.forAgent(AGENT, "workshop", Map.of(), 1000));
        assertThat(result.success()).as(result.output()).isTrue();
        var leaked = result.output().lines().map(String::strip).filter(n -> !n.isEmpty())
            .filter(n -> !PythonSkillExecutor.ENV.allows(n)).toList();
        assertThat(leaked).as("a skill script saw variables it does not need").isEmpty();
    }

    @Test
    void keybase_and_signal_keep_what_they_need_and_nothing_secret() {
        var env = daemonEnv();
        KeybaseSkillExecutor.ENV.apply(env);
        assertThat(env).containsOnlyKeys("PATH", "HOME", "XDG_RUNTIME_DIR");

        env = daemonEnv();
        SignalSkillExecutor.ENV.apply(env);
        assertThat(env).containsOnlyKeys("PATH", "HOME", "JAVA_HOME");
    }

    private static Map<String, String> daemonEnv() {
        var env = new HashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("HOME", "/var/lib/wyrdsekai");
        env.put("XDG_RUNTIME_DIR", "/run/user/998");
        env.put("JAVA_HOME", "/usr/lib/jvm/java-25");
        env.put("WYRDSEKAI_CRED_TWILIO_AUTH_TOKEN", "tw-secret");
        env.put("OPENAI_API_KEY", "sk-secret");
        env.put("SSH_AUTH_SOCK", "/tmp/agent.sock");
        return env;
    }
}

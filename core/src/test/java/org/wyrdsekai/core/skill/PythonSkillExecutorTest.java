package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PythonSkillExecutor.
 * Tests that require Python 3 are conditionally enabled.
 */
class PythonSkillExecutorTest {

    private static final String AGENT_DID = "did:key:z6Mkpython";
    private static final String FAMILY_ID = "family-python-1";

    @TempDir
    Path workspace;

    private FamilyLocker locker;
    private PythonSkillExecutor executor;

    @BeforeEach
    void setUp() {
        var bud = SoulBud.original(AGENT_DID, "z6MkpublicKey", FAMILY_ID,
            "locker://test", "test-node", "qwen2.5:7b");
        locker = FamilyLocker.create(FAMILY_ID, "locker://test", bud);
        executor = new PythonSkillExecutor(locker, AGENT_DID, workspace);
    }

    private SkillContext ctx() {
        return SkillContext.forAgent(AGENT_DID, "workshop", Map.of(), 1000);
    }

    private SkillItemCodec.SkillDefinition makePythonDef(String code) {
        return SkillItemCodec.create("python", code, null, "Test Python skill", null, null);
    }

    static boolean pythonAvailable() {
        return PythonSkillExecutor.isAvailable();
    }

    @Test
    @EnabledIf("pythonAvailable")
    void simple_python_script_executes() {
        String code = "import sys, json\nparams = json.load(sys.stdin)\nprint('hello ' + params.get('name', 'world'))";
        var def = makePythonDef(code);
        var item = SkillItemCodec.toSoulItem("greet", def, AGENT_DID);
        executor.register("greet", item, def);

        var result = executor.execute("workbench.greet", Map.of("name", "Alice"), ctx());
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("hello Alice");
    }

    @Test
    @EnabledIf("pythonAvailable")
    void script_timeout_handled() {
        String code = "import time\ntime.sleep(60)\nprint('done')";
        var def = makePythonDef(code);
        var item = SkillItemCodec.toSoulItem("slow", def, AGENT_DID);
        executor.register("slow", item, def);

        // Use a very short timeout context
        var shortCtx = new SkillContext(AGENT_DID, "workshop", Map.of(), 1000, 2000, false, false, null);
        var result = executor.execute("workbench.slow", Map.of(), shortCtx);
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("timed out");
    }

    @Test
    @EnabledIf("pythonAvailable")
    void stderr_captured_on_error() {
        String code = "import sys\nprint('error message', file=sys.stderr)\nsys.exit(1)";
        var def = makePythonDef(code);
        var item = SkillItemCodec.toSoulItem("failing", def, AGENT_DID);
        executor.register("failing", item, def);

        var result = executor.execute("workbench.failing", Map.of(), ctx());
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("error message");
    }

    @Test
    @EnabledIf("pythonAvailable")
    void is_available_returns_true() {
        assertThat(PythonSkillExecutor.isAvailable()).isTrue();
    }

    @Test
    void tier_is_workbench() {
        assertThat(executor.tier()).isEqualTo(SkillTier.WORKBENCH);
    }

    @Test
    void supports_only_python_skills() {
        var jsDef = SkillItemCodec.create("graaljs", "function execute(p) { return p; }",
            null, "JS skill", null, null);
        var jsItem = SkillItemCodec.toSoulItem("js-skill", jsDef, AGENT_DID);
        executor.register("js-skill", jsItem, jsDef);
        // Should NOT support non-python skills
        assertThat(executor.supports("workbench.js-skill")).isFalse();

        var pyDef = makePythonDef("def execute(params): pass");
        var pyItem = SkillItemCodec.toSoulItem("py-skill", pyDef, AGENT_DID);
        executor.register("py-skill", pyItem, pyDef);
        assertThat(executor.supports("workbench.py-skill")).isTrue();
    }

    @Test
    void unknown_skill_returns_error() {
        var result = executor.execute("workbench.nonexistent", Map.of(), ctx());
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("not found");
    }

    @Test
    void unregister_removes_skill() {
        var def = makePythonDef("def execute(params): pass");
        var item = SkillItemCodec.toSoulItem("temp", def, AGENT_DID);
        executor.register("temp", item, def);
        assertThat(executor.supports("workbench.temp")).isTrue();

        executor.unregister("temp");
        assertThat(executor.supports("workbench.temp")).isFalse();
    }
}

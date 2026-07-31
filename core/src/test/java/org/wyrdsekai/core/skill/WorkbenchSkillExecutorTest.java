package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchSkillExecutorTest {

    private static final String AGENT_DID = "did:key:z6Mktest";
    private static final String FAMILY_ID = "family-test-1";

    private FamilyLocker locker;
    private WorkbenchSkillExecutor executor;

    @BeforeEach
    void setUp() {
        var bud = SoulBud.original(AGENT_DID, "z6MkpublicKey", FAMILY_ID,
            "locker://test", "test-node", "qwen2.5:7b");
        locker = FamilyLocker.create(FAMILY_ID, "locker://test", bud);
        executor = new WorkbenchSkillExecutor(locker, AGENT_DID);
    }

    private SkillContext ctx() {
        return SkillContext.forAgent(AGENT_DID, "workshop", Map.of(), 1000);
    }

    private SkillItemCodec.SkillDefinition makeDef(String runtime) {
        return SkillItemCodec.create(runtime, "function execute(p) { return p; }",
            null, "Test skill", null, null);
    }

    private SoulItem makeSkillItem(String name) {
        var def = makeDef("graaljs");
        return SkillItemCodec.toSoulItem(name, def, AGENT_DID);
    }

    @Test void tier_is_workbench() {
        assertThat(executor.tier()).isEqualTo(SkillTier.WORKBENCH);
    }

    @Test void supports_prefixed_skill_after_register() {
        executor.register("weather", makeSkillItem("weather"), makeDef("graaljs"));
        assertThat(executor.supports("workbench.weather")).isTrue();
    }

    @Test void does_not_support_unprefixed() {
        executor.register("weather", makeSkillItem("weather"), makeDef("graaljs"));
        assertThat(executor.supports("weather")).isFalse();
    }

    @Test void does_not_support_null() {
        assertThat(executor.supports(null)).isFalse();
    }

    @Test void does_not_support_unknown_skill() {
        assertThat(executor.supports("workbench.nonexistent")).isFalse();
    }

    @Test void execute_registered_graaljs_skill() {
        executor.register("weather", makeSkillItem("weather"), makeDef("graaljs"));
        var result = executor.execute("workbench.weather",
            Map.of("city", "Tokyo"), ctx());
        assertThat(result.success()).isTrue();
        // Real GraalJS execution — script returns params directly (function execute(p) { return p; })
        assertThat(result.data()).containsKey("city");
        assertThat(result.executorTier()).isEqualTo(SkillTier.WORKBENCH);
    }

    @Test void execute_with_null_params() {
        executor.register("test", makeSkillItem("test"), makeDef("graaljs"));
        var result = executor.execute("workbench.test", null, ctx());
        assertThat(result.success()).isTrue();
    }

    @Test void execute_unknown_skill_returns_error() {
        var result = executor.execute("workbench.nonexistent", Map.of(), ctx());
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("not found");
    }

    @Test void execute_shell_runtime_returns_unsupported() {
        executor.register("shell-test", makeSkillItem("shell-test"),
            SkillItemCodec.create("shell", "echo hello", null, "Shell test", null, null));
        var result = executor.execute("workbench.shell-test", Map.of(), ctx());
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("not yet supported");
    }

    @Test void unregister_removes_skill() {
        executor.register("temp", makeSkillItem("temp"), makeDef("graaljs"));
        assertThat(executor.supports("workbench.temp")).isTrue();
        executor.unregister("temp");
        assertThat(executor.supports("workbench.temp")).isFalse();
    }

    @Test void availableSkills_lists_registered() {
        executor.register("alpha", makeSkillItem("alpha"), makeDef("graaljs"));
        executor.register("beta", makeSkillItem("beta"), makeDef("graaljs"));
        var skills = executor.availableSkills();
        assertThat(skills).hasSize(2);
        var ids = skills.stream().map(SkillDefinition::id).toList();
        assertThat(ids).containsExactlyInAnyOrder("workbench.alpha", "workbench.beta");
    }

    @Test void availableSkills_empty_when_none_registered() {
        assertThat(executor.availableSkills()).isEmpty();
    }

    @Test void availableSkills_definitions_have_correct_tier() {
        executor.register("test", makeSkillItem("test"), makeDef("graaljs"));
        var skills = executor.availableSkills();
        assertThat(skills.getFirst().tier()).isEqualTo(SkillTier.WORKBENCH);
    }

    @Test void resolves_from_locker_on_demand() {
        // Store skill item directly in locker (bypassing register)
        var item = makeSkillItem("from-locker");
        locker.store(item, AGENT_DID);

        // Executor should find it via refreshCache
        assertThat(executor.supports("workbench.from-locker")).isTrue();
        var result = executor.execute("workbench.from-locker", Map.of(), ctx());
        assertThat(result.success()).isTrue();
    }
}

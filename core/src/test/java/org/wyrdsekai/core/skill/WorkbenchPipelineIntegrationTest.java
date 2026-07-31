package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.agent.ActionParser.AgentAction;
import org.wyrdsekai.core.agent.AgentPermissions;
import org.wyrdsekai.core.agent.ZonePermission;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;
import org.wyrdsekai.scripting.sandbox.SandboxLevel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-module integration tests for the Workbench Runtime pipeline.
 * Tests parse (ActionParser) -> validate (WorkbenchValidator) -> permission
 * check (AgentPermissions) -> sandbox level selection -> execute.
 *
 * <p>GraalJS sandbox execution tests live in the scripting module at
 * {@code scripting/src/test/.../WorkbenchIntegrationTest.java}.
 * These tests cover the core-side pipeline without directly
 * constructing GraalJS contexts (which aren't on core's test classpath).
 */
class WorkbenchPipelineIntegrationTest {

    @TempDir
    Path workspace;

    // -----------------------------------------------------------------------
    // Test 6: workbench_submit_validates_and_executes_graaljs
    // Full pipeline: parse -> validate -> determine level -> verify executable
    // -----------------------------------------------------------------------

    @Test
    void workbench_submit_parses_validates_and_resolves_level() {
        // Step 1: Simulate LLM output with a workbench_submit action
        String llmOutput = """
            I'll create a greeting skill for you.

            ```json
            {
              "action": "workbench_submit",
              "skill_name": "greet",
              "skill_description": "Generates a greeting message",
              "runtime": "graaljs",
              "code": "function execute(params) { return 'Hello, ' + params.name + '!'; }",
              "params": [
                {"name": "name", "type": "string", "description": "Name to greet", "required": true}
              ],
              "test_cases": [
                {"params": {"name": "World"}, "expect_success": true, "expect_contains": "Hello, World!"}
              ]
            }
            ```
            """;

        // Step 2: Parse via ActionParser
        AgentAction action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.WorkbenchSubmit.class);

        var submit = (AgentAction.WorkbenchSubmit) action;
        assertThat(submit.skillName()).isEqualTo("greet");
        assertThat(submit.runtime()).isEqualTo("graaljs");
        assertThat(submit.code()).contains("function execute");
        assertThat(submit.params()).hasSize(1);
        assertThat(submit.params().getFirst().name()).isEqualTo("name");
        assertThat(submit.testCases()).hasSize(1);
        assertThat(submit.testCases().getFirst().expectContains()).isEqualTo("Hello, World!");

        // Step 3: Validate via WorkbenchValidator
        var validation = WorkbenchValidator.validate(
            submit.skillName(), submit.runtime(), submit.code(), submit.testCases());
        assertThat(validation.valid()).isTrue();

        // Step 4: Determine sandbox level from agent permissions
        var companionPerms = AgentPermissions.companion();
        var level = companionPerms.maxSandboxLevel();
        // Companion gets SKILL_BASIC — sufficient for GraalJS with http/crypto
        assertThat(level).isEqualTo(SandboxLevel.SKILL_BASIC);
        assertThat(level.includes(SandboxLevel.SKILL_BASIC)).isTrue();
        // Verify runtime is supported
        assertThat(WorkbenchValidator.isSupportedRuntime(submit.runtime())).isTrue();

        // Step 5: Package as SoulItem
        var skillDef = SkillItemCodec.create(
            submit.runtime(), submit.code(), List.of(),
            submit.skillDescription(), List.of(), List.of());
        var soulItem = SkillItemCodec.toSoulItem(submit.skillName(), skillDef, "did:key:test");
        assertThat(soulItem.category()).isEqualTo("skill");
        assertThat(soulItem.label()).isEqualTo("greet");
        assertThat(soulItem.verifyIntegrity()).isTrue();

        // Verify the item can be decoded back
        var decoded = SkillItemCodec.decode(soulItem);
        assertThat(decoded).isNotNull();
        assertThat(decoded.runtime()).isEqualTo("graaljs");
        assertThat(decoded.code()).contains("function execute");
    }

    @Test
    void workbench_submit_validation_rejects_bad_input() {
        // Missing function execute
        var result = WorkbenchValidator.validate(
            "test-skill", "graaljs", "var x = 1;", null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("function execute"));

        // Unsupported runtime
        result = WorkbenchValidator.validate(
            "test-skill", "ruby", "def execute; end", null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Unsupported runtime"));

        // Empty skill name
        result = WorkbenchValidator.validate(
            "", "graaljs", "function execute() {}", null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("required"));

        // Code too large
        var hugeCode = "function execute() {" + "x".repeat(WorkbenchValidator.MAX_CODE_SIZE) + "}";
        result = WorkbenchValidator.validate("big", "graaljs", hugeCode, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("maximum size"));
    }

    // -----------------------------------------------------------------------
    // Test 7: workbench_submit_python_executes
    // -----------------------------------------------------------------------

    @Test
    void workbench_submit_python_executes() {
        Assumptions.assumeTrue(PythonSkillExecutor.isAvailable(),
            "Python 3 not available — skipping");

        // Parse a Python workbench_submit action
        String llmOutput = """
            I'll write a Python skill for this.

            ```json
            {
              "action": "workbench_submit",
              "skill_name": "py-adder",
              "skill_description": "Adds two numbers",
              "runtime": "python",
              "code": "import sys, json\\ndef execute(params):\\n    return int(params.get('a', 0)) + int(params.get('b', 0))\\nif __name__ == '__main__':\\n    params = json.loads(sys.stdin.read())\\n    print(execute(params))"
            }
            ```
            """;

        // Parse
        AgentAction action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.WorkbenchSubmit.class);

        var submit = (AgentAction.WorkbenchSubmit) action;
        assertThat(submit.runtime()).isEqualTo("python");

        // Validate
        var validation = WorkbenchValidator.validate(
            submit.skillName(), submit.runtime(), submit.code(), submit.testCases());
        assertThat(validation.valid()).isTrue();

        // Execute via PythonSkillExecutor
        String agentDid = "did:key:test-agent";
        var bud = SoulBud.original(agentDid, "z6Mk...", "family-1",
            "locker-addr", "node-1", "test");
        var locker = FamilyLocker.create("family-1", "locker-addr", bud);

        // Create and store the skill item in the locker
        var skillDef = SkillItemCodec.create(
            submit.runtime(), submit.code(), List.of(),
            submit.skillDescription(), List.of(), List.of());
        var soulItem = SkillItemCodec.toSoulItem(submit.skillName(), skillDef, agentDid);
        locker.store(soulItem, agentDid);

        // Create executor and register the skill
        var executor = new PythonSkillExecutor(locker, agentDid, workspace);
        executor.register(submit.skillName(), soulItem, skillDef);

        // Execute
        var context = SkillContext.forAgent(agentDid, "workshop", Map.of(), 1000);
        var result = executor.execute("workbench." + submit.skillName(),
            Map.of("a", "3", "b", "7"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.output().trim()).isEqualTo("10");
    }

    // -----------------------------------------------------------------------
    // Test 8: agent_permission_determines_sandbox_level
    // -----------------------------------------------------------------------

    @Test
    void agent_permission_determines_sandbox_level() {
        // --- Companion: no sandbox grants -> SKILL_BASIC ---
        var companion = AgentPermissions.companion();
        assertThat(companion.maxSandboxLevel()).isEqualTo(SandboxLevel.SKILL_BASIC);
        assertThat(companion.maxSandboxLevel().includes(SandboxLevel.ROOM_SCRIPT)).isTrue();
        assertThat(companion.maxSandboxLevel().includes(SandboxLevel.SKILL_DATA)).isFalse();

        // --- New agent: read-only -> SKILL_BASIC ---
        var newAgent = AgentPermissions.newAgent();
        assertThat(newAgent.maxSandboxLevel()).isEqualTo(SandboxLevel.SKILL_BASIC);

        // --- Companion with sandbox.data grant -> SKILL_DATA ---
        var dataAgent = AgentPermissions.companion().withAdditional(List.of(
            new ZonePermission("sandbox", "data", ZonePermission.PermissionLevel.ALLOW)
        ));
        assertThat(dataAgent.maxSandboxLevel()).isEqualTo(SandboxLevel.SKILL_DATA);
        assertThat(dataAgent.maxSandboxLevel().includes(SandboxLevel.SKILL_BASIC)).isTrue();
        assertThat(dataAgent.maxSandboxLevel().includes(SandboxLevel.SKILL_SERVER)).isFalse();

        // --- Agent with sandbox.server grant -> SKILL_SERVER ---
        var serverAgent = AgentPermissions.companion().withAdditional(List.of(
            new ZonePermission("sandbox", "server", ZonePermission.PermissionLevel.ALLOW)
        ));
        assertThat(serverAgent.maxSandboxLevel()).isEqualTo(SandboxLevel.SKILL_SERVER);

        // --- Agent with sandbox.full grant -> SKILL_FULL ---
        var fullAgent = AgentPermissions.companion().withAdditional(List.of(
            new ZonePermission("sandbox", "full", ZonePermission.PermissionLevel.ALLOW)
        ));
        assertThat(fullAgent.maxSandboxLevel()).isEqualTo(SandboxLevel.SKILL_FULL);
        assertThat(fullAgent.maxSandboxLevel().includes(SandboxLevel.SKILL_DATA)).isTrue();
        assertThat(fullAgent.maxSandboxLevel().includes(SandboxLevel.SKILL_SERVER)).isTrue();

        // --- Unrestricted: full access -> SKILL_FULL ---
        var unrestricted = AgentPermissions.unrestricted();
        assertThat(unrestricted.maxSandboxLevel()).isEqualTo(SandboxLevel.SKILL_FULL);

        // --- Engineer: no special sandbox grants -> SKILL_BASIC ---
        var engineer = AgentPermissions.engineer();
        assertThat(engineer.maxSandboxLevel()).isEqualTo(SandboxLevel.SKILL_BASIC);

        // --- Warden: no special sandbox grants -> SKILL_BASIC ---
        var warden = AgentPermissions.warden();
        assertThat(warden.maxSandboxLevel()).isEqualTo(SandboxLevel.SKILL_BASIC);
    }

    @Test
    void deny_overrides_sandbox_grant() {
        // Agent has both ALLOW for sandbox.full and DENY for sandbox.*
        var denied = new AgentPermissions(List.of(
            new ZonePermission("sandbox", "full", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("sandbox", "*", ZonePermission.PermissionLevel.DENY)
        ));
        // DENY takes precedence
        assertThat(denied.maxSandboxLevel()).isEqualTo(SandboxLevel.SKILL_BASIC);
    }

    @Test
    void prose_extraction_works_alongside_action_parsing() {
        String llmOutput = """
            Sure, let me build that weather checking skill for you!

            ```json
            {
              "action": "workbench_submit",
              "skill_name": "weather-check",
              "skill_description": "Checks weather via HTTP",
              "runtime": "graaljs",
              "code": "function execute(params) { return http.get('http://api.weather.com/' + params.city); }"
            }
            ```
            """;

        // Both action and prose should be extractable
        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.WorkbenchSubmit.class);

        var prose = ActionParser.extractProse(llmOutput);
        assertThat(prose).contains("weather checking skill");
    }
}

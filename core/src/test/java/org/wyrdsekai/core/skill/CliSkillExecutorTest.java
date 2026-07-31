package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CliSkillExecutor — fork+exec skill execution.
 */
class CliSkillExecutorTest {

    private static final SkillDefinition ECHO_DEF = SkillDefinition.native_(
        "test.cli.echo", "Echo", "Echo input", "test", List.of(), SkillAuth.NONE);

    // ── Binding Registration ────────────────────────────────────────────

    @Nested
    class BindingTests {

        @Test
        void register_and_supports() {
            var executor = new CliSkillExecutor();
            executor.registerBinding(new CliSkillExecutor.CliSkillBinding(
                "test.cli.echo", "echo", ECHO_DEF,
                params -> List.of(String.valueOf(params.getOrDefault("msg", "hello"))),
                Map.of()
            ));

            assertTrue(executor.supports("test.cli.echo"));
            assertFalse(executor.supports("test.cli.nonexistent"));
        }

        @Test
        void available_skills_lists_bound() {
            var executor = new CliSkillExecutor();
            executor.registerBinding(new CliSkillExecutor.CliSkillBinding(
                "a.skill", "echo",
                SkillDefinition.native_("a.skill", "A", "d", "r", List.of(), SkillAuth.NONE),
                null, Map.of()
            ));
            executor.registerBinding(new CliSkillExecutor.CliSkillBinding(
                "b.skill", "echo",
                SkillDefinition.native_("b.skill", "B", "d", "r", List.of(), SkillAuth.NONE),
                null, Map.of()
            ));

            assertEquals(2, executor.availableSkills().size());
        }

        @Test
        void tier_is_cli() {
            var executor = new CliSkillExecutor();
            assertEquals(SkillTier.CLI, executor.tier());
        }

        @Test
        void null_skill_id_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                new CliSkillExecutor.CliSkillBinding(null, "echo", ECHO_DEF, null, Map.of()));
        }

        @Test
        void null_binary_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                new CliSkillExecutor.CliSkillBinding("test", null, ECHO_DEF, null, Map.of()));
        }

        @Test
        void null_env_vars_defaulted() {
            var binding = new CliSkillExecutor.CliSkillBinding(
                "test", "echo", ECHO_DEF, null, null);
            assertNotNull(binding.envVars());
            assertTrue(binding.envVars().isEmpty());
        }
    }

    // ── Arg Building ────────────────────────────────────────────────────

    @Nested
    class ArgBuildingTests {

        @Test
        void custom_arg_mapper() {
            var binding = new CliSkillExecutor.CliSkillBinding(
                "test", "echo", ECHO_DEF,
                params -> List.of("-n", String.valueOf(params.get("msg"))),
                Map.of()
            );

            var args = binding.buildArgs(Map.of("msg", "hello"));
            assertEquals(List.of("-n", "hello"), args);
        }

        @Test
        void default_arg_mapper_uses_key_value() {
            var binding = new CliSkillExecutor.CliSkillBinding(
                "test", "echo", ECHO_DEF, null, Map.of());

            var args = binding.buildArgs(Map.of("key", "value"));
            assertEquals(2, args.size());
            assertEquals("--key", args.get(0));
            assertEquals("value", args.get(1));
        }

        @Test
        void null_params_returns_empty_args() {
            var binding = new CliSkillExecutor.CliSkillBinding(
                "test", "echo", ECHO_DEF, null, Map.of());

            var args = binding.buildArgs(null);
            assertTrue(args.isEmpty());
        }
    }

    // ── Execution ───────────────────────────────────────────────────────

    @Nested
    class ExecutionTests {

        @Test
        void execute_echo_command() {
            var executor = new CliSkillExecutor();
            executor.registerBinding(new CliSkillExecutor.CliSkillBinding(
                "test.cli.echo", "echo", ECHO_DEF,
                params -> List.of("hello world"),
                Map.of()
            ));

            var ctx = SkillContext.forAgent("did:agent:1", "test", Map.of(), 1000);
            var result = executor.execute("test.cli.echo", Map.of(), ctx);

            assertTrue(result.success());
            assertTrue(result.output().contains("hello world"));
            assertEquals(SkillTier.CLI, result.executorTier());
        }

        @Test
        void execute_nonexistent_skill() {
            var executor = new CliSkillExecutor();
            var ctx = SkillContext.forAgent("did:agent:1", "test", Map.of(), 1000);
            var result = executor.execute("nonexistent", Map.of(), ctx);

            assertFalse(result.success());
            assertTrue(result.output().contains("not available"));
        }

        @Test
        void execute_with_params() {
            var executor = new CliSkillExecutor();
            executor.registerBinding(new CliSkillExecutor.CliSkillBinding(
                "test.cli.printf", "printf",
                SkillDefinition.native_("test.cli.printf", "Printf", "d", "r",
                    List.of(), SkillAuth.NONE),
                params -> List.of("%s %s",
                    String.valueOf(params.getOrDefault("a", "")),
                    String.valueOf(params.getOrDefault("b", ""))),
                Map.of()
            ));

            var ctx = SkillContext.forAgent("did:agent:1", "test", Map.of(), 1000);
            var result = executor.execute("test.cli.printf",
                Map.of("a", "foo", "b", "bar"), ctx);

            assertTrue(result.success());
            assertTrue(result.output().contains("foo bar"));
        }

        @Test
        void execute_failing_command() {
            var executor = new CliSkillExecutor();
            executor.registerBinding(new CliSkillExecutor.CliSkillBinding(
                "test.cli.fail", "false",
                SkillDefinition.native_("test.cli.fail", "Fail", "d", "r",
                    List.of(), SkillAuth.NONE),
                params -> List.of(),
                Map.of()
            ));

            var ctx = SkillContext.forAgent("did:agent:1", "test", Map.of(), 1000);
            var result = executor.execute("test.cli.fail", Map.of(), ctx);

            assertFalse(result.success());
            assertTrue(result.output().contains("exit"));
        }

        @Test
        void successful_result_includes_exit_code() {
            var executor = new CliSkillExecutor();
            executor.registerBinding(new CliSkillExecutor.CliSkillBinding(
                "test.cli.true", "true",
                SkillDefinition.native_("test.cli.true", "True", "d", "r",
                    List.of(), SkillAuth.NONE),
                params -> List.of(),
                Map.of()
            ));

            var ctx = SkillContext.forAgent("did:agent:1", "test", Map.of(), 1000);
            var result = executor.execute("test.cli.true", Map.of(), ctx);

            assertTrue(result.success());
            assertEquals(0, result.data().get("exitCode"));
        }
    }
}

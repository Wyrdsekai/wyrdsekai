package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptSkillExecutorTest {

    private PromptSkillExecutor executor;

    @BeforeEach void setUp() {
        executor = new PromptSkillExecutor();
    }

    @Test void executes_registered_prompt_skill() {
        var def = SkillItemCodec.create(
            "prompt", "Search for {{query}} and return results.",
            null, "Web search", null, null);
        executor.register("web-search", def);

        var ctx = SkillContext.forAgent("did:test", "library", Map.of(), Long.MAX_VALUE);
        var result = executor.execute("web-search", Map.of("query", "recipes"), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("Search for recipes and return results.");
        assertThat(result.executorTier()).isEqualTo(SkillTier.PROMPT);
    }

    @Test void supports_with_prefix() {
        var def = SkillItemCodec.create("prompt", "instructions", null, "", null, null);
        executor.register("test-skill", def);
        assertThat(executor.supports("prompt.test-skill")).isTrue();
        assertThat(executor.supports("test-skill")).isTrue();
    }

    @Test void unavailable_skill() {
        var ctx = SkillContext.forAgent("did:test", "library", Map.of(), Long.MAX_VALUE);
        var result = executor.execute("nonexistent", Map.of(), ctx);
        assertThat(result.success()).isFalse();
    }

    @Test void register_from_skills_md_format() {
        var format = SkillsMdImporter.parse("""
            ---
            name: imported-skill
            description: An imported skill
            ---
            Do the thing with {{param}}.
            """);
        executor.register(format);
        assertThat(executor.supports("imported-skill")).isTrue();
        assertThat(executor.size()).isEqualTo(1);
    }

    @Test void parameter_substitution() {
        assertThat(PromptSkillExecutor.substituteParams(
            "Hello {{name}}, welcome to {{place}}!",
            Map.of("name", "Alice", "place", "Wyrdsekai")
        )).isEqualTo("Hello Alice, welcome to Wyrdsekai!");
    }

    @Test void parameter_substitution_with_null_params() {
        assertThat(PromptSkillExecutor.substituteParams("no params", null))
            .isEqualTo("no params");
    }

    @Test void lists_available_skills() {
        var def = SkillItemCodec.create("prompt", "inst", null, "Test", null, null);
        executor.register("a", def);
        executor.register("b", def);
        assertThat(executor.availableSkills()).hasSize(2);
        assertThat(executor.availableSkills().getFirst().tier()).isEqualTo(SkillTier.PROMPT);
    }
}

package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillsMdImporterTest {

    private static final String BASIC_SKILL_MD = """
        ---
        name: weather-check
        description: Fetches weather for a city
        params:
          - name: city
            type: string
            description: City name
            required: true
        ---
        Use the weather API to fetch current conditions for {{city}}.
        Return temperature and conditions.
        """;

    // --- Parse ---

    @Nested class Parse {
        @Test void parses_basic_skill_md() {
            var result = SkillsMdImporter.parse(BASIC_SKILL_MD);
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("weather-check");
            assertThat(result.description()).isEqualTo("Fetches weather for a city");
            assertThat(result.params()).hasSize(1);
            assertThat(result.params().getFirst().name()).isEqualTo("city");
            assertThat(result.params().getFirst().type()).isEqualTo("string");
            assertThat(result.params().getFirst().required()).isTrue();
            assertThat(result.hasInstructions()).isTrue();
            assertThat(result.instructions()).contains("weather API");
        }

        @Test void parses_minimal_skill_md() {
            var input = """
                ---
                name: ping
                ---
                Just say pong.
                """;
            var result = SkillsMdImporter.parse(input);
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("ping");
            assertThat(result.description()).isEmpty();
            assertThat(result.params()).isEmpty();
        }

        @Test void returns_null_for_no_frontmatter() {
            assertThat(SkillsMdImporter.parse("Just text without frontmatter")).isNull();
        }

        @Test void returns_null_for_null() {
            assertThat(SkillsMdImporter.parse(null)).isNull();
        }

        @Test void returns_null_for_blank() {
            assertThat(SkillsMdImporter.parse("   ")).isNull();
        }

        @Test void returns_null_for_no_name() {
            var input = """
                ---
                description: No name
                ---
                Body text.
                """;
            assertThat(SkillsMdImporter.parse(input)).isNull();
        }

        @Test void parses_multiple_params() {
            var input = """
                ---
                name: multi
                params:
                  - name: a
                    type: string
                    required: true
                  - name: b
                    type: number
                    required: false
                ---
                Do things with {{a}} and {{b}}.
                """;
            var result = SkillsMdImporter.parse(input);
            assertThat(result.params()).hasSize(2);
            assertThat(result.params().get(1).type()).isEqualTo("number");
        }

        @Test void preserves_metadata() {
            var input = """
                ---
                name: custom
                author: someone
                version: 1.0.0
                ---
                Body.
                """;
            var result = SkillsMdImporter.parse(input);
            assertThat(result.metadata()).containsEntry("author", "someone");
            assertThat(result.metadata()).containsEntry("version", "1.0.0");
        }

        @Test void handles_empty_body() {
            var input = """
                ---
                name: empty-body
                ---
                """;
            var result = SkillsMdImporter.parse(input);
            assertThat(result).isNotNull();
            assertThat(result.hasInstructions()).isFalse();
        }
    }

    // --- toSkillDefinition ---

    @Nested class ToSkillDefinition {
        @Test void converts_to_prompt_runtime() {
            var format = SkillsMdImporter.parse(BASIC_SKILL_MD);
            var def = SkillsMdImporter.toSkillDefinition(format);
            assertThat(def).isNotNull();
            assertThat(def.runtime()).isEqualTo("prompt");
            assertThat(def.code()).contains("weather API");
        }

        @Test void maps_params() {
            var format = SkillsMdImporter.parse(BASIC_SKILL_MD);
            var def = SkillsMdImporter.toSkillDefinition(format);
            assertThat(def.params()).hasSize(1);
            assertThat(def.params().getFirst().name()).isEqualTo("city");
        }

        @Test void null_format_returns_null() {
            assertThat(SkillsMdImporter.toSkillDefinition(null)).isNull();
        }
    }

    // --- importSkill ---

    @Nested class ImportSkill {
        @Test void imports_without_sanitizer() {
            var result = SkillsMdImporter.importSkill(BASIC_SKILL_MD, null);
            assertThat(result).isNotNull();
            assertThat(result.format().name()).isEqualTo("weather-check");
            assertThat(result.definition()).isNotNull();
            assertThat(result.wasSanitized()).isFalse();
        }

        @Test void returns_null_for_invalid_input() {
            assertThat(SkillsMdImporter.importSkill("no frontmatter", null)).isNull();
        }
    }

    // --- YAML parser ---

    @Test void parseSimpleYaml_key_value() {
        var yaml = "name: test\ndescription: a thing";
        var result = SkillsMdImporter.parseSimpleYaml(yaml);
        assertThat(result).containsEntry("name", "test");
        assertThat(result).containsEntry("description", "a thing");
    }

    @Test void parseSimpleYaml_ignores_comments() {
        var yaml = "# comment\nname: test";
        var result = SkillsMdImporter.parseSimpleYaml(yaml);
        assertThat(result).containsEntry("name", "test");
        assertThat(result).hasSize(1);
    }
}

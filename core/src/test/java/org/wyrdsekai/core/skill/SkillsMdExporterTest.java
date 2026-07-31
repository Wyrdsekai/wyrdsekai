package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillsMdExporterTest {

    @Test void exports_prompt_skill() {
        var def = SkillItemCodec.create(
            "prompt", "Use the API to search for {{query}}.",
            List.of(new SkillItemCodec.Param("query", "string", "Search query", true)),
            "Searches the web", null, null);
        var result = SkillsMdExporter.export("web-search", def);

        assertThat(result).contains("name: web-search");
        assertThat(result).contains("description: Searches the web");
        assertThat(result).contains("name: query");
        assertThat(result).contains("required: true");
        assertThat(result).contains("Use the API to search");
    }

    @Test void exports_code_skill_with_code_block() {
        var def = SkillItemCodec.create(
            "graaljs", "function execute(p) { return p.city; }",
            null, "Weather check", null, null);
        var result = SkillsMdExporter.export("weather", def);

        assertThat(result).contains("```javascript");
        assertThat(result).contains("function execute");
    }

    @Test void exports_skillsmd_format_roundtrip() {
        var format = new SkillsMdFormat("test-skill", "A test",
            List.of(new SkillsMdFormat.SkillsMdParam("x", "string", "X param", true)),
            "Do the thing.", Map.of());
        var exported = SkillsMdExporter.export(format);
        assertThat(exported).contains("name: test-skill");
        assertThat(exported).contains("Do the thing.");
    }

    @Test void null_definition_returns_null() {
        assertThat(SkillsMdExporter.export("name", (SkillItemCodec.SkillDefinition) null)).isNull();
    }

    @Test void null_format_returns_null() {
        assertThat(SkillsMdExporter.export((SkillsMdFormat) null)).isNull();
    }

    @Test void handles_no_params() {
        var def = SkillItemCodec.create("prompt", "Just do it.", null, "Simple", null, null);
        var result = SkillsMdExporter.export("simple", def);
        assertThat(result).contains("name: simple");
        assertThat(result).doesNotContain("params:");
    }

    @Test void import_export_roundtrip() {
        var original = """
            ---
            name: roundtrip
            description: Test roundtrip
            ---
            Instructions here.
            """;
        var parsed = SkillsMdImporter.parse(original);
        var exported = SkillsMdExporter.export(parsed);
        var reparsed = SkillsMdImporter.parse(exported);

        assertThat(reparsed).isNotNull();
        assertThat(reparsed.name()).isEqualTo("roundtrip");
        assertThat(reparsed.description()).isEqualTo("Test roundtrip");
        assertThat(reparsed.instructions()).contains("Instructions here.");
    }

    @Test void preserves_metadata_in_export() {
        var format = new SkillsMdFormat("meta", "Has metadata",
            List.of(), "Body.", Map.of("author", "test"));
        var exported = SkillsMdExporter.export(format);
        assertThat(exported).contains("author: test");
    }
}

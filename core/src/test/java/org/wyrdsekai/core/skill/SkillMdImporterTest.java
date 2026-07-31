package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SkillMdImporter — OpenClaw SKILL.md parser.
 */
class SkillMdImporterTest {

    private final SkillMdImporter importer = new SkillMdImporter();

    // ── Parsing ─────────────────────────────────────────────────────────

    @Nested
    class ParsingTests {

        @Test
        void parse_single_tool(@TempDir Path tmpDir) throws IOException {
            Path skillMd = tmpDir.resolve("SKILL.md");
            Files.writeString(skillMd, """
                # openhue skills

                ## set_light

                **Description**: Set the brightness of a Hue light

                | Parameter | Type | Required | Description |
                |-----------|------|----------|-------------|
                | light | string | true | Light entity ID |
                | brightness | number | false | Brightness 0-100 |
                """);

            List<SkillDefinition> skills = importer.importFromMarkdown(skillMd, "openhue", "hearth");

            assertEquals(1, skills.size());
            var skill = skills.get(0);
            assertEquals("hearth.openhue.set_light", skill.id());
            assertEquals("set_light", skill.name());
            assertTrue(skill.description().contains("brightness"));
            assertEquals(SkillTier.CLI, skill.tier());
            assertEquals("openclaw/openhue", skill.origin());
            assertEquals("MIT", skill.license());
            assertEquals(2, skill.params().size());

            var lightParam = skill.params().stream()
                .filter(p -> "light".equals(p.name())).findFirst().orElseThrow();
            assertTrue(lightParam.required());
            assertEquals("string", lightParam.type());

            var brightnessParam = skill.params().stream()
                .filter(p -> "brightness".equals(p.name())).findFirst().orElseThrow();
            assertFalse(brightnessParam.required());
        }

        @Test
        void parse_multiple_tools(@TempDir Path tmpDir) throws IOException {
            Path skillMd = tmpDir.resolve("SKILL.md");
            Files.writeString(skillMd, """
                # himalaya skills

                ## list_messages

                **Description**: List email messages

                | Parameter | Type | Required | Description |
                |-----------|------|----------|-------------|
                | folder | string | false | Folder name |

                ## send_message

                **Description**: Send an email message

                | Parameter | Type | Required | Description |
                |-----------|------|----------|-------------|
                | to | string | true | Recipient |
                | subject | string | true | Subject line |
                | body | string | true | Message body |
                """);

            List<SkillDefinition> skills = importer.importFromMarkdown(skillMd, "himalaya", "herald");

            assertEquals(2, skills.size());
            assertEquals("herald.himalaya.list_messages", skills.get(0).id());
            assertEquals("herald.himalaya.send_message", skills.get(1).id());
            assertEquals(1, skills.get(0).params().size());
            assertEquals(3, skills.get(1).params().size());
        }

        @Test
        void parse_tool_without_params(@TempDir Path tmpDir) throws IOException {
            Path skillMd = tmpDir.resolve("SKILL.md");
            Files.writeString(skillMd, """
                ## status

                **Description**: Get system status
                """);

            List<SkillDefinition> skills = importer.importFromMarkdown(skillMd, "sysutil", "engine");

            assertEquals(1, skills.size());
            assertTrue(skills.get(0).params().isEmpty());
        }

        @Test
        void parse_tool_without_description(@TempDir Path tmpDir) throws IOException {
            Path skillMd = tmpDir.resolve("SKILL.md");
            Files.writeString(skillMd, """
                ## mystery_tool

                | Parameter | Type | Required | Description |
                |-----------|------|----------|-------------|
                | input | string | yes | The input |
                """);

            List<SkillDefinition> skills = importer.importFromMarkdown(skillMd, "mystery", "test");

            assertEquals(1, skills.size());
            assertEquals("mystery_tool", skills.get(0).name());
            // Description falls back to name
            assertEquals("mystery_tool", skills.get(0).description());
            assertEquals(1, skills.get(0).params().size());
            assertTrue(skills.get(0).params().get(0).required());
        }

        @Test
        void parse_empty_file(@TempDir Path tmpDir) throws IOException {
            Path skillMd = tmpDir.resolve("SKILL.md");
            Files.writeString(skillMd, "# No tools here\n\nJust some text.\n");

            List<SkillDefinition> skills = importer.importFromMarkdown(skillMd, "empty", "test");
            assertTrue(skills.isEmpty());
        }
    }

    // ── Directory Scanning ──────────────────────────────────────────────

    @Nested
    class ScanningTests {

        @Test
        void scan_directory_with_skill_files(@TempDir Path tmpDir) throws IOException {
            // Create two skill directories
            Path hue = tmpDir.resolve("openhue");
            Files.createDirectories(hue);
            Files.writeString(hue.resolve("SKILL.md"), """
                ## set_light
                **Description**: Set light
                """);

            Path weather = tmpDir.resolve("weather");
            Files.createDirectories(weather);
            Files.writeString(weather.resolve("SKILL.md"), """
                ## get_forecast
                **Description**: Get weather forecast
                | Parameter | Type | Required | Description |
                |-----------|------|----------|-------------|
                | city | string | true | City name |
                """);

            // Create a directory without SKILL.md
            Files.createDirectories(tmpDir.resolve("no-skills"));

            List<SkillDefinition> skills = importer.scanOpenClawSkills(tmpDir, "tools");

            assertEquals(2, skills.size());
        }

        @Test
        void scan_empty_directory(@TempDir Path tmpDir) throws IOException {
            List<SkillDefinition> skills = importer.scanOpenClawSkills(tmpDir, "tools");
            assertTrue(skills.isEmpty());
        }

        @Test
        void bad_skill_md_doesnt_crash_scan(@TempDir Path tmpDir) throws IOException {
            Path good = tmpDir.resolve("good");
            Files.createDirectories(good);
            Files.writeString(good.resolve("SKILL.md"), """
                ## working_tool
                **Description**: This works
                """);

            Path bad = tmpDir.resolve("bad");
            Files.createDirectories(bad);
            // This is a valid file but with no tool definitions
            Files.writeString(bad.resolve("SKILL.md"), "not a valid skill file");

            List<SkillDefinition> skills = importer.scanOpenClawSkills(tmpDir, "tools");
            // Should still get the good tool, bad one produces empty list (no crash)
            assertEquals(1, skills.size());
        }
    }
}

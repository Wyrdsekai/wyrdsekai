package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modern ClawHub / agentskills.io SKILL.md compat — frontmatter + freeform
 * body. Before this format was recognized, contemporary OpenClaw skills
 * imported as ZERO tools (the importer only knew the legacy ## tool-table
 * shape).
 */
class SkillMdImporterModernTest {

    // Shaped like the ClawHub docs example (todoist-cli).
    private static final String MODERN = """
        ---
        name: todoist-cli
        description: Manage Todoist tasks, projects, and labels from the command line.
        version: 1.2.0
        metadata:
          openclaw:
            requires:
              env:
                - TODOIST_API_KEY
              bins:
                - curl
            primaryEnv: TODOIST_API_KEY
        ---
        # Todoist CLI

        Use `curl` against the Todoist REST API.

        ## Listing tasks
        Run `curl -s -H "Authorization: Bearer $TODOIST_API_KEY" ...`
        """;

    private static final String LEGACY = """
        # openhue skills

        ## set-light
        **Description**: Turn a light on or off.

        | name | type | required | description |
        |------|------|----------|-------------|
        | `light` | `string` | true | Light name |
        | `state` | `string` | true | on or off |
        """;

    @TempDir
    Path tmp;

    private final SkillMdImporter importer = new SkillMdImporter();

    @Test
    void modern_frontmatter_imports_one_prompt_skill() {
        var modern = importer.importModern(MODERN, "workshop").orElseThrow();
        var def = modern.definition();
        assertEquals("workshop.todoist-cli", def.id());
        assertEquals(SkillTier.PROMPT, def.tier());
        assertEquals("openclaw/todoist-cli", def.origin());
        assertEquals("MIT-0", def.license());
        assertTrue(def.description().contains("Todoist tasks"));
        assertEquals("1.2.0", modern.version());
        assertEquals(List.of("curl"), modern.requiredBins());
        assertEquals(List.of("TODOIST_API_KEY"), modern.requiredEnv());
        assertEquals("TODOIST_API_KEY", def.auth().credentialKey());
        // The body — including its ## headings — is instructions, NOT tools.
        assertTrue(modern.instructions().contains("Todoist REST API"));
        assertTrue(modern.instructions().contains("## Listing tasks"));
    }

    @Test
    void importFromMarkdown_routes_modern_files_through_frontmatter_parse() throws Exception {
        var file = tmp.resolve("SKILL.md");
        Files.writeString(file, MODERN);
        var skills = importer.importFromMarkdown(file, "todoist-cli", "workshop");
        assertEquals(1, skills.size(), "modern file = one prompt skill, "
            + "not legacy heading-parsed tools");
        assertEquals(SkillTier.PROMPT, skills.getFirst().tier());
    }

    @Test
    void legacy_structured_format_still_parses_cli_tools() throws Exception {
        var file = tmp.resolve("SKILL.md");
        Files.writeString(file, LEGACY);
        var skills = importer.importFromMarkdown(file, "openhue", "hearth");
        assertEquals(1, skills.size());
        var def = skills.getFirst();
        assertEquals(SkillTier.CLI, def.tier());
        assertEquals(2, def.params().size());
    }

    @Test
    void frontmatter_without_name_is_not_modern() {
        assertTrue(importer.importModern("---\nversion: 1.0.0\n---\nbody", "workshop").isEmpty());
        assertTrue(importer.importModern("no frontmatter at all", "workshop").isEmpty());
    }

    @Test
    void scan_picks_up_lowercase_skill_md_directories(@TempDir Path skillsRoot) throws Exception {
        var dir = Files.createDirectories(skillsRoot.resolve("todoist"));
        Files.writeString(dir.resolve("skill.md"), MODERN);
        var skills = importer.scanOpenClawSkills(skillsRoot, "workshop");
        assertFalse(skills.isEmpty(), "lowercase skill.md must be discovered");
        assertEquals("workshop.todoist-cli", skills.getFirst().id());
    }
}

package org.wyrdsekai.core.skill;

import java.util.List;
import java.util.Map;

/**
 * Parsed SKILL.md format — Vercel's cross-agent skill standard.
 *
 * Format: YAML frontmatter (name, description, params) + Markdown body (instructions).
 * Adopted by Claude Code, Cursor, Copilot, 30+ agents.
 *
 * @param name         Skill name (from YAML frontmatter)
 * @param description  What it does (from YAML frontmatter)
 * @param params       Parameter definitions (from YAML frontmatter)
 * @param instructions The markdown body — instructions for the agent
 * @param metadata     Additional YAML fields not captured above
 */
public record SkillsMdFormat(
    String name,
    String description,
    List<SkillsMdParam> params,
    String instructions,
    Map<String, String> metadata
) {
    /** A parameter definition from the YAML frontmatter. */
    public record SkillsMdParam(
        String name,
        String type,
        String description,
        boolean required
    ) {}

    /** Whether this skill has executable instructions. */
    public boolean hasInstructions() {
        return instructions != null && !instructions.isBlank();
    }
}

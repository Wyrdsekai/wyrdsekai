package org.wyrdsekai.core.skill;

/**
 * Execution tier for a skill.
 * Determines how the skill is invoked and what security constraints apply.
 */
public enum SkillTier {
    /** Native MCP skill — built by us, runs in-process or as local MCP server. */
    NATIVE,

    /** Workbench skill — companion-created, executed in GraalJS sandbox. */
    WORKBENCH,

    /** CLI skill — fork+exec an external binary. OpenClaw lightweight path. */
    CLI,

    /** OpenClaw Gateway — containerized execution via WebSocket bridge. */
    OPENCLAW,

    /** Prompt skill — instructions injected into companion's next inference (skills.sh interop). */
    PROMPT
}

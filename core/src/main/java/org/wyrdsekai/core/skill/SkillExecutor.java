package org.wyrdsekai.core.skill;

import java.util.List;
import java.util.Map;

/**
 * Executes skills. Each executor handles one tier (Native, CLI, OpenClaw).
 * The SkillRegistry aggregates executors and resolves the best one per skill.
 */
public interface SkillExecutor {

    /** Execute a skill with the given parameters and context. */
    SkillResult execute(String skillId, Map<String, Object> params, SkillContext context);

    /** List all skills this executor can provide. */
    List<SkillDefinition> availableSkills();

    /** Check if this executor supports a specific skill. */
    boolean supports(String skillId);

    /** Which tier this executor serves. */
    SkillTier tier();
}

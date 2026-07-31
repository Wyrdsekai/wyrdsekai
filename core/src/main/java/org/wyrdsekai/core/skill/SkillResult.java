package org.wyrdsekai.core.skill;

import org.wyrdsekai.common.i18n.I18n;

import java.util.Map;

/**
 * Result of a skill invocation. Immutable, sanitized, auditable.
 *
 * @param success      Whether the skill executed successfully
 * @param output       Sanitized response text (for room narration)
 * @param data         Structured response data (for programmatic use)
 * @param durationMs   How long execution took
 * @param executorTier Which tier served this (NATIVE, CLI, OPENCLAW)
 * @param skillId      Which skill was invoked
 * @param cost         Cost in credits (null if free)
 */
public record SkillResult(
    boolean success,
    String output,
    Map<String, Object> data,
    long durationMs,
    SkillTier executorTier,
    String skillId,
    Double cost
) {
    public static SkillResult ok(String output, Map<String, Object> data,
                                  long durationMs, SkillTier tier, String skillId) {
        return new SkillResult(true, output, data, durationMs, tier, skillId, null);
    }

    public static SkillResult ok(String output, Map<String, Object> data,
                                  long durationMs, SkillTier tier, String skillId, double cost) {
        return new SkillResult(true, output, data, durationMs, tier, skillId, cost);
    }

    public static SkillResult error(String output, long durationMs, SkillTier tier, String skillId) {
        return new SkillResult(false, output, Map.of(), durationMs, tier, skillId, null);
    }

    public static SkillResult denied(String reason, String skillId) {
        return new SkillResult(false, reason, Map.of(), 0, null, skillId, null);
    }

    public static SkillResult unavailable(String skillId) {
        return new SkillResult(false, I18n.get("skill.unavailable", skillId), Map.of(), 0, null, skillId, null);
    }
}

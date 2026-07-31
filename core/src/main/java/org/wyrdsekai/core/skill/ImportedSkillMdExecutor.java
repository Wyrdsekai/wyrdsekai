package org.wyrdsekai.core.skill;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * executor for SKILL.md skills imported at startup
 * (bundled {@code resources/openclaw-skills/} seeds + user-installed
 * {@code $WYRDSEKAI_DATA_DIR/skills/}). Modern ClawHub / agentskills.io
 * (Hermes) skills are PROMPT-tier: the markdown body is instructions the
 * companion acts on, with {@code {{param}}} substitution — the same shape
 * skills.sh uses, but carrying the REAL {@link SkillDefinition} (room,
 * origin, license) instead of the {@code prompt.*} shorthand ids, so the
 * registry's room/agent views and the permission gate see honest metadata.
 */
public final class ImportedSkillMdExecutor implements SkillExecutor {

    private record Entry(SkillDefinition definition, String instructions) {}

    private final Map<String, Entry> skills = new ConcurrentHashMap<>();

    /** Register an imported skill with its instruction body. */
    public void register(SkillDefinition definition, String instructions) {
        if (definition == null || definition.id() == null) return;
        skills.put(definition.id(), new Entry(definition, instructions));
    }

    public int size() {
        return skills.size();
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        var entry = skills.get(skillId);
        if (entry == null) return SkillResult.unavailable(skillId);
        var instructions = entry.instructions();
        if (instructions == null || instructions.isBlank()) {
            return new SkillResult(false, "Imported skill has no instructions.",
                Map.of(), 0, SkillTier.PROMPT, skillId, null);
        }
        var resolved = PromptSkillExecutor.substituteParams(instructions, params);
        return new SkillResult(true, resolved, Map.of("type", "prompt_instruction",
            "origin", entry.definition().origin() == null ? "" : entry.definition().origin()),
            0, SkillTier.PROMPT, skillId, null);
    }

    @Override
    public List<SkillDefinition> availableSkills() {
        return skills.values().stream().map(Entry::definition).toList();
    }

    @Override
    public boolean supports(String skillId) {
        return skillId != null && skills.containsKey(skillId);
    }

    @Override
    public SkillTier tier() {
        return SkillTier.PROMPT;
    }
}

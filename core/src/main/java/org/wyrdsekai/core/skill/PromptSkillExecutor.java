package org.wyrdsekai.core.skill;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executor for PROMPT-tier skills (skills.sh interop).
 *
 * PROMPT skills are instructions that get injected into the companion's
 * next inference, not executable code. The companion acts on the instructions
 * — exactly how skills.sh works in Claude Code.
 *
 * The executor doesn't actually "execute" anything — it formats the
 * instructions with parameter substitution and returns them. The
 * CompanionActor injects the result into the next prompt context.
 */
public class PromptSkillExecutor implements SkillExecutor {

    private static final String PREFIX = "prompt.";

    private final Map<String, SkillItemCodec.SkillDefinition> skills =
        new ConcurrentHashMap<>();

    /** Register a prompt skill for execution. */
    public void register(String skillName, SkillItemCodec.SkillDefinition definition) {
        if (skillName != null && definition != null) {
            skills.put(skillName, definition);
        }
    }

    /** Register from a SkillsMdFormat import. */
    public void register(SkillsMdFormat format) {
        if (format == null) return;
        var def = SkillsMdImporter.toSkillDefinition(format);
        if (def != null) {
            register(format.name(), def);
        }
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String name = skillId.startsWith(PREFIX) ? skillId.substring(PREFIX.length()) : skillId;
        var def = skills.get(name);
        if (def == null) {
            return SkillResult.unavailable(skillId);
        }

        String instructions = def.code();
        if (instructions == null || instructions.isBlank()) {
            return new SkillResult(false, "Prompt skill has no instructions.",
                Map.of(), 0, SkillTier.PROMPT, skillId, null);
        }

        // Substitute parameters into instructions
        String resolved = substituteParams(instructions, params);

        return new SkillResult(true, resolved, Map.of("type", "prompt_instruction"),
            0, SkillTier.PROMPT, skillId, null);
    }

    @Override
    public List<SkillDefinition> availableSkills() {
        return skills.entrySet().stream()
            .map(e -> new SkillDefinition(
                PREFIX + e.getKey(),
                e.getKey(),
                e.getValue().description(),
                null, SkillTier.PROMPT, "skills.sh", null,
                List.of(), null, null, false))
            .toList();
    }

    @Override
    public boolean supports(String skillId) {
        if (skillId == null) return false;
        String name = skillId.startsWith(PREFIX) ? skillId.substring(PREFIX.length()) : skillId;
        return skills.containsKey(name);
    }

    @Override
    public SkillTier tier() {
        return SkillTier.PROMPT;
    }

    /** Number of registered prompt skills. */
    public int size() {
        return skills.size();
    }

    /**
     * Simple parameter substitution: replaces {{paramName}} with the param value.
     */
    static String substituteParams(String template, Map<String, Object> params) {
        if (template == null || params == null || params.isEmpty()) return template;
        String result = template;
        for (var entry : params.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                String.valueOf(entry.getValue()));
        }
        return result;
    }
}

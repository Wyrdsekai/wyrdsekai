package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Between clipboard sync skill executor.
 * Provides shared copy/paste across household nodes.
 * In-memory clipboard; Between transport would sync in production.
 */
public class ClipboardBridgeSkillExecutor implements SkillExecutor {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final AtomicReference<String> clipboard = new AtomicReference<>();

    public ClipboardBridgeSkillExecutor() {
        define(new SkillDefinition("between.clipboard.copy", "Clipboard Copy",
            "Copy text to the shared Between clipboard", "between", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("text", "string", "Text to copy")),
            SkillAuth.NONE, SkillLocality.BETWEEN, true));

        define(new SkillDefinition("between.clipboard.paste", "Clipboard Paste",
            "Paste text from the shared Between clipboard", "between", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0", List.of(),
            SkillAuth.NONE, SkillLocality.BETWEEN, true));
    }

    private void define(SkillDefinition skill) { skills.put(skill.id(), skill); }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();
        return switch (skillId) {
            case "between.clipboard.copy" -> executeCopy(params, start, skillId);
            case "between.clipboard.paste" -> executePaste(start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeCopy(Map<String, Object> params, long start, String skillId) {
        String text = requireParam(params, "text");
        if (text == null)
            return SkillResult.error(I18n.get("skill.param_required", "text"),
                0, SkillTier.NATIVE, skillId);

        clipboard.set(text);
        long elapsed = System.currentTimeMillis() - start;

        return SkillResult.ok(I18n.get("skill.clipboard.copied"),
            Map.of("length", text.length()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executePaste(long start, String skillId) {
        String text = clipboard.get();
        long elapsed = System.currentTimeMillis() - start;

        if (text == null) {
            return SkillResult.ok(I18n.get("skill.clipboard.empty"),
                Map.of(), elapsed, SkillTier.NATIVE, skillId);
        }

        return SkillResult.ok(text,
            Map.of("text", text, "length", text.length()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    /** Direct access for testing and Between sync. */
    public String getClipboard() { return clipboard.get(); }
    public void setClipboard(String value) { clipboard.set(value); }

    private String requireParam(Map<String, Object> params, String key) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? String.valueOf(v) : null;
    }

    @Override public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }
    @Override public boolean supports(String skillId) { return skills.containsKey(skillId); }
    @Override public SkillTier tier() { return SkillTier.NATIVE; }
}

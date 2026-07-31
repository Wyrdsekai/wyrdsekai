package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phone screenshot skill — server-side stub.
 * Returns phone_only error on the server. On the phone, the KMP/RN client
 * intercepts this skill ID before it reaches the server and handles natively.
 */
public class ScreenshotSkillExecutor implements SkillExecutor {

    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    public ScreenshotSkillExecutor() {
        skills.put("bridge.screenshot.capture", new SkillDefinition(
            "bridge.screenshot.capture",
            "Capture Screenshot",
            "Capture a screenshot on the phone",
            "bridge", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(),
            SkillAuth.NONE, SkillLocality.PHONE, false));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        return SkillResult.error(
            I18n.get("skill.phone_only", skillId),
            0, SkillTier.NATIVE, skillId);
    }

    @Override
    public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }

    @Override
    public boolean supports(String skillId) { return skills.containsKey(skillId); }

    @Override
    public SkillTier tier() { return SkillTier.NATIVE; }
}

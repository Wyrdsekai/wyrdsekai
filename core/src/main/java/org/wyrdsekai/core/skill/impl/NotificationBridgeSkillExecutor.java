package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phone notification bridge for the Hearth room.
 * Server-side stub that returns phone_only when not running on a phone.
 * Actual implementation lives in KMP (Android) and RN (iOS) clients.
 */
public class NotificationBridgeSkillExecutor implements SkillExecutor {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    public NotificationBridgeSkillExecutor() {
        define(new SkillDefinition("hearth.notification.recent",
            "Recent Notifications", "Get recent phone notifications",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("limit", "number", "Max notifications to return")),
            SkillAuth.NONE, SkillLocality.PHONE, true));

        define(new SkillDefinition("hearth.notification.dismiss",
            "Dismiss Notification", "Dismiss a specific notification",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("id", "string", "Notification ID")),
            SkillAuth.NONE, SkillLocality.PHONE, true));
    }

    private void define(SkillDefinition skill) {
        skills.put(skill.id(), skill);
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "hearth.notification.recent" -> {
                long elapsed = System.currentTimeMillis() - start;
                yield SkillResult.ok(I18n.get("skill.notification.recent"),
                    Map.of("notifications", List.of()),
                    elapsed, SkillTier.NATIVE, skillId);
            }
            case "hearth.notification.dismiss" -> {
                long elapsed = System.currentTimeMillis() - start;
                yield SkillResult.error(I18n.get("skill.notification.phone_only"),
                    elapsed, SkillTier.NATIVE, skillId);
            }
            default -> SkillResult.unavailable(skillId);
        };
    }

    @Override
    public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }

    @Override
    public boolean supports(String skillId) { return skills.containsKey(skillId); }

    @Override
    public SkillTier tier() { return SkillTier.NATIVE; }
}

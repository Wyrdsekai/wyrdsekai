package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Health data bridge skill executor (phone-side stub).
 * Returns phone_only errors when running on the server.
 * Real implementation lives on the phone node (KMP/RN) where HealthKit/Health Connect
 * APIs are available.
 */
public class HealthBridgeSkillExecutor implements SkillExecutor {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    public HealthBridgeSkillExecutor() {
        define(new SkillDefinition("hearth.health.steps", "Health Steps",
            "Get step count data", "hearth", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("date", "string", "Date (YYYY-MM-DD, default: today)"),
                     SkillParam.optional("days", "number", "Number of days to retrieve")),
            SkillAuth.NONE, SkillLocality.PHONE, true));

        define(new SkillDefinition("hearth.health.sleep", "Health Sleep",
            "Get sleep tracking data", "hearth", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("date", "string", "Date (YYYY-MM-DD, default: last night)"),
                     SkillParam.optional("days", "number", "Number of days to retrieve")),
            SkillAuth.NONE, SkillLocality.PHONE, true));

        define(new SkillDefinition("hearth.health.heartrate", "Health Heart Rate",
            "Get heart rate data", "hearth", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("date", "string", "Date (YYYY-MM-DD, default: today)"),
                     SkillParam.optional("period", "string", "Period: hour, day, week")),
            SkillAuth.NONE, SkillLocality.PHONE, true));

        define(new SkillDefinition("hearth.health.summary", "Health Summary",
            "Get daily health summary", "hearth", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("date", "string", "Date (YYYY-MM-DD, default: today)")),
            SkillAuth.NONE, SkillLocality.PHONE, true));
    }

    private void define(SkillDefinition skill) { skills.put(skill.id(), skill); }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (!skills.containsKey(skillId)) return SkillResult.unavailable(skillId);

        // On server, all health skills return phone_only error.
        // The phone node overrides this with real HealthKit/Health Connect data.
        return SkillResult.error(I18n.get("skill.health.phone_only"),
            0, SkillTier.NATIVE, skillId);
    }

    @Override public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }
    @Override public boolean supports(String skillId) { return skills.containsKey(skillId); }
    @Override public SkillTier tier() { return SkillTier.NATIVE; }
}

package org.wyrdsekai.core.skill;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.mcp.McpResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes native MCP skills via the existing McpGatewayService.
 * Wraps the MCP gateway as a SkillExecutor — bridges the skill system
 * to the existing MCP infrastructure.
 */
public class NativeMcpExecutor implements SkillExecutor {

    private final McpGatewayService gateway;
    private final Map<String, SkillDefinition> registeredSkills = new ConcurrentHashMap<>();

    public NativeMcpExecutor(McpGatewayService gateway) {
        this.gateway = gateway;
    }

    /** Register a native skill definition. */
    public void registerSkill(SkillDefinition skill) {
        if (skill.tier() != SkillTier.NATIVE) {
            throw new IllegalArgumentException(I18n.get("skill.native.only_native"));
        }
        registeredSkills.put(skill.id(), skill);
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        SkillDefinition skill = registeredSkills.get(skillId);
        if (skill == null) {
            return SkillResult.unavailable(skillId);
        }

        // Map skill ID to MCP service + tool
        // Convention: skill ID "hearth.ha.service" → MCP service "hearth-ha", tool "service"
        String[] parts = skillId.split("\\.", 3);
        String serviceId = parts.length >= 2 ? parts[0] + "-" + parts[1] : parts[0];
        String toolName = parts.length >= 3 ? parts[2] : parts[parts.length - 1];

        long start = System.currentTimeMillis();

        try {
            McpResult result = gateway.execute(context.agentDid(), context.roomId(),
                serviceId, toolName, params);
            long elapsed = System.currentTimeMillis() - start;

            if (result.success()) {
                return SkillResult.ok(result.data(), Map.of("raw", result.data()),
                    elapsed, SkillTier.NATIVE, skillId,
                    result.cost() != null ? result.cost() : 0);
            } else {
                return SkillResult.error(result.error() != null ? result.error() : I18n.get("skill.native.mcp_failed"),
                    elapsed, SkillTier.NATIVE, skillId);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.native.error", e.getMessage()),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    @Override
    public List<SkillDefinition> availableSkills() {
        return new ArrayList<>(registeredSkills.values());
    }

    @Override
    public boolean supports(String skillId) {
        return registeredSkills.containsKey(skillId);
    }

    @Override
    public SkillTier tier() {
        return SkillTier.NATIVE;
    }
}

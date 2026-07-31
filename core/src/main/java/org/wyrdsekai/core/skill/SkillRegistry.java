package org.wyrdsekai.core.skill;

import org.wyrdsekai.core.library.OutputSanitizer;
import org.wyrdsekai.core.safety.McpAuditLog;

import org.wyrdsekai.common.i18n.I18n;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Aggregates all skill executors. Resolves skill IDs to the best available
 * executor (tier precedence: Native > CLI > OpenClaw). Runs the security
 * pipeline on every invocation.
 */
public class SkillRegistry {

    private final List<SkillExecutor> executors = new ArrayList<>();
    private final Map<String, SkillPermission> agentPermissions = new ConcurrentHashMap<>();
    private final OutputSanitizer sanitizer;
    private final McpAuditLog auditLog;

    public SkillRegistry(OutputSanitizer sanitizer, McpAuditLog auditLog) {
        this.sanitizer = sanitizer;
        this.auditLog = auditLog;
    }

    /** Register an executor. Executors are tried in registration order (register natives first). */
    public void registerExecutor(SkillExecutor executor) {
        executors.add(executor);
    }

    /** Execute a skill through the full security pipeline. */
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        // 1. Find executor
        SkillExecutor executor = resolveExecutor(skillId);
        if (executor == null) {
            return SkillResult.unavailable(skillId);
        }

        // 2. Permission check (Ward Room)
        if (!isAllowed(context.agentDid(), skillId)) {
            audit(context.agentDid(), skillId, params, McpAuditLog.CallResult.BUDGET_EXCEEDED, 0, null);
            return SkillResult.denied(I18n.get("skill.denied.permission", skillId), skillId);
        }

        // 3. Budget check (Counting House)
        if (context.budgetRemaining() <= 0) {
            audit(context.agentDid(), skillId, params, McpAuditLog.CallResult.BUDGET_EXCEEDED, 0, null);
            return SkillResult.denied(I18n.get("skill.denied.budget"), skillId);
        }

        // 4. Execute
        long start = System.currentTimeMillis();
        SkillResult raw;
        try {
            raw = executor.execute(skillId, params, context);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            audit(context.agentDid(), skillId, params, McpAuditLog.CallResult.FAILURE, elapsed, context.nodeId());
            return SkillResult.error(I18n.get("skill.error.execution", e.getMessage()),
                elapsed, executor.tier(), skillId);
        }

        // 5. Sanitize output
        if (raw.success() && raw.output() != null && sanitizer != null) {
            var sanitized = sanitizer.sanitize(skillId, raw.output());
            if (!sanitized.clean()) {
                raw = new SkillResult(raw.success(), sanitized.sanitizedResponse(),
                    raw.data(), raw.durationMs(), raw.executorTier(), raw.skillId(), raw.cost());
            }
        }

        // 6. Audit
        long elapsed = System.currentTimeMillis() - start;
        audit(context.agentDid(), skillId, params,
            raw.success() ? McpAuditLog.CallResult.SUCCESS : McpAuditLog.CallResult.FAILURE,
            elapsed, context.nodeId());

        return raw;
    }

    /** List all available skills across all executors. */
    public List<SkillDefinition> allSkills() {
        return executors.stream()
            .flatMap(e -> e.availableSkills().stream())
            .toList();
    }

    /** List skills available for a specific room. */
    public List<SkillDefinition> skillsForRoom(String roomId) {
        return allSkills().stream()
            .filter(s -> roomId.equals(s.room()))
            .toList();
    }

    /** List skills an agent is allowed to use. */
    public List<SkillDefinition> skillsForAgent(String agentDid) {
        return allSkills().stream()
            .filter(s -> isAllowed(agentDid, s.id()))
            .toList();
    }

    /** Set skill permissions for an agent. */
    public void setPermissions(String agentDid, SkillPermission permission) {
        agentPermissions.put(agentDid, permission);
    }

    /** Get permission config for an agent. */
    public SkillPermission getPermissions(String agentDid) {
        return agentPermissions.get(agentDid);
    }

    /** Check if an agent is allowed to use a skill. */
    public boolean isAllowed(String agentDid, String skillId) {
        SkillPermission perm = agentPermissions.get(agentDid);
        if (perm == null) return false; // Default deny
        return perm.isAllowed(skillId);
    }

    /** Check if any executor supports a given skill. */
    public boolean hasSkill(String skillId) {
        return resolveExecutor(skillId) != null;
    }

    /** Resolve the best executor for a skill (tier precedence). */
    SkillExecutor resolveExecutor(String skillId) {
        // Executors registered in tier precedence order (native first)
        for (SkillExecutor executor : executors) {
            if (executor.supports(skillId)) {
                return executor;
            }
        }
        return null;
    }

    /** Audit a skill invocation (null-safe for testing without database). */
    private void audit(String agentDid, String skillId, Map<String, Object> params,
                       McpAuditLog.CallResult result, long latencyMs, String zoneId) {
        if (auditLog != null) {
            auditLog.logCall(agentDid, "skill", skillId, redactParams(params),
                result, latencyMs, 0, zoneId);
        }
    }

    /** Redact sensitive parameter values for audit logging. */
    private Map<String, Object> redactParams(Map<String, Object> params) {
        if (params == null) return Map.of();
        var redacted = new HashMap<String, Object>();
        for (var entry : params.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if (key.contains("password") || key.contains("secret") || key.contains("token")
                || key.contains("key") || key.contains("credential")) {
                redacted.put(entry.getKey(), "[REDACTED]");
            } else {
                redacted.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return redacted;
    }
}

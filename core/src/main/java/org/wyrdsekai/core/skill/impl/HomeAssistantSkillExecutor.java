package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Home Assistant skills via REST API.
 * Manages smart home entities: state queries, service calls, automation triggers, history.
 * Requires Bearer token from The Safe (key: "ha_token").
 */
public class HomeAssistantSkillExecutor extends HttpSkillExecutor {

    private static final String DEFAULT_BASE_URL = "http://homeassistant.local:8123";

    public HomeAssistantSkillExecutor() {
        this(DEFAULT_BASE_URL);
    }

    public HomeAssistantSkillExecutor(String baseUrl) {
        super(baseUrl);
        var auth = SkillAuth.apiKey("ha_token");

        define(new SkillDefinition("hearth.ha.state",
            "HA Entity State", "Get the current state of a Home Assistant entity",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("entity_id", "string", "Entity ID (e.g. light.living_room)")),
            auth, SkillLocality.LOCAL, true));

        define(new SkillDefinition("hearth.ha.service",
            "HA Service Call", "Call a Home Assistant service",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(
                SkillParam.required("domain", "string", "Service domain (e.g. light)"),
                SkillParam.required("service", "string", "Service name (e.g. turn_on)"),
                SkillParam.required("entity_id", "string", "Target entity ID")),
            auth, SkillLocality.LOCAL, true));

        define(new SkillDefinition("hearth.ha.entities",
            "HA Entity List", "List all Home Assistant entities",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(),
            auth, SkillLocality.LOCAL, true));

        define(new SkillDefinition("hearth.ha.automation",
            "HA Trigger Automation", "Trigger a Home Assistant automation",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("automation_id", "string", "Automation entity ID")),
            auth, SkillLocality.LOCAL, true));

        define(new SkillDefinition("hearth.ha.history",
            "HA Entity History", "Get state history for an entity",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(
                SkillParam.required("entity_id", "string", "Entity ID"),
                SkillParam.optional("period", "string", "Time period (e.g. 1h, 24h, 7d)")),
            auth, SkillLocality.LOCAL, true));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return notConfigured(skillId, "ha_url");
        }
        String token = context.credentials().get("ha_token");
        if (token == null || token.isBlank()) {
            return notConfigured(skillId, "ha_token");
        }

        Map<String, String> headers = Map.of(
            "Authorization", "Bearer " + token,
            "Content-Type", "application/json");

        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "hearth.ha.state" -> executeState(params, headers, start, skillId, context);
            case "hearth.ha.service" -> executeService(params, headers, start, skillId, context);
            case "hearth.ha.entities" -> executeEntities(headers, start, skillId, context);
            case "hearth.ha.automation" -> executeAutomation(params, headers, start, skillId, context);
            case "hearth.ha.history" -> executeHistory(params, headers, start, skillId, context);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeState(Map<String, Object> params, Map<String, String> headers,
                                      long start, String skillId, SkillContext context) {
        String entityId = requireParam(params, "entity_id");
        if (entityId == null) {
            return SkillResult.error(I18n.get("skill.param_required", "entity_id"),
                0, SkillTier.NATIVE, skillId);
        }

        String url = baseUrl + "/api/states/" + sanitizeEntityId(entityId);
        var result = httpGet(url, headers, context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        String state = jsonString(result.body(), "state");
        String output = I18n.get("skill.ha.state", entityId, state != null ? state : "unknown");

        return SkillResult.ok(output, Map.of("entity_id", entityId, "state", state != null ? state : "unknown",
            "raw", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeService(Map<String, Object> params, Map<String, String> headers,
                                        long start, String skillId, SkillContext context) {
        String domain = requireParam(params, "domain");
        String service = requireParam(params, "service");
        String entityId = requireParam(params, "entity_id");
        if (domain == null || service == null || entityId == null) {
            return SkillResult.error(I18n.get("skill.param_required", "domain, service, entity_id"),
                0, SkillTier.NATIVE, skillId);
        }

        String url = baseUrl + "/api/services/" + sanitizeEntityId(domain) + "/" + sanitizeEntityId(service);
        String body = "{\"entity_id\": \"" + sanitizeEntityId(entityId) + "\"}";
        var result = httpPost(url, body, headers, context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        String output = I18n.get("skill.ha.service_called", domain, service, entityId);
        return SkillResult.ok(output, Map.of("domain", domain, "service", service, "entity_id", entityId),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeEntities(Map<String, String> headers, long start,
                                         String skillId, SkillContext context) {
        String url = baseUrl + "/api/states";
        var result = httpGet(url, headers, context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        // Count entities roughly by counting "entity_id" occurrences
        String body = result.body();
        int count = 0;
        int idx = 0;
        while ((idx = body.indexOf("\"entity_id\"", idx)) >= 0) {
            count++;
            idx += 11;
        }

        String output = I18n.get("skill.ha.entities_found", count);
        return SkillResult.ok(output, Map.of("count", count, "raw", body),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeAutomation(Map<String, Object> params, Map<String, String> headers,
                                           long start, String skillId, SkillContext context) {
        String automationId = requireParam(params, "automation_id");
        if (automationId == null) {
            return SkillResult.error(I18n.get("skill.param_required", "automation_id"),
                0, SkillTier.NATIVE, skillId);
        }

        String url = baseUrl + "/api/services/automation/trigger";
        String body = "{\"entity_id\": \"" + sanitizeEntityId(automationId) + "\"}";
        var result = httpPost(url, body, headers, context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        String output = I18n.get("skill.ha.service_called", "automation", "trigger", automationId);
        return SkillResult.ok(output, Map.of("automation_id", automationId),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeHistory(Map<String, Object> params, Map<String, String> headers,
                                        long start, String skillId, SkillContext context) {
        String entityId = requireParam(params, "entity_id");
        if (entityId == null) {
            return SkillResult.error(I18n.get("skill.param_required", "entity_id"),
                0, SkillTier.NATIVE, skillId);
        }

        String period = param(params, "period", "24h");
        String url = baseUrl + "/api/history/period?filter_entity_id="
            + URLEncoder.encode(sanitizeEntityId(entityId), StandardCharsets.UTF_8);

        var result = httpGet(url, headers, context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(), Map.of("entity_id", entityId, "period", period, "raw", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    /** Sanitize entity IDs — allow only alphanumeric, dots, underscores. */
    private static String sanitizeEntityId(String id) {
        return id.replaceAll("[^a-zA-Z0-9._]", "");
    }
}

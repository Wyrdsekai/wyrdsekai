package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.util.List;
import java.util.Map;

/**
 * Google Chat API skill executor.
 * Provides space listing and message sending via Google Chat REST API.
 * Requires OAuth credentials for Google in The Safe.
 */
public class GoogleChatSkillExecutor extends HttpSkillExecutor {

    private static final String BASE = "https://chat.googleapis.com/v1";
    private static final SkillAuth AUTH = SkillAuth.oauth("google_oauth");

    public GoogleChatSkillExecutor() {
        super(BASE);

        define(SkillDefinition.native_("herald.gchat.send", "Google Chat Send",
            "Send a message to a Google Chat space", "herald",
            List.of(SkillParam.required("space", "string", "Space name (e.g., spaces/AAAA)"),
                     SkillParam.required("text", "string", "Message text")),
            AUTH));

        define(SkillDefinition.native_("herald.gchat.spaces", "Google Chat Spaces",
            "List Google Chat spaces", "herald",
            List.of(SkillParam.optional("pageSize", "number", "Max spaces to return"),
                     SkillParam.optional("filter", "string", "Filter expression")),
            AUTH));

        define(SkillDefinition.native_("herald.gchat.messages", "Google Chat Messages",
            "List messages in a Google Chat space", "herald",
            List.of(SkillParam.required("space", "string", "Space name (e.g., spaces/AAAA)"),
                     SkillParam.optional("pageSize", "number", "Max messages to return")),
            AUTH));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String token = context.credentials().get("google_oauth");
        if (token == null) return notConfigured(skillId, "Google OAuth token");

        var headers = Map.of("Authorization", "Bearer " + token);
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "herald.gchat.send" -> executeSend(params, headers, start, skillId);
            case "herald.gchat.spaces" -> executeSpaces(params, headers, start, skillId);
            case "herald.gchat.messages" -> executeMessages(params, headers, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeSend(Map<String, Object> params, Map<String, String> headers,
                                     long start, String skillId) {
        String space = requireParam(params, "space");
        String text = requireParam(params, "text");
        if (space == null || text == null)
            return SkillResult.error(I18n.get("skill.param_required", "space, text"),
                0, SkillTier.NATIVE, skillId);

        String json = "{\"text\":\"" + escJson(text) + "\"}";
        String url = baseUrl + "/" + space + "/messages";

        var result = httpPost(url, json, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.gchat.sent", space),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeSpaces(Map<String, Object> params, Map<String, String> headers,
                                       long start, String skillId) {
        int pageSize = intParam(params, "pageSize", 20);
        String url = baseUrl + "/spaces?pageSize=" + pageSize;

        var result = httpGet(url, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        int count = countOccurrences(result.body(), "\"name\"");
        return SkillResult.ok(I18n.get("skill.gchat.spaces", count),
            Map.of("body", result.body(), "count", count), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeMessages(Map<String, Object> params, Map<String, String> headers,
                                          long start, String skillId) {
        String space = requireParam(params, "space");
        if (space == null) return SkillResult.error(I18n.get("skill.param_required", "space"),
            0, SkillTier.NATIVE, skillId);

        int pageSize = intParam(params, "pageSize", 25);
        String url = baseUrl + "/" + space + "/messages?pageSize=" + pageSize;

        var result = httpGet(url, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private String escJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private int countOccurrences(String text, String search) {
        if (text == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) >= 0) { count++; idx += search.length(); }
        return count;
    }
}

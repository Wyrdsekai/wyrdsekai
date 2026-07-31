package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Google Gmail API skill executor.
 * Provides inbox, read, draft, send, label, and search via Gmail REST v1.
 * Requires OAuth credentials for Google in The Safe.
 */
public class GmailSkillExecutor extends HttpSkillExecutor {

    private static final String BASE = "https://www.googleapis.com/gmail/v1/users/me";
    private static final SkillAuth AUTH = SkillAuth.oauth("google_oauth");

    public GmailSkillExecutor() {
        super(BASE);

        define(SkillDefinition.native_("herald.gmail.inbox", "Gmail Inbox",
            "List recent messages from Gmail inbox", "herald",
            List.of(SkillParam.optional("maxResults", "number", "Max messages to return"),
                     SkillParam.optional("labelIds", "string", "Comma-separated label IDs")),
            AUTH));

        define(SkillDefinition.native_("herald.gmail.read", "Gmail Read",
            "Read a specific Gmail message", "herald",
            List.of(SkillParam.required("messageId", "string", "Message ID to read")),
            AUTH));

        define(SkillDefinition.native_("herald.gmail.draft", "Gmail Draft",
            "Create a draft email in Gmail", "herald",
            List.of(SkillParam.required("to", "string", "Recipient email address"),
                     SkillParam.required("subject", "string", "Email subject"),
                     SkillParam.required("body", "string", "Email body text")),
            AUTH));

        define(SkillDefinition.native_("herald.gmail.send", "Gmail Send",
            "Send an email via Gmail", "herald",
            List.of(SkillParam.required("to", "string", "Recipient email address"),
                     SkillParam.required("subject", "string", "Email subject"),
                     SkillParam.required("body", "string", "Email body text")),
            AUTH));

        define(SkillDefinition.native_("herald.gmail.label", "Gmail Label",
            "Add or remove labels on a message", "herald",
            List.of(SkillParam.required("messageId", "string", "Message ID"),
                     SkillParam.optional("addLabels", "string", "Comma-separated labels to add"),
                     SkillParam.optional("removeLabels", "string", "Comma-separated labels to remove")),
            AUTH));

        define(SkillDefinition.native_("herald.gmail.search", "Gmail Search",
            "Search Gmail messages with a query", "herald",
            List.of(SkillParam.required("query", "string", "Gmail search query"),
                     SkillParam.optional("maxResults", "number", "Max results")),
            AUTH));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String token = context.credentials().get("google_oauth");
        if (token == null) return notConfigured(skillId, "Google OAuth token");

        var headers = Map.of("Authorization", "Bearer " + token);
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "herald.gmail.inbox" -> executeInbox(params, headers, start, skillId);
            case "herald.gmail.read" -> executeRead(params, headers, start, skillId);
            case "herald.gmail.draft" -> executeDraft(params, headers, start, skillId);
            case "herald.gmail.send" -> executeSend(params, headers, start, skillId);
            case "herald.gmail.label" -> executeLabel(params, headers, start, skillId);
            case "herald.gmail.search" -> executeSearch(params, headers, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeInbox(Map<String, Object> params, Map<String, String> headers,
                                      long start, String skillId) {
        int max = intParam(params, "maxResults", 10);
        String labels = param(params, "labelIds", "INBOX");
        String url = baseUrl + "/messages?maxResults=" + max + "&labelIds=" + labels;

        var result = httpGet(url, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        int count = countJsonArray(result.body(), "messages");
        return SkillResult.ok(I18n.get("skill.gmail.inbox", count),
            Map.of("body", result.body(), "count", count), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeRead(Map<String, Object> params, Map<String, String> headers,
                                     long start, String skillId) {
        String messageId = requireParam(params, "messageId");
        if (messageId == null) return SkillResult.error(I18n.get("skill.param_required", "messageId"),
            0, SkillTier.NATIVE, skillId);

        String url = baseUrl + "/messages/" + messageId + "?format=full";
        var result = httpGet(url, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        String snippet = jsonString(result.body(), "snippet");
        return SkillResult.ok(snippet != null ? snippet : result.body(),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeDraft(Map<String, Object> params, Map<String, String> headers,
                                      long start, String skillId) {
        String to = requireParam(params, "to");
        String subject = requireParam(params, "subject");
        String body = requireParam(params, "body");
        if (to == null || subject == null || body == null)
            return SkillResult.error(I18n.get("skill.param_required", "to, subject, body"),
                0, SkillTier.NATIVE, skillId);

        String raw = encodeMessage(to, subject, body);
        String json = "{\"message\":{\"raw\":\"" + raw + "\"}}";
        var result = httpPost(baseUrl + "/drafts", json, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.email.draft_saved", subject),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeSend(Map<String, Object> params, Map<String, String> headers,
                                     long start, String skillId) {
        String to = requireParam(params, "to");
        String subject = requireParam(params, "subject");
        String body = requireParam(params, "body");
        if (to == null || subject == null || body == null)
            return SkillResult.error(I18n.get("skill.param_required", "to, subject, body"),
                0, SkillTier.NATIVE, skillId);

        String raw = encodeMessage(to, subject, body);
        String json = "{\"raw\":\"" + raw + "\"}";
        var result = httpPost(baseUrl + "/messages/send", json, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.gmail.sent"),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeLabel(Map<String, Object> params, Map<String, String> headers,
                                      long start, String skillId) {
        String messageId = requireParam(params, "messageId");
        if (messageId == null) return SkillResult.error(I18n.get("skill.param_required", "messageId"),
            0, SkillTier.NATIVE, skillId);

        String addLabels = param(params, "addLabels", "");
        String removeLabels = param(params, "removeLabels", "");

        StringBuilder json = new StringBuilder("{");
        if (!addLabels.isEmpty()) {
            json.append("\"addLabelIds\":[");
            appendJsonArray(json, addLabels.split(","));
            json.append("]");
        }
        if (!removeLabels.isEmpty()) {
            if (!addLabels.isEmpty()) json.append(",");
            json.append("\"removeLabelIds\":[");
            appendJsonArray(json, removeLabels.split(","));
            json.append("]");
        }
        json.append("}");

        var result = httpPost(baseUrl + "/messages/" + messageId + "/modify",
            json.toString(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.gmail.labeled", messageId),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeSearch(Map<String, Object> params, Map<String, String> headers,
                                       long start, String skillId) {
        String query = requireParam(params, "query");
        if (query == null) return SkillResult.error(I18n.get("skill.param_required", "query"),
            0, SkillTier.NATIVE, skillId);
        int max = intParam(params, "maxResults", 10);

        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/messages?q=" + encoded + "&maxResults=" + max;
        var result = httpGet(url, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        int count = countJsonArray(result.body(), "messages");
        return SkillResult.ok(I18n.get("skill.gmail.inbox", count),
            Map.of("body", result.body(), "count", count), elapsed, SkillTier.NATIVE, skillId);
    }

    private String encodeMessage(String to, String subject, String body) {
        String mime = "To: " + to + "\r\nSubject: " + subject +
            "\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n" + body;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mime.getBytes(StandardCharsets.UTF_8));
    }

    private int countJsonArray(String json, String arrayKey) {
        if (json == null) return 0;
        String pattern = "\"" + arrayKey + "\"\\s*:\\s*\\[";
        if (!json.matches("(?s).*" + pattern + ".*")) return 0;
        int start = json.indexOf("\"" + arrayKey + "\"");
        if (start < 0) return 0;
        int bracket = json.indexOf('[', start);
        if (bracket < 0) return 0;
        // Count commas between matching brackets as rough estimate
        int depth = 0;
        int count = 0;
        for (int i = bracket; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) break; }
            else if (c == '{' && depth == 1) count++;
        }
        return Math.max(count, 0);
    }

    private void appendJsonArray(StringBuilder sb, String[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(values[i].trim()).append("\"");
        }
    }
}

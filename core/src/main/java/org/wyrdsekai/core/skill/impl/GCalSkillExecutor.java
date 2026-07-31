package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Google Calendar API skill executor.
 * Provides event listing, creation, free/busy lookup, and event updates.
 * Requires OAuth credentials for Google in The Safe.
 */
public class GCalSkillExecutor extends HttpSkillExecutor {

    private static final String BASE = "https://www.googleapis.com/calendar/v3";
    private static final SkillAuth AUTH = SkillAuth.oauth("google_oauth");

    public GCalSkillExecutor() {
        super(BASE);

        define(SkillDefinition.native_("scriptorium.gcal.events", "Google Calendar Events",
            "List events from Google Calendar", "scriptorium",
            List.of(SkillParam.optional("calendarId", "string", "Calendar ID (default: primary)"),
                     SkillParam.optional("timeMin", "string", "Start time (RFC3339)"),
                     SkillParam.optional("timeMax", "string", "End time (RFC3339)"),
                     SkillParam.optional("maxResults", "number", "Max events to return")),
            AUTH));

        define(SkillDefinition.native_("scriptorium.gcal.create", "Google Calendar Create",
            "Create a new calendar event", "scriptorium",
            List.of(SkillParam.required("summary", "string", "Event title"),
                     SkillParam.required("start", "string", "Start time (RFC3339)"),
                     SkillParam.required("end", "string", "End time (RFC3339)"),
                     SkillParam.optional("calendarId", "string", "Calendar ID (default: primary)"),
                     SkillParam.optional("description", "string", "Event description"),
                     SkillParam.optional("location", "string", "Event location")),
            AUTH));

        define(SkillDefinition.native_("scriptorium.gcal.freebusy", "Google Calendar Free/Busy",
            "Check free/busy status for a time range", "scriptorium",
            List.of(SkillParam.required("timeMin", "string", "Start time (RFC3339)"),
                     SkillParam.required("timeMax", "string", "End time (RFC3339)"),
                     SkillParam.optional("calendarId", "string", "Calendar ID (default: primary)")),
            AUTH));

        define(SkillDefinition.native_("scriptorium.gcal.update", "Google Calendar Update",
            "Update an existing calendar event", "scriptorium",
            List.of(SkillParam.required("eventId", "string", "Event ID to update"),
                     SkillParam.optional("calendarId", "string", "Calendar ID (default: primary)"),
                     SkillParam.optional("summary", "string", "New event title"),
                     SkillParam.optional("start", "string", "New start time (RFC3339)"),
                     SkillParam.optional("end", "string", "New end time (RFC3339)"),
                     SkillParam.optional("description", "string", "New description")),
            AUTH));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String token = context.credentials().get("google_oauth");
        if (token == null) return notConfigured(skillId, "Google OAuth token");

        var headers = Map.of("Authorization", "Bearer " + token);
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "scriptorium.gcal.events" -> executeEvents(params, headers, start, skillId);
            case "scriptorium.gcal.create" -> executeCreate(params, headers, start, skillId);
            case "scriptorium.gcal.freebusy" -> executeFreeBusy(params, headers, start, skillId);
            case "scriptorium.gcal.update" -> executeUpdate(params, headers, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeEvents(Map<String, Object> params, Map<String, String> headers,
                                       long start, String skillId) {
        String calId = param(params, "calendarId", "primary");
        int max = intParam(params, "maxResults", 10);
        StringBuilder url = new StringBuilder(baseUrl)
            .append("/calendars/").append(enc(calId))
            .append("/events?maxResults=").append(max)
            .append("&singleEvents=true&orderBy=startTime");

        String timeMin = param(params, "timeMin", null);
        String timeMax = param(params, "timeMax", null);
        if (timeMin != null) url.append("&timeMin=").append(enc(timeMin));
        if (timeMax != null) url.append("&timeMax=").append(enc(timeMax));

        var result = httpGet(url.toString(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        int count = countItems(result.body());
        return SkillResult.ok(I18n.get("skill.gcal.events", count),
            Map.of("body", result.body(), "count", count), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeCreate(Map<String, Object> params, Map<String, String> headers,
                                       long start, String skillId) {
        String summary = requireParam(params, "summary");
        String startTime = requireParam(params, "start");
        String endTime = requireParam(params, "end");
        if (summary == null || startTime == null || endTime == null)
            return SkillResult.error(I18n.get("skill.param_required", "summary, start, end"),
                0, SkillTier.NATIVE, skillId);

        String calId = param(params, "calendarId", "primary");
        String desc = param(params, "description", "");
        String location = param(params, "location", "");

        String json = "{\"summary\":\"" + escJson(summary) + "\"," +
            "\"description\":\"" + escJson(desc) + "\"," +
            "\"location\":\"" + escJson(location) + "\"," +
            "\"start\":{\"dateTime\":\"" + startTime + "\"}," +
            "\"end\":{\"dateTime\":\"" + endTime + "\"}}";

        String url = baseUrl + "/calendars/" + enc(calId) + "/events";
        var result = httpPost(url, json, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.gcal.created", summary),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeFreeBusy(Map<String, Object> params, Map<String, String> headers,
                                         long start, String skillId) {
        String timeMin = requireParam(params, "timeMin");
        String timeMax = requireParam(params, "timeMax");
        if (timeMin == null || timeMax == null)
            return SkillResult.error(I18n.get("skill.param_required", "timeMin, timeMax"),
                0, SkillTier.NATIVE, skillId);

        String calId = param(params, "calendarId", "primary");
        String json = "{\"timeMin\":\"" + timeMin + "\",\"timeMax\":\"" + timeMax + "\"," +
            "\"items\":[{\"id\":\"" + escJson(calId) + "\"}]}";

        var result = httpPost(baseUrl + "/freeBusy", json, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeUpdate(Map<String, Object> params, Map<String, String> headers,
                                       long start, String skillId) {
        String eventId = requireParam(params, "eventId");
        if (eventId == null)
            return SkillResult.error(I18n.get("skill.param_required", "eventId"),
                0, SkillTier.NATIVE, skillId);

        String calId = param(params, "calendarId", "primary");

        // Build partial update JSON
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        String summary = param(params, "summary", null);
        if (summary != null) { json.append("\"summary\":\"").append(escJson(summary)).append("\""); first = false; }
        String startTime = param(params, "start", null);
        if (startTime != null) {
            if (!first) json.append(",");
            json.append("\"start\":{\"dateTime\":\"").append(startTime).append("\"}");
            first = false;
        }
        String endTime = param(params, "end", null);
        if (endTime != null) {
            if (!first) json.append(",");
            json.append("\"end\":{\"dateTime\":\"").append(endTime).append("\"}");
            first = false;
        }
        String desc = param(params, "description", null);
        if (desc != null) {
            if (!first) json.append(",");
            json.append("\"description\":\"").append(escJson(desc)).append("\"");
        }
        json.append("}");

        // Google Calendar uses PATCH for partial updates, but POST with method override works
        String url = baseUrl + "/calendars/" + enc(calId) + "/events/" + enc(eventId);
        var result = httpPost(url + "?_method=PATCH", json.toString(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.gcal.created", summary != null ? summary : eventId),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String escJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private int countItems(String json) {
        if (json == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = json.indexOf("\"summary\"", idx)) >= 0) { count++; idx++; }
        return count;
    }
}

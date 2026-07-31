package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Graph Calendar API skill executor.
 * Provides event listing and creation via Microsoft Graph REST API.
 * Requires OAuth credentials for Microsoft in The Safe.
 */
public class OutlookCalSkillExecutor extends HttpSkillExecutor {

    private static final String BASE = "https://graph.microsoft.com/v1.0/me";
    private static final SkillAuth AUTH = SkillAuth.oauth("microsoft_oauth");

    public OutlookCalSkillExecutor() {
        super(BASE);

        define(SkillDefinition.native_("scriptorium.outlook.events", "Outlook Calendar Events",
            "List events from Outlook Calendar", "scriptorium",
            List.of(SkillParam.optional("startDateTime", "string", "Start time (ISO 8601)"),
                     SkillParam.optional("endDateTime", "string", "End time (ISO 8601)"),
                     SkillParam.optional("top", "number", "Max events to return")),
            AUTH));

        define(SkillDefinition.native_("scriptorium.outlook.create", "Outlook Calendar Create",
            "Create a new calendar event in Outlook", "scriptorium",
            List.of(SkillParam.required("subject", "string", "Event subject"),
                     SkillParam.required("start", "string", "Start time (ISO 8601)"),
                     SkillParam.required("end", "string", "End time (ISO 8601)"),
                     SkillParam.optional("body", "string", "Event body text"),
                     SkillParam.optional("location", "string", "Event location"),
                     SkillParam.optional("timeZone", "string", "Time zone (default: UTC)")),
            AUTH));

        define(SkillDefinition.native_("scriptorium.outlook.freebusy", "Outlook Free/Busy",
            "Check free/busy schedule via Microsoft Graph", "scriptorium",
            List.of(SkillParam.required("startTime", "string", "Start time (ISO 8601)"),
                     SkillParam.required("endTime", "string", "End time (ISO 8601)"),
                     SkillParam.optional("timeZone", "string", "Time zone (default: UTC)")),
            AUTH));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String token = context.credentials().get("microsoft_oauth");
        if (token == null) return notConfigured(skillId, "Microsoft OAuth token");

        var headers = Map.of("Authorization", "Bearer " + token);
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "scriptorium.outlook.events" -> executeEvents(params, headers, start, skillId);
            case "scriptorium.outlook.create" -> executeCreate(params, headers, start, skillId);
            case "scriptorium.outlook.freebusy" -> executeFreeBusy(params, headers, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeEvents(Map<String, Object> params, Map<String, String> headers,
                                       long start, String skillId) {
        int top = intParam(params, "top", 10);
        StringBuilder url = new StringBuilder(baseUrl).append("/events?$top=").append(top)
            .append("&$orderby=start/dateTime");

        String startDt = param(params, "startDateTime", null);
        String endDt = param(params, "endDateTime", null);
        if (startDt != null && endDt != null) {
            url = new StringBuilder(baseUrl)
                .append("/calendarView?startDateTime=").append(enc(startDt))
                .append("&endDateTime=").append(enc(endDt))
                .append("&$top=").append(top);
        }

        var result = httpGet(url.toString(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        int count = countValues(result.body());
        return SkillResult.ok(I18n.get("skill.outlook.events", count),
            Map.of("body", result.body(), "count", count), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeCreate(Map<String, Object> params, Map<String, String> headers,
                                       long start, String skillId) {
        String subject = requireParam(params, "subject");
        String startTime = requireParam(params, "start");
        String endTime = requireParam(params, "end");
        if (subject == null || startTime == null || endTime == null)
            return SkillResult.error(I18n.get("skill.param_required", "subject, start, end"),
                0, SkillTier.NATIVE, skillId);

        String tz = param(params, "timeZone", "UTC");
        String bodyText = param(params, "body", "");
        String location = param(params, "location", "");

        String json = "{\"subject\":\"" + esc(subject) + "\"," +
            "\"body\":{\"contentType\":\"Text\",\"content\":\"" + esc(bodyText) + "\"}," +
            "\"start\":{\"dateTime\":\"" + startTime + "\",\"timeZone\":\"" + tz + "\"}," +
            "\"end\":{\"dateTime\":\"" + endTime + "\",\"timeZone\":\"" + tz + "\"}," +
            "\"location\":{\"displayName\":\"" + esc(location) + "\"}}";

        var result = httpPost(baseUrl + "/events", json, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.outlook.created", subject),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeFreeBusy(Map<String, Object> params, Map<String, String> headers,
                                         long start, String skillId) {
        String startTime = requireParam(params, "startTime");
        String endTime = requireParam(params, "endTime");
        if (startTime == null || endTime == null)
            return SkillResult.error(I18n.get("skill.param_required", "startTime, endTime"),
                0, SkillTier.NATIVE, skillId);

        String tz = param(params, "timeZone", "UTC");
        String json = "{\"schedules\":[\"me\"]," +
            "\"startTime\":{\"dateTime\":\"" + startTime + "\",\"timeZone\":\"" + tz + "\"}," +
            "\"endTime\":{\"dateTime\":\"" + endTime + "\",\"timeZone\":\"" + tz + "\"}," +
            "\"availabilityViewInterval\":30}";

        var result = httpPost(baseUrl + "/calendar/getSchedule", json, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String esc(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private int countValues(String json) {
        if (json == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = json.indexOf("\"subject\"", idx)) >= 0) { count++; idx++; }
        return count;
    }
}

package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CalDAV calendar skills via simplified HTTP PROPFIND/REPORT requests.
 * Supports event listing, creation, free/busy queries, and reminders.
 * Auth via Basic auth from The Safe (key: "caldav_credentials").
 */
public class CalDavSkillExecutor extends HttpSkillExecutor {

    private static final DateTimeFormatter ICAL_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public CalDavSkillExecutor(String baseUrl) {
        super(baseUrl);

        define(SkillDefinition.native_("scriptorium.calendar.events",
            "Calendar Events", "List calendar events within a date range",
            "scriptorium",
            List.of(
                SkillParam.optional("start", "string", "Start date (ISO-8601)"),
                SkillParam.optional("end", "string", "End date (ISO-8601)")),
            SkillAuth.apiKey("caldav_credentials")));

        define(SkillDefinition.native_("scriptorium.calendar.create",
            "Create Event", "Create a new calendar event",
            "scriptorium",
            List.of(
                SkillParam.required("title", "string", "Event title"),
                SkillParam.required("start", "string", "Start time (ISO-8601)"),
                SkillParam.required("end", "string", "End time (ISO-8601)")),
            SkillAuth.apiKey("caldav_credentials")));

        define(SkillDefinition.native_("scriptorium.calendar.freebusy",
            "Free/Busy Query", "Check free/busy status for a time range",
            "scriptorium",
            List.of(
                SkillParam.required("start", "string", "Start time (ISO-8601)"),
                SkillParam.required("end", "string", "End time (ISO-8601)")),
            SkillAuth.apiKey("caldav_credentials")));

        define(SkillDefinition.native_("scriptorium.calendar.reminders",
            "Calendar Reminders", "Get upcoming reminders and alarms",
            "scriptorium",
            List.of(),
            SkillAuth.apiKey("caldav_credentials")));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return notConfigured(skillId, "caldav_url");
        }
        String credentials = context.credentials().get("caldav_credentials");
        if (credentials == null || credentials.isBlank()) {
            return notConfigured(skillId, "caldav_credentials");
        }

        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        Map<String, String> headers = Map.of(
            "Authorization", "Basic " + encoded,
            "Depth", "1");

        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "scriptorium.calendar.events" -> executeEvents(params, headers, start, skillId, context);
            case "scriptorium.calendar.create" -> executeCreate(params, headers, start, skillId, context);
            case "scriptorium.calendar.freebusy" -> executeFreeBusy(params, headers, start, skillId, context);
            case "scriptorium.calendar.reminders" -> executeReminders(headers, start, skillId, context);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeEvents(Map<String, Object> params, Map<String, String> headers,
                                       long start, String skillId, SkillContext context) {
        String rangeStart = param(params, "start", ICAL_FMT.format(Instant.now()));
        String rangeEnd = param(params, "end", ICAL_FMT.format(Instant.now().plusSeconds(7 * 86400)));

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
            + "<d:prop><d:getetag/><c:calendar-data/></d:prop>"
            + "<c:filter><c:comp-filter name=\"VCALENDAR\">"
            + "<c:comp-filter name=\"VEVENT\">"
            + "<c:time-range start=\"" + rangeStart + "\" end=\"" + rangeEnd + "\"/>"
            + "</c:comp-filter></c:comp-filter></c:filter>"
            + "</c:calendar-query>";

        var result = caldavReport(baseUrl, xml, headers, context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        // Count VEVENT occurrences as rough event count
        int count = countOccurrences(result.body(), "VEVENT");
        String output = I18n.get("skill.caldav.events_found", count);

        return SkillResult.ok(output, Map.of("count", count, "raw", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeCreate(Map<String, Object> params, Map<String, String> headers,
                                       long start, String skillId, SkillContext context) {
        String title = requireParam(params, "title");
        String eventStart = requireParam(params, "start");
        String eventEnd = requireParam(params, "end");
        if (title == null || eventStart == null || eventEnd == null) {
            return SkillResult.error(I18n.get("skill.param_required", "title, start, end"),
                0, SkillTier.NATIVE, skillId);
        }

        String uid = UUID.randomUUID().toString();
        String ical = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//wyrdsekai//EN\r\n"
            + "BEGIN:VEVENT\r\nUID:" + uid + "\r\n"
            + "DTSTART:" + eventStart + "\r\nDTEND:" + eventEnd + "\r\n"
            + "SUMMARY:" + title + "\r\n"
            + "END:VEVENT\r\nEND:VCALENDAR";

        String url = baseUrl + "/" + uid + ".ics";
        var result = caldavPut(url, ical, headers, context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        String output = I18n.get("skill.caldav.event_created", title);
        return SkillResult.ok(output, Map.of("uid", uid, "title", title),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeFreeBusy(Map<String, Object> params, Map<String, String> headers,
                                         long start, String skillId, SkillContext context) {
        String fbStart = requireParam(params, "start");
        String fbEnd = requireParam(params, "end");
        if (fbStart == null || fbEnd == null) {
            return SkillResult.error(I18n.get("skill.param_required", "start, end"),
                0, SkillTier.NATIVE, skillId);
        }

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<c:free-busy-query xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
            + "<c:time-range start=\"" + fbStart + "\" end=\"" + fbEnd + "\"/>"
            + "</c:free-busy-query>";

        var result = caldavReport(baseUrl, xml, headers, context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(), Map.of("raw", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeReminders(Map<String, String> headers, long start,
                                          String skillId, SkillContext context) {
        // Query for events with VALARM components in the next 24 hours
        String now = ICAL_FMT.format(Instant.now());
        String tomorrow = ICAL_FMT.format(Instant.now().plusSeconds(86400));

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
            + "<d:prop><c:calendar-data/></d:prop>"
            + "<c:filter><c:comp-filter name=\"VCALENDAR\">"
            + "<c:comp-filter name=\"VEVENT\">"
            + "<c:time-range start=\"" + now + "\" end=\"" + tomorrow + "\"/>"
            + "</c:comp-filter></c:comp-filter></c:filter>"
            + "</c:calendar-query>";

        var result = caldavReport(baseUrl, xml, headers, context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(), Map.of("raw", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    /** Send a REPORT request (CalDAV query). */
    private HttpResult caldavReport(String url, String xmlBody,
                                     Map<String, String> extraHeaders, long timeoutMs) {
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("REPORT", HttpRequest.BodyPublishers.ofString(xmlBody))
                .header("Content-Type", "application/xml; charset=utf-8")
                .timeout(Duration.ofMillis(timeoutMs));
            extraHeaders.forEach(builder::header);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body(), null);
        } catch (Exception e) {
            return new HttpResult(0, null, e);
        }
    }

    /** Send a PUT request (CalDAV event creation). */
    private HttpResult caldavPut(String url, String icalBody,
                                  Map<String, String> extraHeaders, long timeoutMs) {
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofString(icalBody))
                .header("Content-Type", "text/calendar; charset=utf-8")
                .timeout(Duration.ofMillis(timeoutMs));
            extraHeaders.forEach(builder::header);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body(), null);
        } catch (Exception e) {
            return new HttpResult(0, null, e);
        }
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}

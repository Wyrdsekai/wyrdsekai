package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Calendar adapter.
 *
 * <p>Exposes {@code world.calendar.{list_events, create_event, update_event,
 * delete_event}}. Reads (list_events) are Tier 4; writes are Tier 5 because
 * sending invites is a real-world action.</p>
 *
 * <p>Credentials: {@code google.oauth_token} (OAuth bearer). Domain:
 * {@code www.googleapis.com}.</p>
 */
public final class GoogleCalendarAdapter extends AbstractHttpAdapter {

    private static final String BASE = "https://www.googleapis.com/calendar/v3";

    @Override public String namespace() { return "calendar"; }

    @Override public Set<String> capabilities() {
        return Set.of("list_events", "create_event", "update_event", "delete_event");
    }

    @Override public String credentialSlot() { return "google.oauth_token"; }

    @Override protected List<String> defaultDomains() {
        return List.of("www.googleapis.com");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var token = resolveCredential();
        if (token.isEmpty()) return missingCredentials();
        var headers = Map.of(
            "Authorization", "Bearer " + token.get(),
            "Accept", "application/json"
        );
        return switch (req.method()) {
            case "list_events" -> listEvents(req, headers);
            case "create_event" -> createEvent(req, headers);
            case "update_event" -> updateEvent(req, headers);
            case "delete_event" -> deleteEvent(req, headers);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse listEvents(AdapterRequest req, Map<String, String> headers) {
        var calendar = (String) req.args().getOrDefault("calendar", "primary");
        var url = BASE + "/calendars/" + calendar + "/events";
        var params = new LinkedHashMap<String, Object>();
        if (req.args().get("since") != null) params.put("timeMin", req.args().get("since"));
        if (req.args().get("until") != null) params.put("timeMax", req.args().get("until"));
        if (req.args().get("max") != null) params.put("maxResults", req.args().get("max"));
        return httpGetJson(url, headers, params);
    }

    private AdapterResponse createEvent(AdapterRequest req, Map<String, String> headers) {
        var calendar = (String) req.args().getOrDefault("calendar", "primary");
        var body = new LinkedHashMap<String, Object>();
        if (req.args().get("title") != null) body.put("summary", req.args().get("title"));
        if (req.args().get("description") != null) body.put("description", req.args().get("description"));
        if (req.args().get("location") != null) body.put("location", req.args().get("location"));
        if (req.args().get("start") != null) body.put("start", Map.of("dateTime", req.args().get("start")));
        if (req.args().get("end") != null) body.put("end", Map.of("dateTime", req.args().get("end")));
        if (req.args().get("attendees") != null) body.put("attendees", req.args().get("attendees"));
        var url = BASE + "/calendars/" + calendar + "/events";
        return httpPostJson(url, headers, body);
    }

    private AdapterResponse updateEvent(AdapterRequest req, Map<String, String> headers) {
        var eventId = requireString(req, "eventId");
        if (eventId == null) return AdapterResponse.fail("missing_arg", "eventId required", false);
        var calendar = (String) req.args().getOrDefault("calendar", "primary");
        var body = new LinkedHashMap<>(req.args());
        body.remove("eventId");
        body.remove("calendar");
        var url = BASE + "/calendars/" + calendar + "/events/" + eventId;
        return httpPatchJson(url, headers, body);
    }

    private AdapterResponse deleteEvent(AdapterRequest req, Map<String, String> headers) {
        var eventId = requireString(req, "eventId");
        if (eventId == null) return AdapterResponse.fail("missing_arg", "eventId required", false);
        var calendar = (String) req.args().getOrDefault("calendar", "primary");
        var url = BASE + "/calendars/" + calendar + "/events/" + eventId;
        return httpDelete(url, headers);
    }
}

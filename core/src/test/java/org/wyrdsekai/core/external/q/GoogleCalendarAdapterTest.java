package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class GoogleCalendarAdapterTest {

    private GoogleCalendarAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new GoogleCalendarAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
        wireCred("google.oauth_token", "test_token");
    }

    @AfterEach
    void tearDown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_and_capabilities() {
        assertEquals("calendar", adapter.namespace());
        assertTrue(adapter.capabilities().containsAll(
            List.of("list_events", "create_event", "update_event", "delete_event")));
    }

    @Test
    void missing_credentials_returns_credentials_missing() {
        wireNoCreds();
        var resp = adapter.invoke(req("calendar", "list_events", Map.of()));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void list_events_uses_primary_default_calendar() {
        adapter.invoke(req("calendar", "list_events", Map.of()));
        assertEquals("GET", rec.method.get());
        assertTrue(rec.url.get().contains("/calendars/primary/events"));
        assertEquals("Bearer test_token", rec.headers.get().get("Authorization"));
    }

    @Test
    void create_event_serialises_summary_and_dates() {
        adapter.invoke(req("calendar", "create_event", Map.of(
            "title", "Standup",
            "start", "2026-05-06T10:00:00Z",
            "end", "2026-05-06T10:15:00Z"
        )));
        assertEquals("POST", rec.method.get());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rec.body.get();
        assertEquals("Standup", body.get("summary"));
        assertNotNull(body.get("start"));
        assertNotNull(body.get("end"));
    }

    @Test
    void update_event_requires_eventId() {
        var resp = adapter.invoke(req("calendar", "update_event", Map.of()));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void delete_event_routes_DELETE() {
        adapter.invoke(req("calendar", "delete_event", Map.of("eventId", "abc123")));
        assertEquals("DELETE", rec.method.get());
        assertTrue(rec.url.get().endsWith("/events/abc123"));
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var resp = adapter.invoke(req("calendar", "destroy_world", Map.of()));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }
}

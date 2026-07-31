package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class TodoistAdapterTest {

    private TodoistAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new TodoistAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
        wireCred("todoist.api_token", "td_token");
    }

    @AfterEach
    void tearDown() { CredentialResolver.get().resetForTests(); }

    @Test
    void list_GET() {
        adapter.invoke(req("todoist", "list", Map.of("project", "P1")));
        assertEquals("GET", rec.method.get());
        assertTrue(rec.url.get().contains("project_id=P1"));
    }

    @Test
    void add_requires_content() {
        var resp = adapter.invoke(req("todoist", "add", Map.of()));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void add_with_due_string_serialised() {
        adapter.invoke(req("todoist", "add",
            Map.of("content", "buy milk", "due", "tomorrow", "priority", 3)));
        assertEquals("POST", rec.method.get());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rec.body.get();
        assertEquals("buy milk", body.get("content"));
        assertEquals("tomorrow", body.get("due_string"));
        assertEquals(3, body.get("priority"));
    }

    @Test
    void complete_requires_taskId() {
        var resp = adapter.invoke(req("todoist", "complete", Map.of()));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void complete_POSTs_close_endpoint() {
        adapter.invoke(req("todoist", "complete", Map.of("taskId", "T1")));
        assertEquals("POST", rec.method.get());
        assertTrue(rec.url.get().endsWith("/tasks/T1/close"));
    }

    @Test
    void no_creds() {
        wireNoCreds();
        assertEquals("credentials_missing",
            adapter.invoke(req("todoist", "list", Map.of())).error().code());
    }
}

package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class AsanaAdapterTest {

    private AsanaAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new AsanaAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
        wireCred("asana.token", "asana_pat");
    }

    @AfterEach
    void tearDown() { CredentialResolver.get().resetForTests(); }

    @Test
    void list_tasks_GET_with_filter() {
        adapter.invoke(req("asana", "list_tasks", Map.of("project", "P1", "assignee", "me")));
        assertEquals("GET", rec.method.get());
        var u = rec.url.get();
        assertTrue(u.contains("project=P1"));
        assertTrue(u.contains("assignee=me"));
    }

    @Test
    void create_task_requires_name() {
        var resp = adapter.invoke(req("asana", "create_task", Map.of()));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void create_task_wraps_payload_in_data() {
        adapter.invoke(req("asana", "create_task",
            Map.of("name", "Write spec", "project", "P1", "notes", "details")));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rec.body.get();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) body.get("data");
        assertEquals("Write spec", data.get("name"));
        assertEquals(List.of("P1"), data.get("projects"));
        assertEquals("details", data.get("notes"));
    }

    @Test
    void update_task_PATCHes_with_data_envelope() {
        adapter.invoke(req("asana", "update_task",
            Map.of("taskId", "T1", "completed", true)));
        assertEquals("PATCH", rec.method.get());
        assertTrue(rec.url.get().endsWith("/tasks/T1"));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rec.body.get();
        assertNotNull(body.get("data"));
    }

    @Test
    void no_creds() {
        wireNoCreds();
        var resp = adapter.invoke(req("asana", "list_tasks", Map.of()));
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void unknown_method() {
        assertEquals("unknown_method",
            adapter.invoke(req("asana", "delete_task", Map.of())).error().code());
    }
}

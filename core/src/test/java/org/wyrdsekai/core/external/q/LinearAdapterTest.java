package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class LinearAdapterTest {

    private LinearAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new LinearAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
        wireCred("linear.api_key", "lin_key");
    }

    @AfterEach
    void tearDown() { CredentialResolver.get().resetForTests(); }

    @Test
    void list_issues_posts_graphql() {
        adapter.invoke(req("linear", "list_issues", Map.of("max", 25)));
        assertEquals("POST", rec.method.get());
        assertTrue(rec.url.get().endsWith("/graphql"));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rec.body.get();
        assertNotNull(body.get("query"));
        @SuppressWarnings("unchecked")
        var vars = (Map<String, Object>) body.get("variables");
        assertEquals(25, vars.get("first"));
    }

    @Test
    void create_issue_requires_team_and_title() {
        var resp = adapter.invoke(req("linear", "create_issue", Map.of("team", "T1")));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void create_issue_serialises_input() {
        adapter.invoke(req("linear", "create_issue",
            Map.of("team", "T1", "title", "Bug", "body", "details", "priority", 2)));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rec.body.get();
        @SuppressWarnings("unchecked")
        var vars = (Map<String, Object>) body.get("variables");
        @SuppressWarnings("unchecked")
        var input = (Map<String, Object>) vars.get("input");
        assertEquals("T1", input.get("teamId"));
        assertEquals("Bug", input.get("title"));
        assertEquals("details", input.get("description"));
        assertEquals(2, input.get("priority"));
    }

    @Test
    void update_issue_requires_id() {
        var resp = adapter.invoke(req("linear", "update_issue", Map.of()));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void comment_requires_issue_and_body() {
        var resp = adapter.invoke(req("linear", "comment", Map.of("issueId", "I1")));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void comment_sends_create_mutation() {
        adapter.invoke(req("linear", "comment",
            Map.of("issueId", "I1", "body", "looks good")));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rec.body.get();
        assertTrue(body.get("query").toString().contains("commentCreate"));
    }

    @Test
    void unknown_method() {
        assertEquals("unknown_method",
            adapter.invoke(req("linear", "explode", Map.of())).error().code());
    }
}

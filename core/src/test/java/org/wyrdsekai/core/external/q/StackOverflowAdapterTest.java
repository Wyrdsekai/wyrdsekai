package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class StackOverflowAdapterTest {

    private StackOverflowAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new StackOverflowAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
    }

    @AfterEach
    void tearDown() { CredentialResolver.get().resetForTests(); }

    @Test
    void anonymous_search_works_without_creds() {
        wireNoCreds();
        adapter.invoke(req("stackoverflow", "search", Map.of("query", "java"))).success();
        var u = rec.url.get();
        assertTrue(u.contains("/search"));
        assertTrue(u.contains("intitle=java"));
        assertFalse(u.contains("key="), "no key without creds: " + u);
    }

    @Test
    void with_key_appends_key_param() {
        wireCred("stackexchange.api_key", "stack_k");
        adapter.invoke(req("stackoverflow", "search", Map.of("query", "kotlin")));
        assertTrue(rec.url.get().contains("key=stack_k"));
    }

    @Test
    void site_default_is_stackoverflow() {
        wireNoCreds();
        adapter.invoke(req("stackoverflow", "search", Map.of("query", "x")));
        assertTrue(rec.url.get().contains("site=stackoverflow"));
    }

    @Test
    void custom_site_used_when_provided() {
        wireNoCreds();
        adapter.invoke(req("stackoverflow", "search",
            Map.of("query", "x", "site", "serverfault")));
        assertTrue(rec.url.get().contains("site=serverfault"));
    }

    @Test
    void search_requires_query() {
        wireNoCreds();
        assertEquals("missing_arg",
            adapter.invoke(req("stackoverflow", "search", Map.of())).error().code());
    }

    @Test
    void top_answer_routes_to_questions_answers_endpoint() {
        wireNoCreds();
        adapter.invoke(req("stackoverflow", "top_answer", Map.of("questionId", "12345")));
        assertTrue(rec.url.get().contains("/questions/12345/answers"));
    }

    @Test
    void top_answer_requires_questionId() {
        wireNoCreds();
        assertEquals("missing_arg",
            adapter.invoke(req("stackoverflow", "top_answer", Map.of())).error().code());
    }

    @Test
    void unknown_method() {
        wireNoCreds();
        assertEquals("unknown_method",
            adapter.invoke(req("stackoverflow", "ask", Map.of())).error().code());
    }
}

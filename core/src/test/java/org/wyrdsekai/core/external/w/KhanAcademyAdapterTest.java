package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KhanAcademyAdapterTest {

    @Test
    void declares_khan_namespace() {
        var a = new KhanAcademyAdapter();
        assertEquals("khan", a.namespace());
        assertTrue(a.capabilities().contains("topic_search"));
        assertTrue(a.capabilities().contains("video_lookup"));
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new KhanAcademyAdapter();
        var resp = a.invoke(AdapterTestHarness.req("khan", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void topic_search_missing_query_is_missing_arg() {
        var a = new KhanAcademyAdapter();
        var resp = a.invoke(AdapterTestHarness.req("khan", "topic_search"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void topic_search_parses_hits() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"hits\":[{\"id\":\"x1\",\"title\":\"Algebra\",\"kind\":\"Topic\",\"url\":\"/x\"}]}")) {
            var a = new KhanAcademyAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("khan", "topic_search", "query", "algebra"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals("x1", list.get(0).get("contentId"));
            assertEquals("Algebra", list.get(0).get("title"));
        }
    }

    @Test
    void video_lookup_parses_response() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"id\":\"v1\",\"title\":\"Intro\",\"description\":\"d\",\"duration\":600,\"youtube_id\":\"y1\"}")) {
            var a = new KhanAcademyAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("khan", "video_lookup", "slug", "intro"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("v1", data.get("contentId"));
            assertEquals("Intro", data.get("title"));
            assertEquals("y1", data.get("youtubeId"));
        }
    }

    @Test
    void video_lookup_accepts_contentId_alias() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"id\":\"v2\",\"title\":\"x\",\"duration\":1}")) {
            var a = new KhanAcademyAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("khan", "video_lookup", "contentId", "v2"));
            assertTrue(resp.success());
        }
    }
}

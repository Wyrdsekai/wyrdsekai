package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MangaDexAdapterTest {

    @Test
    void declares_mangadex_namespace() {
        var a = new MangaDexAdapter();
        assertEquals("mangadex", a.namespace());
        assertTrue(a.capabilities().contains("search"));
        assertTrue(a.capabilities().contains("chapter_list"));
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new MangaDexAdapter();
        var resp = a.invoke(AdapterTestHarness.req("mangadex", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void search_missing_query_is_missing_arg() {
        var a = new MangaDexAdapter();
        var resp = a.invoke(AdapterTestHarness.req("mangadex", "search"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void search_parses_data() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"data\":[{\"id\":\"m1\",\"type\":\"manga\",\"attributes\":{\"title\":{\"en\":\"X\"},\"originalLanguage\":\"ja\",\"status\":\"ongoing\"}}]}")) {
            var a = new MangaDexAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("mangadex", "search", "query", "x"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals("m1", list.get(0).get("mangaId"));
            assertEquals("ongoing", list.get(0).get("status"));
        }
    }

    @Test
    void chapter_list_missing_mangaId_is_missing_arg() {
        var a = new MangaDexAdapter();
        var resp = a.invoke(AdapterTestHarness.req("mangadex", "chapter_list"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void chapter_list_parses_chapters() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"data\":[{\"id\":\"c1\",\"attributes\":{\"chapter\":\"1\",\"title\":\"Start\",\"translatedLanguage\":\"en\",\"publishAt\":\"2020-01-01\",\"pages\":20}}]}")) {
            var a = new MangaDexAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("mangadex", "chapter_list", "mangaId", "m1"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals("c1", list.get(0).get("chapterId"));
            assertEquals("1", list.get(0).get("chapter"));
        }
    }
}

package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoodreadsAdapterTest {

    @Test
    void declares_goodreads_namespace() {
        var a = new GoodreadsAdapter();
        assertEquals("goodreads", a.namespace());
        assertTrue(a.capabilities().contains("search"));
        assertTrue(a.capabilities().contains("book_info"));
    }

    @Test
    void search_returns_not_yet_wired() {
        var a = new GoodreadsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("goodreads", "search", "query", "x"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void book_info_returns_not_yet_wired() {
        var a = new GoodreadsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("goodreads", "book_info", "id", "x"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void list_reviews_returns_not_yet_wired() {
        var a = new GoodreadsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("goodreads", "list_reviews", "bookId", "x"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new GoodreadsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("goodreads", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void error_message_mentions_csv_migration() {
        var a = new GoodreadsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("goodreads", "search", "query", "x"));
        assertTrue(resp.error().message().toLowerCase().contains("import_csv")
            || resp.error().message().toLowerCase().contains("csv"));
    }
}

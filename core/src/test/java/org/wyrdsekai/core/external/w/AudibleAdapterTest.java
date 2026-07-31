package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudibleAdapterTest {

    @Test
    void declares_audible_namespace() {
        var a = new AudibleAdapter();
        assertEquals("audible", a.namespace());
        assertTrue(a.capabilities().contains("library_list"));
        assertTrue(a.capabilities().contains("listening_history"));
    }

    @Test
    void library_list_returns_not_yet_wired() {
        var a = new AudibleAdapter();
        var resp = a.invoke(AdapterTestHarness.req("audible", "library_list"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void listening_history_returns_not_yet_wired() {
        var a = new AudibleAdapter();
        var resp = a.invoke(AdapterTestHarness.req("audible", "listening_history"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new AudibleAdapter();
        var resp = a.invoke(AdapterTestHarness.req("audible", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void error_message_points_to_audiobookshelf() {
        var a = new AudibleAdapter();
        var resp = a.invoke(AdapterTestHarness.req("audible", "library_list"));
        assertTrue(resp.error().message().toLowerCase().contains("audiobookshelf"));
    }
}

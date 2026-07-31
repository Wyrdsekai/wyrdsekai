package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KoboBooksAdapterTest {

    @Test
    void declares_kobo_namespace() {
        var a = new KoboBooksAdapter();
        assertEquals("kobo", a.namespace());
        assertTrue(a.capabilities().contains("library_list"));
        assertTrue(a.capabilities().contains("recent_purchases"));
    }

    @Test
    void library_list_returns_not_yet_wired() {
        var a = new KoboBooksAdapter();
        var resp = a.invoke(AdapterTestHarness.req("kobo", "library_list"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void recent_purchases_returns_not_yet_wired() {
        var a = new KoboBooksAdapter();
        var resp = a.invoke(AdapterTestHarness.req("kobo", "recent_purchases"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new KoboBooksAdapter();
        var resp = a.invoke(AdapterTestHarness.req("kobo", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void error_message_mentions_calibre_path() {
        var a = new KoboBooksAdapter();
        var resp = a.invoke(AdapterTestHarness.req("kobo", "library_list"));
        assertTrue(resp.error().message().toLowerCase().contains("calibre"));
    }
}

package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DuolingoAdapterTest {

    @Test
    void declares_duolingo_namespace() {
        var a = new DuolingoAdapter();
        assertEquals("duolingo", a.namespace());
        assertTrue(a.capabilities().contains("user_progress"));
        assertTrue(a.capabilities().contains("list_courses"));
    }

    @Test
    void user_progress_returns_not_yet_wired() {
        var a = new DuolingoAdapter();
        var resp = a.invoke(AdapterTestHarness.req("duolingo", "user_progress"));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void list_courses_returns_not_yet_wired() {
        var a = new DuolingoAdapter();
        var resp = a.invoke(AdapterTestHarness.req("duolingo", "list_courses"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new DuolingoAdapter();
        var resp = a.invoke(AdapterTestHarness.req("duolingo", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void credential_slot_is_token() {
        assertEquals("duolingo.token", new DuolingoAdapter().credentialSlot());
    }
}

package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoursaAdapterTest {

    @Test
    void declares_coursa_namespace() {
        var a = new CoursaAdapter();
        assertEquals("coursa", a.namespace());
        assertTrue(a.capabilities().contains("course_search"));
        assertTrue(a.capabilities().contains("enroll"));
    }

    @Test
    void course_search_returns_not_yet_wired() {
        var a = new CoursaAdapter();
        var resp = a.invoke(AdapterTestHarness.req("coursa", "course_search", "query", "python"));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void enroll_returns_not_yet_wired() {
        var a = new CoursaAdapter();
        var resp = a.invoke(AdapterTestHarness.req("coursa", "enroll", "courseId", "x"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new CoursaAdapter();
        var resp = a.invoke(AdapterTestHarness.req("coursa", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void credential_slot() {
        assertEquals("coursa.api_key", new CoursaAdapter().credentialSlot());
    }
}

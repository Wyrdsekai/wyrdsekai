package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class WolframAdapterTest {

    private WolframAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new WolframAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
        wireCred("wolfram.app_id", "WOLF1");
    }

    @AfterEach
    void tearDown() { CredentialResolver.get().resetForTests(); }

    @Test
    void no_creds_means_credentials_missing() {
        wireNoCreds();
        var resp = adapter.invoke(req("wolfram", "query", Map.of("input", "2+2")));
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void query_serialises_appid_and_input() {
        adapter.invoke(req("wolfram", "query", Map.of("input", "integral of x^2")));
        var u = rec.url.get();
        assertTrue(u.contains("appid=WOLF1"));
        assertTrue(u.contains("input=integral"));
        assertTrue(u.contains("output=json"));
    }

    @Test
    void query_requires_input() {
        var resp = adapter.invoke(req("wolfram", "query", Map.of()));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void unknown_method() {
        assertEquals("unknown_method",
            adapter.invoke(req("wolfram", "explode", Map.of())).error().code());
    }

    @Test
    void namespace_and_capabilities() {
        assertEquals("wolfram", adapter.namespace());
        assertEquals(Set.of("query"), adapter.capabilities());
    }
}

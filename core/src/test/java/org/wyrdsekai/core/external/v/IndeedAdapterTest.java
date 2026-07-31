package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndeedAdapterTest {

    private IndeedAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new IndeedAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_indeed() { assertEquals("indeed", adapter.namespace()); }

    @Test
    void only_job_search() {
        assertEquals(1, adapter.capabilities().size());
        assertTrue(adapter.capabilities().contains("job_search"));
    }

    @Test
    void cred_slot_indeed_publisher_id() {
        assertEquals("indeed.publisher_id", adapter.credentialSlot());
    }

    @Test
    void job_search_blank_query() {
        var resp = adapter.invoke(new AdapterRequest("indeed", "job_search",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void job_search_no_cred_stub() {
        var resp = adapter.invoke(new AdapterRequest("indeed", "job_search",
            Map.of("query", "java engineer"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
        assertNotNull(data.get("jobs"));
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("indeed", "post_job",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }
}

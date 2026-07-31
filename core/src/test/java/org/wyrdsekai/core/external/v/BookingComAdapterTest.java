package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BookingComAdapterTest {

    private BookingComAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new BookingComAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_booking() {
        assertEquals("booking", adapter.namespace());
    }

    @Test
    void caps_are_hotel_search_only() {
        assertEquals(1, adapter.capabilities().size());
        assertTrue(adapter.capabilities().contains("hotel_search"));
    }

    @Test
    void cred_slot_is_affiliate_id() {
        assertEquals("booking.affiliate_id", adapter.credentialSlot());
    }

    @Test
    void hotel_search_blank_city_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("booking", "hotel_search",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("bad_request", resp.error().code());
    }

    @Test
    void hotel_search_no_cred_returns_stub() {
        var resp = adapter.invoke(new AdapterRequest("booking", "hotel_search",
            Map.of("city", "Kyoto"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("booking", "book",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }
}

package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShopifyAdapterTest {

    private ShopifyAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new ShopifyAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_shopify() { assertEquals("shopify", adapter.namespace()); }

    @Test
    void declares_three_caps_including_create_order() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("list_products"));
        assertTrue(caps.contains("list_orders"));
        assertTrue(caps.contains("create_order"));
    }

    @Test
    void list_products_no_cred_stub() {
        var resp = adapter.invoke(new AdapterRequest("shopify", "list_products",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
    }

    @Test
    void list_orders_no_cred_stub() {
        var resp = adapter.invoke(new AdapterRequest("shopify", "list_orders",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
    }

    @Test
    void create_order_without_write_cap_denied() {
        var caps = ItemCapabilitySet.of(Set.of("shopify.read"));
        var resp = adapter.invoke(new AdapterRequest("shopify", "create_order",
            Map.of(),
            caps, "test-item"));
        assertFalse(resp.success());
        assertEquals("permission_denied", resp.error().code());
    }

    @Test
    void create_order_with_write_cap_but_no_cred_returns_credential_missing() {
        var caps = ItemCapabilitySet.of(Set.of("shopify.write"));
        var resp = adapter.invoke(new AdapterRequest("shopify", "create_order",
            Map.of(),
            caps, "test-item"));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void create_order_with_cred_and_write_requires_steward_consent() {
        CredentialResolver.get().setSafeReader(slot -> Optional.of("token"));
        var caps = ItemCapabilitySet.of(Set.of("shopify.write"));
        var resp = adapter.invoke(new AdapterRequest("shopify", "create_order",
            Map.of(),
            caps, "test-item"));
        assertFalse(resp.success());
        assertEquals("steward_consent_required", resp.error().code());
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("shopify", "delete_store",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }
}

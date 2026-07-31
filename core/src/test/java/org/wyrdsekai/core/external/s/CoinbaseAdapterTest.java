package org.wyrdsekai.core.external.s;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Phase S — unit tests for the Coinbase adapter (read-only). */
class CoinbaseAdapterTest {

    private CoinbaseAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new CoinbaseAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void teardown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_coinbase() {
        assertEquals("coinbase", adapter.namespace());
    }

    @Test
    void capabilities_are_read_only() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("balances"));
        assertTrue(caps.contains("recent_transactions"));
        // No money-movement methods in Phase S.
        assertFalse(caps.contains("send"));
        assertFalse(caps.contains("withdraw"));
        assertEquals(2, caps.size());
    }

    @Test
    void credential_slot_is_api_key() {
        assertEquals("coinbase.api_key", adapter.credentialSlot());
    }

    @Test
    void missing_key_returns_credential_missing() {
        var resp = adapter.invoke(new AdapterRequest(
            "coinbase", "balances", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("coinbase-key"));
        var resp = adapter.invoke(new AdapterRequest(
            "coinbase", "send_btc", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void recent_transactions_default_account_does_not_throw() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("coinbase-key"));
        var resp = adapter.invoke(new AdapterRequest(
            "coinbase", "recent_transactions", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertNotNull(resp);
        // Offline → network_error is acceptable; the path under test is that
        // no ad-hoc exception escapes the adapter.
        assertFalse(resp.success());
    }
}

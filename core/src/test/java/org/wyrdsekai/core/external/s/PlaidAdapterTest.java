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

/** Phase S — unit tests for the Plaid adapter (offline / read-only surface). */
class PlaidAdapterTest {

    private PlaidAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new PlaidAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void teardown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_plaid() {
        assertEquals("plaid", adapter.namespace());
    }

    @Test
    void capabilities_are_read_only() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("list_accounts"));
        assertTrue(caps.contains("list_transactions"));
        assertTrue(caps.contains("balance"));
        // No write methods in Phase S.
        assertFalse(caps.contains("transfer"));
        assertFalse(caps.contains("create_transfer"));
        assertEquals(3, caps.size());
    }

    @Test
    void credential_slot_is_access_token() {
        assertEquals("plaid.access_token", adapter.credentialSlot());
    }

    @Test
    void missing_access_token_returns_credential_missing() {
        var resp = adapter.invoke(new AdapterRequest(
            "plaid", "list_accounts", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("access-sandbox-xxx"));
        var resp = adapter.invoke(new AdapterRequest(
            "plaid", "create_transfer", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void list_transactions_accepts_window_args_without_throwing() {
        // Stub credentials so the adapter reaches the request build path.
        // The actual HTTP send will fail → network_error, which is the
        // expected offline-test outcome.
        CredentialResolver.get().setSafeReader(s -> Optional.of("plaid-stub"));
        var resp = adapter.invoke(new AdapterRequest(
            "plaid", "list_transactions",
            Map.of("since", "2026-01-01", "until", "2026-02-01", "limit", 50),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertNotNull(resp);
        // Either network_error (most likely — DNS unreachable in CI) or
        // a structured plaid error code; never an unhandled exception.
        assertFalse(resp.success());
    }
}

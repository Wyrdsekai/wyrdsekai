package org.wyrdsekai.core.external.s;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.safety.SafeService;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Phase S — unit tests for the Stripe adapter (offline / no network). */
class StripeAdapterTest {

    private StripeAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new StripeAdapter();
        CredentialResolver.get().resetForTests();
        SafeService.get().resetForTests();
    }

    @AfterEach
    void teardown() {
        CredentialResolver.get().resetForTests();
        SafeService.get().resetForTests();
    }

    @Test
    void namespace_is_stripe() {
        assertEquals("stripe", adapter.namespace());
    }

    @Test
    void capabilities_match_phase_s_surface() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("list_charges"));
        assertTrue(caps.contains("create_payment_intent"));
        assertTrue(caps.contains("refund"));
        assertEquals(3, caps.size());
    }

    @Test
    void credential_slot_is_secret_key() {
        assertEquals("stripe.secret_key", adapter.credentialSlot());
    }

    @Test
    void missing_credential_returns_normalized_error() {
        // No credentials wired — every method should fail with credential_missing.
        var resp = adapter.invoke(new AdapterRequest(
            "stripe", "list_charges", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
        assertFalse(resp.error().retryable());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("sk_test_xxx"));
        var resp = adapter.invoke(new AdapterRequest(
            "stripe", "destroy_account", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void create_payment_intent_without_steward_token_is_rejected() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("sk_test_xxx"));
        var resp = adapter.invoke(new AdapterRequest(
            "stripe", "create_payment_intent",
            Map.of("amount", 1000L, "currency", "usd"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("steward_token_missing", resp.error().code());
        assertFalse(resp.error().retryable());
    }

    @Test
    void refund_without_steward_token_is_rejected() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("sk_test_xxx"));
        var resp = adapter.invoke(new AdapterRequest(
            "stripe", "refund", Map.of("charge", "ch_123"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("steward_token_missing", resp.error().code());
    }

    @Test
    void create_payment_intent_with_invalid_amount_short_circuits_after_token() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("sk_test_xxx"));
        SafeService.get().grantToken(StripeAdapter.TOKEN_PURPOSE, "test-token");
        var resp = adapter.invoke(new AdapterRequest(
            "stripe", "create_payment_intent",
            Map.of("amount", 0L, "currency", "usd"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }

    @Test
    void refund_without_charge_id_returns_invalid_args() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("sk_test_xxx"));
        SafeService.get().grantToken(StripeAdapter.TOKEN_PURPOSE, "test-token");
        var resp = adapter.invoke(new AdapterRequest(
            "stripe", "refund", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }
}

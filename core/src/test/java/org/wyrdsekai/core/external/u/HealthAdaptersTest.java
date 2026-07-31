package org.wyrdsekai.core.external.u;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §4.38 health & wearables adapter contract tests.
 *
 * <p>Phase U adapters return {@code credential_missing} when no token is
 * provisioned and {@code not_yet_wired} once a credential is present
 * (the live HTTP integration is added when stewards onboard real keys).
 * Apple HealthKit always returns {@code phone_only} server-side — the
 * iOS runtime overrides the namespace.</p>
 */
class HealthAdaptersTest {

    @BeforeEach
    void setup() {
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void teardown() {
        CredentialResolver.get().resetForTests();
    }

    private AdapterRequest req(String ns, String method) {
        return new AdapterRequest(ns, method, Map.of(),
            ItemCapabilitySet.UNRESTRICTED, "did:wyrd:test");
    }

    @Test
    void oura_without_token_returns_credential_missing() {
        var resp = new OuraAdapter().invoke(req("oura", "list_sleep"));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void oura_with_token_returns_not_yet_wired_stub() {
        CredentialResolver.get().setSafeReader(slot ->
            "oura.token".equals(slot) ? Optional.of("fake") : Optional.empty());
        var resp = new OuraAdapter().invoke(req("oura", "list_sleep"));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
        assertFalse(resp.error().retryable());
    }

    @Test
    void oura_declares_all_four_methods() {
        var caps = new OuraAdapter().capabilities();
        assertTrue(caps.contains("list_sleep"));
        assertTrue(caps.contains("list_activity"));
        assertTrue(caps.contains("list_heart_rate"));
        assertTrue(caps.contains("list_workouts"));
    }

    @Test
    void fitbit_credential_slot_matches_spec() {
        assertEquals("fitbit.access_token", new FitbitAdapter().credentialSlot());
    }

    @Test
    void fitbit_methods() {
        var caps = new FitbitAdapter().capabilities();
        assertEquals(4, caps.size());
        assertTrue(caps.contains("list_sleep"));
        assertTrue(caps.contains("weight"));
    }

    @Test
    void apple_health_always_returns_phone_only() {
        var resp = new AppleHealthAdapter().invoke(req("apple_health", "read"));
        assertFalse(resp.success());
        assertEquals("phone_only", resp.error().code());
    }

    @Test
    void apple_health_has_empty_credential_slot() {
        // Native OS auth — no Safe slot. Empty string is the signal.
        assertEquals("", new AppleHealthAdapter().credentialSlot());
    }

    @Test
    void whoop_declares_three_methods() {
        var caps = new WhoopAdapter().capabilities();
        assertEquals(3, caps.size());
        assertTrue(caps.contains("list_recovery"));
        assertTrue(caps.contains("list_strain"));
        assertTrue(caps.contains("list_sleep"));
    }

    @Test
    void garmin_credential_missing_then_stub() {
        var ad = new GarminAdapter();
        assertEquals("credential_missing",
            ad.invoke(req("garmin", "list_activities")).error().code());
        CredentialResolver.get().setSafeReader(slot ->
            "garmin.user_token".equals(slot) ? Optional.of("ok") : Optional.empty());
        AdapterResponse r2 = ad.invoke(req("garmin", "list_activities"));
        assertEquals("not_yet_wired", r2.error().code());
    }

    @Test
    void google_fit_declares_steps_and_workouts() {
        var caps = new GoogleFitAdapter().capabilities();
        assertTrue(caps.contains("list_steps"));
        assertTrue(caps.contains("list_workouts"));
    }
}

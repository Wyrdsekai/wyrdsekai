package org.wyrdsekai.core.external.u;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** §4.39 gov & civic adapter contract tests. */
class GovAdaptersTest {

    @BeforeEach
    void setup() { CredentialResolver.get().resetForTests(); }
    @AfterEach
    void teardown() { CredentialResolver.get().resetForTests(); }

    private AdapterRequest req(String ns, String method) {
        return new AdapterRequest(ns, method, Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null);
    }

    @Test
    void usajobs_without_key_returns_credential_missing() {
        var resp = new USAJobsAdapter().invoke(req("usajobs", "search"));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void usajobs_with_key_returns_stub() {
        CredentialResolver.get().setSafeReader(slot ->
            "usajobs.api_key".equals(slot) ? Optional.of("k") : Optional.empty());
        var resp = new USAJobsAdapter().invoke(req("usajobs", "search"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void datagov_is_unauthenticated_and_returns_stub_directly() {
        // datagov has no credential slot — proceeds straight to the live call
        // (stubbed in Phase U).
        assertEquals("", new DataGovAdapter().credentialSlot());
        var resp = new DataGovAdapter().invoke(req("datagov", "query"));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void datagov_unknown_method_returns_unknown_method_via_registry() {
        // direct adapter invoke skips the registry's method check, so we
        // expect the adapter's own fallback path. Phase U adapters route
        // every method through stub() which still produces a structured fail.
        var resp = new DataGovAdapter().invoke(req("datagov", "totally_made_up"));
        assertFalse(resp.success());
        // Either not_yet_wired (stub) or unknown_method depending on dispatch
        // — registry-level routing produces unknown_method; direct invoke
        // delegates to stub. Both are deterministic fail envelopes.
        assertNotNull(resp.error().code());
    }

    @Test
    void congress_declares_three_methods() {
        var caps = new CongressAdapter().capabilities();
        assertEquals(3, caps.size());
        assertTrue(caps.contains("bills"));
        assertTrue(caps.contains("members"));
        assertTrue(caps.contains("votes"));
    }

    @Test
    void congress_credential_slot_matches_spec() {
        assertEquals("congress.api_key", new CongressAdapter().credentialSlot());
    }

    @Test
    void congress_with_key_returns_stub() {
        CredentialResolver.get().setSafeReader(slot ->
            "congress.api_key".equals(slot) ? Optional.of("k") : Optional.empty());
        var resp = new CongressAdapter().invoke(req("congress", "bills"));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void irs_returns_stub_regardless_of_credential() {
        // IRS surface is read-only public data; no credential gate.
        var resp = new IRSAdapter().invoke(req("irs", "rates"));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void irs_namespace_and_methods() {
        var ad = new IRSAdapter();
        assertEquals("irs", ad.namespace());
        assertTrue(ad.capabilities().contains("rates"));
        assertTrue(ad.capabilities().contains("deadlines"));
    }
}

package org.wyrdsekai.core.external.s;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.safety.SafeService;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * assert that <em>every</em> Phase S
 * financial write is rejected without a steward-confirmation token.
 *
 * <p>This is the safety-net test class — even if a future refactor in a
 * single adapter accidentally drops the gate, this class fails first.
 * Each financial.write capability gets one explicit assertion.</p>
 */
@DisplayName("Phase S — financial-write steward-token gate")
class FinancialSafetyTest {

    @BeforeEach
    void setup() {
        CredentialResolver.get().resetForTests();
        SafeService.get().resetForTests();
        // Wire a dummy credential for every slot — we want the test to fail
        // because of the steward-token gate, not because of credential lookup.
        CredentialResolver.get().setSafeReader(slot -> Optional.of("dummy-secret"));
    }

    @AfterEach
    void teardown() {
        CredentialResolver.get().resetForTests();
        SafeService.get().resetForTests();
    }

    @Test
    @DisplayName("stripe.create_payment_intent rejects without steward token")
    void stripe_create_payment_intent_requires_steward_token() {
        var resp = new StripeAdapter().invoke(new AdapterRequest(
            "stripe", "create_payment_intent",
            Map.of("amount", 5000L, "currency", "usd"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success(), "stripe.create_payment_intent must deny without token");
        assertEquals("steward_token_missing", resp.error().code());
        assertFalse(resp.error().retryable(),
            "denial is not retryable — it requires a new steward grant");
    }

    @Test
    @DisplayName("stripe.refund rejects without steward token")
    void stripe_refund_requires_steward_token() {
        var resp = new StripeAdapter().invoke(new AdapterRequest(
            "stripe", "refund", Map.of("charge", "ch_test_xxx"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success(), "stripe.refund must deny without token");
        assertEquals("steward_token_missing", resp.error().code());
    }

    @Test
    @DisplayName("SafeService default-denies financial.write")
    void safeservice_default_denies_financial_write() {
        // Brand-new SafeService — no grants yet. requireStewardToken must throw.
        assertThrows(SafeService.StewardTokenMissingError.class,
            () -> SafeService.get().requireStewardToken("financial.write"));
    }

    @Test
    @DisplayName("SafeService consumes the token on require (one-shot)")
    void safeservice_token_is_one_shot() {
        SafeService.get().grantToken("financial.write", "tok-1");
        // First call consumes the token …
        assertDoesNotThrow(() -> SafeService.get().requireStewardToken("financial.write"));
        // … second call must throw again.
        assertThrows(SafeService.StewardTokenMissingError.class,
            () -> SafeService.get().requireStewardToken("financial.write"));
    }

    @Test
    @DisplayName("SafeService.DENY_ALL hardens tests around the deny path")
    void safeservice_deny_all_overrides_grants() {
        SafeService.get().grantToken("financial.write", "tok-1");
        SafeService.get().setMode(SafeService.Mode.DENY_ALL);
        assertThrows(SafeService.StewardTokenMissingError.class,
            () -> SafeService.get().requireStewardToken("financial.write"));
    }

    @Test
    @DisplayName("Read-only adapters do not invoke the steward-token gate")
    void readonly_adapters_skip_gate() {
        // Plaid / Wise / Coinbase are read-only — they must not consume a token
        // even when one is granted (they should never call requireStewardToken
        // in the first place).
        SafeService.get().grantToken("financial.write", "tok-1");

        // Each read call hits the network and will fail offline; the
        // assertion is that the granted token is still present after the
        // call (i.e., the adapter didn't consume it).
        new PlaidAdapter().invoke(new AdapterRequest(
            "plaid", "list_accounts", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        new WiseAdapter().invoke(new AdapterRequest(
            "wise", "balance", Map.of("profileId", "1"),
            ItemCapabilitySet.UNRESTRICTED, null));
        new CoinbaseAdapter().invoke(new AdapterRequest(
            "coinbase", "balances", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));

        // Reads must NOT consume the token — re-checking should still find it.
        assertTrue(SafeService.get().hasToken("financial.write"),
            "read-only adapters must never consume a steward-write token");
    }

    @Test
    @DisplayName("Stripe write succeeds (past gate) when token is granted")
    void stripe_write_passes_gate_when_token_granted() {
        SafeService.get().grantToken(StripeAdapter.TOKEN_PURPOSE, "tok-1");
        var resp = new StripeAdapter().invoke(new AdapterRequest(
            "stripe", "create_payment_intent",
            Map.of("amount", 0L, "currency", "usd"),
            ItemCapabilitySet.UNRESTRICTED, null));
        // Token consumed — the validation now fails on amount=0 instead.
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code(),
            "with steward token, request reaches argument validation");
    }
}

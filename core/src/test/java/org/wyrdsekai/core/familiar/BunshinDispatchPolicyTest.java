package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — bunshin dispatch placement across zones.
 * The structural invariant ("soul operations always route home") is
 * enforced at integration time; this unit verifies the placement decision.
 */
class BunshinDispatchPolicyTest {

    private static final String DID = "did:wyrd:zA:wyrd";
    private static final String HOME = "alpha";
    private static final String HOST = "beta";

    // ── trivial: at home ───────────────────────────────────────────────────

    @Test
    void agent_at_home_returns_local_home() {
        var ctx = new BunshinDispatchPolicy.DispatchContext(
            DID, HOME, HOME, false, Optional.empty());
        var d = BunshinDispatchPolicy.decide(ctx);
        assertEquals(BunshinDispatchPolicy.Placement.LOCAL_HOME, d.placement());
        assertTrue(d.permitted());
        assertTrue(d.reason().contains("at home"));
    }

    // ── default: visitor + home reachable ──────────────────────────────────

    @Test
    void visitor_with_home_reachable_routes_home() {
        var ctx = new BunshinDispatchPolicy.DispatchContext(
            DID, HOME, HOST, true, Optional.empty());
        var d = BunshinDispatchPolicy.decide(ctx);
        assertEquals(BunshinDispatchPolicy.Placement.HOME_ZONE, d.placement());
        assertTrue(d.reason().contains("federation"));
        assertTrue(d.agreementApplied().isEmpty(),
            "home routing doesn't consume a host agreement");
    }

    @Test
    void visitor_prefers_home_over_host_even_with_contract() {
        // Spec §16.1: default is home-zone compute; host-compute is opt-in,
        // but when home is reachable the default should win.
        var terms = new BunshinDispatchPolicy.BilateralTerms(
            HOST, HOME, true, 10000, true);
        var ctx = new BunshinDispatchPolicy.DispatchContext(
            DID, HOME, HOST, true, Optional.of(terms));
        var d = BunshinDispatchPolicy.decide(ctx);
        assertEquals(BunshinDispatchPolicy.Placement.HOME_ZONE, d.placement(),
            "home-reachable takes precedence over host contract");
    }

    // ── contracted: visitor + home unreachable + agreement allows ──────────

    @Test
    void visitor_with_contract_runs_on_host() {
        var terms = new BunshinDispatchPolicy.BilateralTerms(
            HOST, HOME, true, 5000, false);
        var ctx = new BunshinDispatchPolicy.DispatchContext(
            DID, HOME, HOST, false, Optional.of(terms));
        var d = BunshinDispatchPolicy.decide(ctx);
        assertEquals(BunshinDispatchPolicy.Placement.HOST_ZONE, d.placement());
        assertTrue(d.agreementApplied().isPresent());
        assertEquals(5000, d.agreementApplied().get().inferenceTokensPerDay());
        assertTrue(d.reason().contains("bilateral agreement"));
    }

    // ── refused: no home, no contract ──────────────────────────────────────

    @Test
    void no_home_no_contract_refused() {
        var ctx = new BunshinDispatchPolicy.DispatchContext(
            DID, HOME, HOST, false, Optional.empty());
        var d = BunshinDispatchPolicy.decide(ctx);
        assertEquals(BunshinDispatchPolicy.Placement.REFUSED, d.placement());
        assertFalse(d.permitted());
        assertTrue(d.reason().contains("unreachable"));
        assertTrue(d.reason().contains("no bilateral agreement"));
    }

    // ── refused: no home, contract without inference clause ────────────────

    @Test
    void contract_without_inference_clause_refuses() {
        var terms = new BunshinDispatchPolicy.BilateralTerms(
            HOST, HOME, /*allowInferenceForVisitors*/ false, 0, true);
        var ctx = new BunshinDispatchPolicy.DispatchContext(
            DID, HOME, HOST, false, Optional.of(terms));
        var d = BunshinDispatchPolicy.decide(ctx);
        assertEquals(BunshinDispatchPolicy.Placement.REFUSED, d.placement());
        assertTrue(d.reason().contains("does not include allowInferenceForVisitors"));
    }

    // ── context invariants ─────────────────────────────────────────────────

    @Test
    void context_rejects_blank_fields() {
        assertThrows(IllegalArgumentException.class,
            () -> new BunshinDispatchPolicy.DispatchContext(
                "", HOME, HOST, false, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new BunshinDispatchPolicy.DispatchContext(
                DID, "", HOST, false, Optional.empty()));
    }

    @Test
    void atHome_matches_home_equal_current() {
        var atHome = new BunshinDispatchPolicy.DispatchContext(
            DID, HOME, HOME, false, Optional.empty());
        assertTrue(atHome.atHome());
        var visiting = new BunshinDispatchPolicy.DispatchContext(
            DID, HOME, HOST, true, Optional.empty());
        assertFalse(visiting.atHome());
    }
}

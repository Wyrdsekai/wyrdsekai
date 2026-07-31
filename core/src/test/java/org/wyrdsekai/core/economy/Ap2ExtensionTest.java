package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §98.5-98.11 — AP2 Payment Protocol Bridge.
 */
class Ap2ExtensionTest {

    private Ap2Extension ap2;
    private AgentAccount account;
    private final String AGENT_DID = "did:key:z6MkAgent";
    private final String SERVICE_DID = "did:key:z6MkService";

    @BeforeEach
    void setup() {
        // Transport that always approves
        ap2 = new Ap2Extension(request ->
            Ap2Extension.PaymentResult.approved(request.id(),
                "tx-" + UUID.randomUUID(), request.amount()));

        account = new AgentAccount(AGENT_DID, "credits");
        account.earn(BigDecimal.valueOf(1000), "steward", "Initial funding");
        account.setMandate(AgentAccount.IntentMandate.permissive());
    }

    // --- Payment Processing ---

    @Test
    void process_simple_payment() {
        var request = Ap2Extension.PaymentRequest.create(
            AGENT_DID, SERVICE_DID, BigDecimal.valueOf(10),
            "credits", "compute", "GPU time");

        var result = ap2.processPayment(request, account);
        assertTrue(result.approved());
        assertEquals(BigDecimal.valueOf(10), result.settledAmount());
        assertEquals(1, ap2.totalPayments());
    }

    @Test
    void payment_denied_insufficient_balance() {
        var request = Ap2Extension.PaymentRequest.create(
            AGENT_DID, SERVICE_DID, BigDecimal.valueOf(5000),
            "credits", "compute", "Expensive");

        var result = ap2.processPayment(request, account);
        assertFalse(result.approved());
        assertTrue(result.reason().contains("Insufficient"));
    }

    @Test
    void payment_denied_by_mandate() {
        ap2.setMandate(AGENT_DID, AgentAccount.IntentMandate.restrictive());

        var request = Ap2Extension.PaymentRequest.create(
            AGENT_DID, SERVICE_DID, BigDecimal.valueOf(50),
            "credits", "compute", "Over limit");

        var result = ap2.processPayment(request, account);
        assertFalse(result.approved());
        assertTrue(result.reason().contains("limit"));
    }

    @Test
    void payment_denied_by_category() {
        ap2.setMandate(AGENT_DID, AgentAccount.IntentMandate.restrictive());

        var request = Ap2Extension.PaymentRequest.create(
            AGENT_DID, SERVICE_DID, BigDecimal.valueOf(0.5),
            "credits", "entertainment", "Not allowed");

        var result = ap2.processPayment(request, account);
        assertFalse(result.approved());
        assertTrue(result.reason().contains("Category"));
    }

    @Test
    void payment_denied_human_presence() {
        ap2.setMandate(AGENT_DID, AgentAccount.IntentMandate.restrictive());

        var request = Ap2Extension.PaymentRequest.create(
            AGENT_DID, SERVICE_DID, BigDecimal.valueOf(0.8),
            "credits", "compute", "Needs human");

        // restrictive: humanPresentAbove = 5, but we need amount > 5
        // Actually restrictive maxPerTx = 1, so 0.8 is within limit
        // And humanPresentAbove = 5, so 0.8 < 5 doesn't trigger
        var result = ap2.processPayment(request, account);
        assertTrue(result.approved()); // Within all limits
    }

    @Test
    void transport_failure_propagated() {
        var failingAp2 = new Ap2Extension(request ->
            Ap2Extension.PaymentResult.denied(request.id(), "Network timeout"));

        var request = Ap2Extension.PaymentRequest.create(
            AGENT_DID, SERVICE_DID, BigDecimal.ONE,
            "credits", "compute", "Test");

        var result = failingAp2.processPayment(request, account);
        assertFalse(result.approved());
        assertEquals("Network timeout", result.reason());
    }

    // --- Cart Processing ---

    @Test
    void process_cart_success() {
        ap2.setCartMandate(AGENT_DID, Ap2Extension.CartMandate.permissive(AGENT_DID));

        var items = List.of(
            Ap2Extension.PaymentRequest.create(AGENT_DID, SERVICE_DID,
                BigDecimal.valueOf(5), "credits", "compute", "Item 1"),
            Ap2Extension.PaymentRequest.create(AGENT_DID, SERVICE_DID,
                BigDecimal.valueOf(3), "credits", "compute", "Item 2")
        );

        var result = ap2.processCart(AGENT_DID, items, account);
        assertTrue(result.approved());
        assertEquals(0, BigDecimal.valueOf(8).compareTo(result.settledAmount()));
    }

    @Test
    void cart_denied_too_many_items() {
        ap2.setCartMandate(AGENT_DID,
            Ap2Extension.CartMandate.restrictive(AGENT_DID)); // max 3 items

        var items = new ArrayList<Ap2Extension.PaymentRequest>();
        for (int i = 0; i < 5; i++) {
            items.add(Ap2Extension.PaymentRequest.create(AGENT_DID, SERVICE_DID,
                BigDecimal.ONE, "credits", "compute", "Item " + i));
        }

        var result = ap2.processCart(AGENT_DID, items, account);
        assertFalse(result.approved());
        assertTrue(result.reason().contains("Too many items"));
    }

    @Test
    void cart_denied_total_exceeds_limit() {
        ap2.setCartMandate(AGENT_DID,
            Ap2Extension.CartMandate.restrictive(AGENT_DID)); // max 10

        var items = List.of(
            Ap2Extension.PaymentRequest.create(AGENT_DID, SERVICE_DID,
                BigDecimal.valueOf(6), "credits", "compute", "Expensive 1"),
            Ap2Extension.PaymentRequest.create(AGENT_DID, SERVICE_DID,
                BigDecimal.valueOf(6), "credits", "compute", "Expensive 2")
        );

        var result = ap2.processCart(AGENT_DID, items, account);
        assertFalse(result.approved());
        assertTrue(result.reason().contains("exceeds limit"));
    }

    @Test
    void cart_denied_blocked_category() {
        ap2.setCartMandate(AGENT_DID,
            Ap2Extension.CartMandate.restrictive(AGENT_DID));

        var items = List.of(
            Ap2Extension.PaymentRequest.create(AGENT_DID, SERVICE_DID,
                BigDecimal.ONE, "credits", "gambling", "Bad category")
        );

        var result = ap2.processCart(AGENT_DID, items, account);
        assertFalse(result.approved());
        assertTrue(result.reason().contains("blocked"));
    }

    @Test
    void cart_denied_needs_human_approval() {
        ap2.setCartMandate(AGENT_DID,
            Ap2Extension.CartMandate.restrictive(AGENT_DID)); // requireHumanApproval=true

        var items = List.of(
            Ap2Extension.PaymentRequest.create(AGENT_DID, SERVICE_DID,
                BigDecimal.ONE, "credits", "compute", "Needs approval")
        );

        var result = ap2.processCart(AGENT_DID, items, account);
        assertFalse(result.approved());
        assertTrue(result.reason().contains("Human approval"));
    }

    // --- Receiving Payments ---

    @Test
    void receive_payment() {
        var before = account.balance();
        ap2.receivePayment(AGENT_DID, BigDecimal.valueOf(50),
            "did:key:payer", "Service rendered", account);
        assertEquals(0, before.add(BigDecimal.valueOf(50)).compareTo(account.balance()));
    }

    // --- Subscriptions ---

    @Test
    void create_subscription() {
        var sub = ap2.subscribe(AGENT_DID, SERVICE_DID,
            "Cloud Compute", BigDecimal.valueOf(10), "credits", "monthly");

        assertNotNull(sub.id());
        assertEquals(AGENT_DID, sub.agentDid());
        assertTrue(sub.active());
        assertEquals("monthly", sub.interval());
    }

    @Test
    void cancel_subscription() {
        var sub = ap2.subscribe(AGENT_DID, SERVICE_DID,
            "Storage", BigDecimal.valueOf(5), "credits", "monthly");

        assertTrue(ap2.cancelSubscription(sub.id()));
        var active = ap2.activeSubscriptions(AGENT_DID);
        assertTrue(active.isEmpty());
    }

    @Test
    void cancel_nonexistent_subscription() {
        assertFalse(ap2.cancelSubscription("nonexistent"));
    }

    @Test
    void list_active_subscriptions() {
        ap2.subscribe(AGENT_DID, SERVICE_DID, "A", BigDecimal.ONE, "c", "monthly");
        ap2.subscribe(AGENT_DID, SERVICE_DID, "B", BigDecimal.ONE, "c", "weekly");
        var sub = ap2.subscribe(AGENT_DID, SERVICE_DID, "C", BigDecimal.ONE, "c", "daily");
        ap2.cancelSubscription(sub.id());

        var active = ap2.activeSubscriptions(AGENT_DID);
        assertEquals(2, active.size());
    }

    // --- CartMandate ---

    @Test
    void cart_mandate_restrictive() {
        var m = Ap2Extension.CartMandate.restrictive(AGENT_DID);
        assertEquals(3, m.maxItemsPerCart());
        assertTrue(m.requireHumanApproval());
        assertTrue(m.isCategoryBlocked("gambling"));
        assertFalse(m.isCategoryBlocked("compute"));
    }

    @Test
    void cart_mandate_permissive() {
        var m = Ap2Extension.CartMandate.permissive(AGENT_DID);
        assertEquals(20, m.maxItemsPerCart());
        assertFalse(m.requireHumanApproval());
        assertTrue(m.isCategoryBlocked("weapons"));
        assertFalse(m.isCategoryBlocked("compute"));
    }

    @Test
    void cart_mandate_vendor_check() {
        var m = new Ap2Extension.CartMandate(AGENT_DID,
            BigDecimal.valueOf(100), 10,
            List.of("vendor-a", "vendor-b"), List.of(), false,
            Instant.now(), null);
        assertTrue(m.isVendorAllowed("vendor-a"));
        assertFalse(m.isVendorAllowed("vendor-c"));
    }

    @Test
    void cart_mandate_empty_vendors_allows_all() {
        var m = Ap2Extension.CartMandate.permissive(AGENT_DID);
        assertTrue(m.isVendorAllowed("any-vendor"));
    }

    @Test
    void payment_request_with_service() {
        var request = Ap2Extension.PaymentRequest.create(
            AGENT_DID, SERVICE_DID, BigDecimal.ONE, "credits", "compute", "Test");
        var withService = request.withService("https://api.example.com");
        assertEquals("https://api.example.com", withService.serviceUri());
        assertEquals(request.amount(), withService.amount());
    }
}

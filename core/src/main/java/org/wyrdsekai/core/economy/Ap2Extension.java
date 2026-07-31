package org.wyrdsekai.core.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * AP2 Payment Protocol bridge (§98.5-98.11).
 *
 * AP2 = Agent Payment Protocol. Enables agents to make and receive
 * payments in the broader agent economy. The bridge translates
 * between Wyrdsekai's internal economy (CountingHouse, AgentAccount)
 * and external AP2 services.
 *
 * Three payment tiers (from §88.7 MCP payment model):
 * - Local: free (compute on steward's hardware)
 * - Keyed: human pays (API keys from The Safe)
 * - Metered: per-use tracked (AP2 or CountingHouse)
 *
 * IntentMandate (defined in AgentAccount) constrains agent spending.
 * CartMandate extends this to purchase workflows (shopping, subscriptions).
 */
public class Ap2Extension {

    /** AP2 payment request. */
    public record PaymentRequest(
        String id,
        String fromDid,
        String toDid,
        BigDecimal amount,
        String currency,
        String category,
        String description,
        String serviceUri,
        Instant requestedAt
    ) {
        public static PaymentRequest create(String fromDid, String toDid,
                                              BigDecimal amount, String currency,
                                              String category, String description) {
            return new PaymentRequest(
                UUID.randomUUID().toString(), fromDid, toDid,
                amount, currency, category, description, null, Instant.now());
        }

        public PaymentRequest withService(String uri) {
            return new PaymentRequest(id, fromDid, toDid, amount, currency,
                category, description, uri, requestedAt);
        }
    }

    /** AP2 payment result. */
    public record PaymentResult(
        String requestId,
        boolean approved,
        String reason,
        String transactionId,
        BigDecimal settledAmount,
        Instant settledAt
    ) {
        public static PaymentResult approved(String requestId, String txId, BigDecimal amount) {
            return new PaymentResult(requestId, true, "Approved",
                txId, amount, Instant.now());
        }

        public static PaymentResult denied(String requestId, String reason) {
            return new PaymentResult(requestId, false, reason,
                null, BigDecimal.ZERO, Instant.now());
        }
    }

    /** Cart mandate: constraints for purchase workflows (§98.8). */
    public record CartMandate(
        String agentDid,
        BigDecimal maxCartTotal,
        int maxItemsPerCart,
        List<String> approvedVendors,  // empty = all vendors
        List<String> blockedCategories,
        boolean requireHumanApproval,
        Instant validFrom,
        Instant validUntil
    ) {
        public static CartMandate restrictive(String agentDid) {
            return new CartMandate(agentDid, BigDecimal.TEN, 3, List.of(),
                List.of("gambling", "weapons", "adult"), true,
                Instant.now(), null);
        }

        public static CartMandate permissive(String agentDid) {
            return new CartMandate(agentDid, BigDecimal.valueOf(500), 20, List.of(),
                List.of("weapons"), false, Instant.now(), null);
        }

        public boolean isExpired() {
            return validUntil != null && Instant.now().isAfter(validUntil);
        }

        public boolean isVendorAllowed(String vendor) {
            return approvedVendors.isEmpty() || approvedVendors.contains(vendor);
        }

        public boolean isCategoryBlocked(String category) {
            return blockedCategories.contains(category);
        }
    }

    /** Subscription record for recurring payments. */
    public record Subscription(
        String id,
        String agentDid,
        String serviceDid,
        String serviceName,
        BigDecimal amount,
        String currency,
        String interval,    // "daily", "weekly", "monthly"
        Instant startedAt,
        Instant nextPayment,
        boolean active
    ) {
        public Subscription cancel() {
            return new Subscription(id, agentDid, serviceDid, serviceName,
                amount, currency, interval, startedAt, nextPayment, false);
        }
    }

    /** AP2 transport abstraction. */
    @FunctionalInterface
    public interface Ap2Transport {
        PaymentResult send(PaymentRequest request);
    }

    private final Ap2Transport transport;
    private final Map<String, AgentAccount.IntentMandate> mandates = new LinkedHashMap<>();
    private final Map<String, CartMandate> cartMandates = new LinkedHashMap<>();
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    private final List<PaymentResult> paymentHistory = new ArrayList<>();

    public Ap2Extension(Ap2Transport transport) {
        this.transport = transport;
    }

    /** Register a spending mandate for an agent. */
    public void setMandate(String agentDid, AgentAccount.IntentMandate mandate) {
        mandates.put(agentDid, mandate);
    }

    /** Register a cart mandate for an agent. */
    public void setCartMandate(String agentDid, CartMandate mandate) {
        cartMandates.put(agentDid, mandate);
    }

    /**
     * Process a payment request. Checks mandate compliance before sending.
     */
    public PaymentResult processPayment(PaymentRequest request, AgentAccount account) {
        // 1. Check intent mandate
        var mandate = mandates.get(request.fromDid());
        if (mandate != null) {
            var mandateCheck = checkMandate(request, mandate);
            if (mandateCheck.isPresent()) {
                var denied = PaymentResult.denied(request.id(), mandateCheck.get());
                paymentHistory.add(denied);
                return denied;
            }
        }

        // 2. Check balance
        if (account.balance().compareTo(request.amount()) < 0) {
            var denied = PaymentResult.denied(request.id(), "Insufficient balance");
            paymentHistory.add(denied);
            return denied;
        }

        // 3. Send via AP2 transport
        var result = transport.send(request);
        paymentHistory.add(result);

        // 4. Record in agent account if approved
        if (result.approved()) {
            account.spend(request.amount(), request.toDid(),
                request.description(), request.category());
        }

        return result;
    }

    /**
     * Process a cart (multi-item purchase). Checks cart mandate compliance.
     */
    public PaymentResult processCart(String agentDid, List<PaymentRequest> items,
                                       AgentAccount account) {
        var cartMandate = cartMandates.get(agentDid);

        if (cartMandate != null) {
            if (cartMandate.isExpired()) {
                return PaymentResult.denied("cart", "Cart mandate expired");
            }
            if (items.size() > cartMandate.maxItemsPerCart()) {
                return PaymentResult.denied("cart",
                    "Too many items: " + items.size() + " > " + cartMandate.maxItemsPerCart());
            }
            BigDecimal total = items.stream()
                .map(PaymentRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(cartMandate.maxCartTotal()) > 0) {
                return PaymentResult.denied("cart",
                    "Cart total " + total + " exceeds limit " + cartMandate.maxCartTotal());
            }
            for (var item : items) {
                if (cartMandate.isCategoryBlocked(item.category())) {
                    return PaymentResult.denied(item.id(),
                        "Category blocked: " + item.category());
                }
            }
            if (cartMandate.requireHumanApproval()) {
                return PaymentResult.denied("cart", "Human approval required for cart");
            }
        }

        // Process each item
        BigDecimal totalSpent = BigDecimal.ZERO;
        for (var item : items) {
            var result = processPayment(item, account);
            if (!result.approved()) {
                return result;
            }
            totalSpent = totalSpent.add(result.settledAmount());
        }

        return PaymentResult.approved("cart", "cart-" + UUID.randomUUID(), totalSpent);
    }

    /** Receive a payment from an external agent. */
    public void receivePayment(String toDid, BigDecimal amount,
                                 String fromDid, String description,
                                 AgentAccount account) {
        account.earn(amount, fromDid, description);
    }

    /** Create a subscription. */
    public Subscription subscribe(String agentDid, String serviceDid,
                                    String serviceName, BigDecimal amount,
                                    String currency, String interval) {
        var sub = new Subscription(UUID.randomUUID().toString(), agentDid,
            serviceDid, serviceName, amount, currency, interval,
            Instant.now(), Instant.now(), true);
        subscriptions.put(sub.id(), sub);
        return sub;
    }

    /** Cancel a subscription. */
    public boolean cancelSubscription(String subscriptionId) {
        var sub = subscriptions.get(subscriptionId);
        if (sub == null) return false;
        subscriptions.put(subscriptionId, sub.cancel());
        return true;
    }

    /** Get active subscriptions for an agent. */
    public List<Subscription> activeSubscriptions(String agentDid) {
        return subscriptions.values().stream()
            .filter(s -> s.agentDid().equals(agentDid) && s.active())
            .toList();
    }

    /** Total payments processed. */
    public int totalPayments() {
        return paymentHistory.size();
    }

    private Optional<String> checkMandate(PaymentRequest request,
                                            AgentAccount.IntentMandate mandate) {
        if (request.amount().compareTo(mandate.maxPerTransaction()) > 0) {
            return Optional.of("Exceeds per-transaction limit: " + request.amount()
                + " > " + mandate.maxPerTransaction());
        }
        if (!mandate.approvedCategories().isEmpty()
            && !mandate.approvedCategories().contains(request.category())) {
            return Optional.of("Category not approved: " + request.category());
        }
        if (request.amount().compareTo(mandate.humanPresentAbove()) > 0) {
            return Optional.of("Requires human presence for amount: " + request.amount());
        }
        return Optional.empty();
    }
}

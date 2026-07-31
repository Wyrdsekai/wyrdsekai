package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shopify storefront adapter.
 *
 * <p>Reads ({@code list_products}, {@code list_orders}) are Tier 4. Writes
 * ({@code create_order}) are Tier 7 with steward gating — the adapter
 * additionally requires the steward token presented at call time, surfaced
 * via {@link AdapterRequest#capabilities()}.</p>
 *
 * <p>When credentials or the steward token are missing, returns a structured
 * stub. Real Shopify Admin API integration would target
 * {@code https://{shop}.myshopify.com/admin/api/2024-01/...} with the
 * {@code X-Shopify-Access-Token} header — held off until creds are wired.</p>
 */
public final class ShopifyAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "shopify"; }

    @Override public Set<String> capabilities() {
        return Set.of("list_products", "list_orders", "create_order");
    }

    @Override public String credentialSlot() { return "shopify.access_token"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        var args = request.args();
        return switch (request.method()) {
            case "list_products" -> listProducts(args);
            case "list_orders" -> listOrders(args);
            case "create_order" -> createOrder(request);
            default -> AdapterResponse.fail("unknown_method",
                "shopify." + request.method() + " is not supported", false);
        };
    }

    private AdapterResponse listProducts(Map<String, Object> args) {
        if (credential().isEmpty()) {
            return stub("products", "credential_missing:shopify.access_token");
        }
        return stub("products", "live_not_wired");
    }

    private AdapterResponse listOrders(Map<String, Object> args) {
        if (credential().isEmpty()) {
            return stub("orders", "credential_missing:shopify.access_token");
        }
        return stub("orders", "live_not_wired");
    }

    /**
     * Tier 7 write — create_order. Requires the {@code shopify.write}
     * capability declared on the manifest plus a steward-token check on
     * top of the cap gate.
     */
    private AdapterResponse createOrder(AdapterRequest request) {
        var caps = request.capabilities();
        if (caps != null && !caps.isUnrestricted() && !caps.has("shopify.write")) {
            return AdapterResponse.fail("permission_denied",
                "create_order requires shopify.write capability + steward token",
                false);
        }
        if (credential().isEmpty()) {
            return AdapterResponse.fail("credential_missing",
                "shopify.access_token is required for create_order", false);
        }
        // Tier 7 — require explicit steward consent to proceed; until the
        // ritual confirmation pipeline is wired we surface the pending state
        // rather than executing.
        return AdapterResponse.fail("steward_consent_required",
            "create_order is Tier 7; steward must confirm the spend in the chapel",
            false);
    }

    private AdapterResponse stub(String key, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put(key, List.of());
        return AdapterResponse.ok(out);
    }
}

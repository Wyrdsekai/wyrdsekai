package org.wyrdsekai.core.external.s;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;

/**
 * Coinbase adapter ({@code world.coinbase.*}).
 *
 * <p>Read-only Phase S surface. All actual money-movement methods
 * ({@code create_charge} / sends) remain Tier 7 and will land alongside the
 * crypto-wallet ritual flow in a later phase.</p>
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code balances()} — list balances per Coinbase wallet</li>
 *   <li>{@code recent_transactions({account?, limit?})} — paginated tx list</li>
 * </ul>
 *
 * <p>Credential slot: {@code coinbase.api_key}. The bearer-token style works
 * with Coinbase Commerce + the Advanced Trade API; for legacy v2 endpoints
 * Coinbase requires HMAC signing which the stub omits — invoking against a
 * v2 endpoint surfaces the upstream {@code authentication_error} normally.</p>
 */
public final class CoinbaseAdapter extends PhaseSAdapterSupport implements ExternalAdapter {

    static final String NAMESPACE = "coinbase";
    static final String CRED_SLOT = "coinbase.api_key";
    static final String API_BASE = "https://api.coinbase.com";

    private static final Set<String> METHODS = Set.of(
        "balances",
        "recent_transactions"
    );

    @Override public String namespace() { return NAMESPACE; }
    @Override public Set<String> capabilities() { return METHODS; }
    @Override public String credentialSlot() { return CRED_SLOT; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var key = credential(CRED_SLOT);
        if (key.isEmpty()) return credentialMissing(CRED_SLOT);
        return switch (req.method()) {
            case "balances" -> balances(key.get());
            case "recent_transactions" -> recentTransactions(key.get(), req.args());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse balances(String key) {
        return get(API_BASE + "/v2/accounts", key);
    }

    private AdapterResponse recentTransactions(String key, Map<String, Object> args) {
        var account = strArg(args, "account", "primary");
        var limit = longArg(args, "limit");
        var qs = limit == null ? "" : "?limit=" + Math.min(Math.max(limit, 1), 100);
        return get(API_BASE + "/v2/accounts/" + account + "/transactions" + qs, key);
    }

    private AdapterResponse get(String url, String key) {
        try {
            var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(ADAPTER_TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .header("CB-VERSION", "2024-01-01")
                .GET()
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            var status = resp.statusCode();
            var node = MAPPER.readTree(resp.body());
            if (status >= 200 && status < 300) {
                return AdapterResponse.ok(MAPPER.convertValue(node, Object.class));
            }
            var msg = "coinbase returned " + status;
            if (node.has("errors") && node.get("errors").isArray() && node.get("errors").size() > 0) {
                var first = node.get("errors").get(0);
                if (first.has("message")) msg = first.get("message").asText();
            }
            return AdapterResponse.fail("coinbase_error_" + status, msg,
                status >= 500 || status == 429);
        } catch (Exception e) {
            log.debug("coinbase get failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }
}

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
 * Wise (TransferWise) adapter
 * ({@code world.wise.*}). Read-only initially; {@code wise.write} is Tier 7
 * deferred until a later phase wires the chapel-ritual flow.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code balance({profileId})} — list balance accounts for the steward's profile</li>
 *   <li>{@code recent_transfers({profileId, limit?})} — list recent transfers</li>
 * </ul>
 *
 * <p>Credential slot: {@code wise.api_token} — Wise personal API tokens
 * scope to a single profile / sandbox by default; multi-profile setups
 * pass {@code profileId} explicitly.</p>
 */
public final class WiseAdapter extends PhaseSAdapterSupport implements ExternalAdapter {

    static final String NAMESPACE = "wise";
    static final String CRED_SLOT = "wise.api_token";
    static final String API_BASE = "https://api.wise.com";

    private static final Set<String> METHODS = Set.of(
        "balance",
        "recent_transfers"
    );

    @Override public String namespace() { return NAMESPACE; }
    @Override public Set<String> capabilities() { return METHODS; }
    @Override public String credentialSlot() { return CRED_SLOT; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var token = credential(CRED_SLOT);
        if (token.isEmpty()) return credentialMissing(CRED_SLOT);
        return switch (req.method()) {
            case "balance" -> balance(token.get(), req.args());
            case "recent_transfers" -> recentTransfers(token.get(), req.args());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse balance(String token, Map<String, Object> args) {
        var profile = strArg(args, "profileId");
        if (profile == null) {
            return AdapterResponse.fail("invalid_args", "profileId is required", false);
        }
        return get(API_BASE + "/v4/profiles/" + profile + "/balances?types=STANDARD", token);
    }

    private AdapterResponse recentTransfers(String token, Map<String, Object> args) {
        var profile = strArg(args, "profileId");
        if (profile == null) {
            return AdapterResponse.fail("invalid_args", "profileId is required", false);
        }
        var limit = longArg(args, "limit");
        var qs = "?profile=" + profile;
        if (limit != null) qs += "&limit=" + Math.min(Math.max(limit, 1), 100);
        return get(API_BASE + "/v1/transfers" + qs, token);
    }

    private AdapterResponse get(String url, String token) {
        try {
            var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(ADAPTER_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            var status = resp.statusCode();
            var node = MAPPER.readTree(resp.body());
            if (status >= 200 && status < 300) {
                return AdapterResponse.ok(MAPPER.convertValue(node, Object.class));
            }
            var msg = node.has("message") ? node.get("message").asText()
                : "wise returned " + status;
            return AdapterResponse.fail("wise_error_" + status, msg,
                status >= 500 || status == 429);
        } catch (Exception e) {
            log.debug("wise get failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }
}

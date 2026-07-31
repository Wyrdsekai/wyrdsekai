package org.wyrdsekai.core.external.s;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plaid adapter ({@code world.plaid.*}).
 *
 * <p>Read-only by design — Plaid integrations <em>can</em> mutate (e.g.
 * Auth-debited transfers), but Phase S restricts the surface to history /
 * balance reads. Future phases that need writes can extend this adapter
 * behind a steward-token gate using the Stripe write pattern.</p>
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code list_accounts()} → {@code [{id, name, type, balance}]}</li>
 *   <li>{@code list_transactions({account?, since?, until?, limit?})}
 *       → {@code [{id, amount, merchant, date}]}</li>
 *   <li>{@code balance({account})} → {@code {available, current}}</li>
 * </ul>
 *
 * <p>The credential slot is {@code plaid.access_token}; production
 * deployments combine this with a {@code plaid.client_id} +
 * {@code plaid.secret} pair which the resolver looks up implicitly through
 * the same Safe-mediated path. To keep the stub portable we read all three
 * slots and fall back to {@code "<missing>"} placeholders when only the
 * access token is set — Plaid will reject the call with a structured error
 * which the adapter surfaces verbatim.</p>
 */
public final class PlaidAdapter extends PhaseSAdapterSupport implements ExternalAdapter {

    static final String NAMESPACE = "plaid";
    static final String CRED_SLOT = "plaid.access_token";
    static final String CLIENT_ID_SLOT = "plaid.client_id";
    static final String SECRET_SLOT = "plaid.secret";

    /** Production: api.plaid.com. Sandbox: sandbox.plaid.com. Read from creds. */
    static final String API_BASE = "https://production.plaid.com";

    private static final Set<String> METHODS = Set.of(
        "list_accounts",
        "list_transactions",
        "balance"
    );

    @Override public String namespace() { return NAMESPACE; }
    @Override public Set<String> capabilities() { return METHODS; }
    @Override public String credentialSlot() { return CRED_SLOT; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var token = credential(CRED_SLOT);
        if (token.isEmpty()) return credentialMissing(CRED_SLOT);
        var clientId = credential(CLIENT_ID_SLOT).orElse(null);
        var secret = credential(SECRET_SLOT).orElse(null);

        return switch (req.method()) {
            case "list_accounts" -> listAccounts(token.get(), clientId, secret);
            case "list_transactions" -> listTransactions(token.get(), clientId, secret, req.args());
            case "balance" -> balance(token.get(), clientId, secret, req.args());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse listAccounts(String token, String clientId, String secret) {
        var body = baseBody(token, clientId, secret);
        return post("/accounts/get", body);
    }

    private AdapterResponse listTransactions(String token, String clientId, String secret,
                                             Map<String, Object> args) {
        var body = baseBody(token, clientId, secret);
        var since = strArg(args, "since", LocalDate.now().minusDays(30).toString());
        var until = strArg(args, "until", LocalDate.now().toString());
        body.put("start_date", since);
        body.put("end_date", until);
        var limit = longArg(args, "limit");
        if (limit != null) body.put("options", Map.of("count", Math.min(Math.max(limit, 1), 500)));
        return post("/transactions/get", body);
    }

    private AdapterResponse balance(String token, String clientId, String secret,
                                    Map<String, Object> args) {
        var body = baseBody(token, clientId, secret);
        var account = strArg(args, "account");
        if (account != null) {
            body.put("options", Map.of("account_ids", List.of(account)));
        }
        return post("/accounts/balance/get", body);
    }

    private LinkedHashMap<String, Object> baseBody(String token, String clientId, String secret) {
        var body = new LinkedHashMap<String, Object>();
        body.put("access_token", token);
        body.put("client_id", clientId == null ? "<missing>" : clientId);
        body.put("secret", secret == null ? "<missing>" : secret);
        return body;
    }

    private AdapterResponse post(String path, Map<String, Object> body) {
        try {
            var json = MAPPER.writeValueAsString(body);
            var req = HttpRequest.newBuilder(URI.create(API_BASE + path))
                .timeout(ADAPTER_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            var status = resp.statusCode();
            var node = MAPPER.readTree(resp.body());
            if (status >= 200 && status < 300) {
                return AdapterResponse.ok(MAPPER.convertValue(node, Object.class));
            }
            var code = node.has("error_code") ? node.get("error_code").asText()
                : "plaid_error_" + status;
            var msg = node.has("error_message") ? node.get("error_message").asText()
                : "plaid returned " + status;
            return AdapterResponse.fail(code, msg, status >= 500 || status == 429);
        } catch (Exception e) {
            log.debug("plaid post failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }
}

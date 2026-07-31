package org.wyrdsekai.core.external.s;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;
import org.wyrdsekai.core.safety.SafeService;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Stripe adapter ({@code world.stripe.*}).
 *
 * <p>Read paths ({@code list_charges}) are unconditionally available given a
 * valid {@code stripe.secret_key}. Write paths
 * ({@code create_payment_intent}, {@code refund}) require a
 * <strong>steward-confirmation token</strong> resolved via
 * {@link SafeService#requireStewardToken(String)} — Phase S ships with the
 * gate stubbed; the chapel-ritual UX lands in a later phase but the contract
 * is enforced today so items can be authored against the final shape.</p>
 *
 * <p>Read-only mode is the safe default. To opt an installation into writes,
 * the steward grants a one-shot {@code financial.write} token through
 * {@code SafeService.grantToken}; the adapter consumes it on the next
 * mutation. Missing token returns a structured
 * {@code {error: {code: "steward_token_missing", retryable: false}}}
 * response.</p>
 */
public final class StripeAdapter extends PhaseSAdapterSupport implements ExternalAdapter {

    static final String NAMESPACE = "stripe";
    static final String CRED_SLOT = "stripe.secret_key";
    static final String API_BASE = "https://api.stripe.com/v1";

    /** Steward-token purpose — lines up with {@code stripe.write} capability. */
    static final String TOKEN_PURPOSE = "financial.write";

    private static final Set<String> METHODS = Set.of(
        "list_charges",
        "create_payment_intent",
        "refund"
    );

    @Override public String namespace() { return NAMESPACE; }
    @Override public Set<String> capabilities() { return METHODS; }
    @Override public String credentialSlot() { return CRED_SLOT; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var creds = credential(CRED_SLOT);
        if (creds.isEmpty()) return credentialMissing(CRED_SLOT);
        var key = creds.get();

        return switch (req.method()) {
            case "list_charges" -> listCharges(key, req.args());
            case "create_payment_intent" -> guardedWrite(() -> createPaymentIntent(key, req.args()));
            case "refund" -> guardedWrite(() -> refund(key, req.args()));
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    /** Wrap a write call so the steward-token check is uniform. */
    private AdapterResponse guardedWrite(Supplier<AdapterResponse> body) {
        try {
            SafeService.get().requireStewardToken(TOKEN_PURPOSE);
        } catch (SafeService.StewardTokenMissingError e) {
            return AdapterResponse.fail(
                "steward_token_missing",
                "stripe write requires a steward-confirmation token (purpose=" + e.purpose() + ")",
                false);
        }
        return body.get();
    }

    private AdapterResponse listCharges(String key, Map<String, Object> args) {
        var limit = longArg(args, "limit");
        var qs = new StringBuilder();
        if (limit != null) qs.append("limit=").append(Math.min(Math.max(limit, 1), 100));
        var customer = strArg(args, "customer");
        if (customer != null) {
            if (qs.length() > 0) qs.append('&');
            qs.append("customer=").append(URLEncoder.encode(customer, StandardCharsets.UTF_8));
        }
        var url = API_BASE + "/charges" + (qs.length() == 0 ? "" : "?" + qs);
        return getJson(url, key);
    }

    private AdapterResponse createPaymentIntent(String key, Map<String, Object> args) {
        var amount = longArg(args, "amount");
        var currency = strArg(args, "currency", "usd");
        if (amount == null || amount <= 0) {
            return AdapterResponse.fail("invalid_args", "amount (positive minor units) is required", false);
        }
        var form = new LinkedHashMap<String, String>();
        form.put("amount", String.valueOf(amount));
        form.put("currency", currency);
        var customer = strArg(args, "customer");
        if (customer != null) form.put("customer", customer);
        var description = strArg(args, "description");
        if (description != null) form.put("description", description);
        return postForm(API_BASE + "/payment_intents", key, form);
    }

    private AdapterResponse refund(String key, Map<String, Object> args) {
        var charge = strArg(args, "charge");
        if (charge == null) {
            return AdapterResponse.fail("invalid_args", "charge id is required", false);
        }
        var form = new LinkedHashMap<String, String>();
        form.put("charge", charge);
        var amount = longArg(args, "amount");
        if (amount != null) form.put("amount", String.valueOf(amount));
        return postForm(API_BASE + "/refunds", key, form);
    }

    // ─── HTTP helpers ────────────────────────────────────────────────

    private AdapterResponse getJson(String url, String key) {
        try {
            var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(ADAPTER_TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .GET()
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return decode(resp);
        } catch (Exception e) {
            log.debug("stripe get failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }

    private AdapterResponse postForm(String url, String key, Map<String, String> form) {
        try {
            var body = new StringBuilder();
            form.forEach((k, v) -> {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            });
            var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(ADAPTER_TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return decode(resp);
        } catch (Exception e) {
            log.debug("stripe post failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }

    private AdapterResponse decode(HttpResponse<String> resp) {
        try {
            var status = resp.statusCode();
            var node = MAPPER.readTree(resp.body());
            if (status >= 200 && status < 300) {
                return AdapterResponse.ok(MAPPER.convertValue(node, Object.class));
            }
            var code = "stripe_error_" + status;
            String msg = node.has("error") && node.get("error").has("message")
                ? node.get("error").get("message").asText()
                : "stripe returned " + status;
            return AdapterResponse.fail(code, msg, status >= 500 || status == 429);
        } catch (Exception e) {
            return AdapterResponse.fail("decode_error", e.getMessage(), false);
        }
    }

    /** Visible for tests — capability list. */
    static List<String> methods() { return List.copyOf(METHODS); }
}

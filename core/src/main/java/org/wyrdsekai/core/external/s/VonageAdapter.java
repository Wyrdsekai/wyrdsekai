package org.wyrdsekai.core.external.s;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Vonage adapter ({@code world.vonage.*}).
 *
 * <p>Alternative provider to {@link TwilioAdapter} — only the SMS surface is
 * exposed in Phase S. Vonage uses query-string-style API key + secret auth
 * for the Messages legacy API, which is what the steward-facing console
 * surfaces by default.</p>
 *
 * <p>Method:</p>
 * <ul>
 *   <li>{@code send_sms({to, text, from?})}</li>
 * </ul>
 *
 * <p>Credentials: {@code vonage.api_key} + {@code vonage.api_secret}, plus
 * an optional {@code vonage.from_number} default sender id.</p>
 */
public final class VonageAdapter extends PhaseSAdapterSupport implements ExternalAdapter {

    static final String NAMESPACE = "vonage";
    static final String KEY_SLOT = "vonage.api_key";
    static final String SECRET_SLOT = "vonage.api_secret";
    static final String FROM_SLOT = "vonage.from_number";
    static final String API_BASE = "https://rest.nexmo.com";

    private static final Set<String> METHODS = Set.of("send_sms");

    @Override public String namespace() { return NAMESPACE; }
    @Override public Set<String> capabilities() { return METHODS; }
    @Override public String credentialSlot() { return KEY_SLOT; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var key = credential(KEY_SLOT);
        if (key.isEmpty()) return credentialMissing(KEY_SLOT);
        var secret = credential(SECRET_SLOT);
        if (secret.isEmpty()) return credentialMissing(SECRET_SLOT);
        var defaultFrom = credential(FROM_SLOT).orElse(null);

        if ("send_sms".equals(req.method())) {
            return sendSms(key.get(), secret.get(), req.args(), defaultFrom);
        }
        return AdapterResponse.fail("unknown_method", req.method(), false);
    }

    private AdapterResponse sendSms(String key, String secret, Map<String, Object> args,
                                    String defaultFrom) {
        var to = strArg(args, "to");
        var text = strArg(args, "text");
        if (to == null || text == null) {
            return AdapterResponse.fail("invalid_args", "to + text are required", false);
        }
        var from = strArg(args, "from", defaultFrom);
        if (from == null) {
            return AdapterResponse.fail("invalid_args",
                "from is required (set vonage.from_number or pass explicitly)", false);
        }

        var form = new LinkedHashMap<String, String>();
        form.put("api_key", key);
        form.put("api_secret", secret);
        form.put("to", to);
        form.put("from", from);
        form.put("text", text);
        return postForm(API_BASE + "/sms/json", form);
    }

    private AdapterResponse postForm(String url, Map<String, String> form) {
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
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            var status = resp.statusCode();
            var node = MAPPER.readTree(resp.body());
            if (status >= 200 && status < 300) {
                // Vonage returns 200 even on logical failures; check per-message status
                if (node.has("messages") && node.get("messages").isArray()
                        && node.get("messages").size() > 0) {
                    var first = node.get("messages").get(0);
                    var s = first.has("status") ? first.get("status").asText() : "0";
                    if (!"0".equals(s)) {
                        var em = first.has("error-text") ? first.get("error-text").asText()
                            : "vonage logical-error status=" + s;
                        return AdapterResponse.fail("vonage_status_" + s, em, false);
                    }
                }
                return AdapterResponse.ok(MAPPER.convertValue(node, Object.class));
            }
            return AdapterResponse.fail("vonage_error_" + status,
                "vonage returned " + status, status >= 500 || status == 429);
        } catch (Exception e) {
            log.debug("vonage post failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }
}

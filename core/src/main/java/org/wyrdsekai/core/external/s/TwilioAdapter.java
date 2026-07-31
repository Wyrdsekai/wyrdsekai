package org.wyrdsekai.core.external.s;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Twilio adapter ({@code world.twilio.*}).
 *
 * <p>All methods place a real outbound communication and are Tier 5 — the
 * runtime gates them via the {@code twilio.sms} / {@code twilio.whatsapp} /
 * {@code twilio.voice} capabilities. Items must declare per-method
 * {@code rate_limits} in their manifest (the catalogue inherits Tier 5 for
 * the {@code twilio.*} wildcard, so concrete caps validate too).</p>
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code send_sms({to, body, from?})}</li>
 *   <li>{@code send_whatsapp({to, body, from?})} — same Messages API,
 *       {@code whatsapp:} URI prefix.</li>
 *   <li>{@code voice_call({to, twiml | url, from?})} — outbound call via
 *       the Calls API.</li>
 * </ul>
 *
 * <p>Credentials: {@code twilio.account_sid} + {@code twilio.auth_token},
 * plus an optional {@code twilio.from_number} default. Auth uses HTTP
 * Basic with the SID + token pair, per Twilio docs.</p>
 */
public final class TwilioAdapter extends PhaseSAdapterSupport implements ExternalAdapter {

    static final String NAMESPACE = "twilio";
    static final String SID_SLOT = "twilio.account_sid";
    static final String TOKEN_SLOT = "twilio.auth_token";
    static final String FROM_SLOT = "twilio.from_number";
    static final String API_BASE = "https://api.twilio.com/2010-04-01";

    private static final Set<String> METHODS = Set.of(
        "send_sms",
        "send_whatsapp",
        "voice_call"
    );

    @Override public String namespace() { return NAMESPACE; }
    @Override public Set<String> capabilities() { return METHODS; }
    @Override public String credentialSlot() { return TOKEN_SLOT; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var sid = credential(SID_SLOT);
        if (sid.isEmpty()) return credentialMissing(SID_SLOT);
        var token = credential(TOKEN_SLOT);
        if (token.isEmpty()) return credentialMissing(TOKEN_SLOT);
        var defaultFrom = credential(FROM_SLOT).orElse(null);

        return switch (req.method()) {
            case "send_sms" -> sendMessage(sid.get(), token.get(), req.args(), defaultFrom, false);
            case "send_whatsapp" -> sendMessage(sid.get(), token.get(), req.args(), defaultFrom, true);
            case "voice_call" -> voiceCall(sid.get(), token.get(), req.args(), defaultFrom);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse sendMessage(String sid, String token, Map<String, Object> args,
                                        String defaultFrom, boolean whatsapp) {
        var to = strArg(args, "to");
        var body = strArg(args, "body");
        if (to == null || body == null) {
            return AdapterResponse.fail("invalid_args", "to + body are required", false);
        }
        var from = strArg(args, "from", defaultFrom);
        if (from == null) {
            return AdapterResponse.fail("invalid_args",
                "from is required (set twilio.from_number or pass explicitly)", false);
        }
        var form = new LinkedHashMap<String, String>();
        form.put("To", whatsapp ? "whatsapp:" + to : to);
        form.put("From", whatsapp ? "whatsapp:" + from : from);
        form.put("Body", body);
        return postForm(API_BASE + "/Accounts/" + sid + "/Messages.json", sid, token, form);
    }

    private AdapterResponse voiceCall(String sid, String token, Map<String, Object> args,
                                      String defaultFrom) {
        var to = strArg(args, "to");
        if (to == null) {
            return AdapterResponse.fail("invalid_args", "to is required", false);
        }
        var from = strArg(args, "from", defaultFrom);
        if (from == null) {
            return AdapterResponse.fail("invalid_args",
                "from is required (set twilio.from_number or pass explicitly)", false);
        }
        var url = strArg(args, "url");
        var twiml = strArg(args, "twiml");
        if (url == null && twiml == null) {
            return AdapterResponse.fail("invalid_args",
                "either url or twiml is required for the call instructions", false);
        }
        var form = new LinkedHashMap<String, String>();
        form.put("To", to);
        form.put("From", from);
        if (url != null) form.put("Url", url);
        if (twiml != null) form.put("Twiml", twiml);
        return postForm(API_BASE + "/Accounts/" + sid + "/Calls.json", sid, token, form);
    }

    private AdapterResponse postForm(String url, String sid, String token,
                                     Map<String, String> form) {
        try {
            var body = new StringBuilder();
            form.forEach((k, v) -> {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            });
            var basic = Base64.getEncoder()
                .encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8));
            var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(ADAPTER_TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            var status = resp.statusCode();
            var node = MAPPER.readTree(resp.body());
            if (status >= 200 && status < 300) {
                return AdapterResponse.ok(MAPPER.convertValue(node, Object.class));
            }
            var msg = node.has("message") ? node.get("message").asText()
                : "twilio returned " + status;
            return AdapterResponse.fail("twilio_error_" + status, msg,
                status >= 500 || status == 429);
        } catch (Exception e) {
            log.debug("twilio post failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }
}

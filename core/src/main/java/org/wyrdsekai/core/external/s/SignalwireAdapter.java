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
 * SignalWire adapter
 * ({@code world.signalwire.*}). Drop-in Twilio-Compatibility-API user;
 * Phase S exposes only the SMS surface.
 *
 * <p>Method:</p>
 * <ul>
 *   <li>{@code send_sms({to, body, from?})}</li>
 * </ul>
 *
 * <p>Credentials: {@code signalwire.project_id} +
 * {@code signalwire.api_token} + {@code signalwire.space_url} (the space
 * URL is the per-tenant subdomain, e.g. {@code mycorp.signalwire.com}).
 * Auth is HTTP Basic with project + token, identical to Twilio.</p>
 */
public final class SignalwireAdapter extends PhaseSAdapterSupport implements ExternalAdapter {

    static final String NAMESPACE = "signalwire";
    static final String PROJECT_SLOT = "signalwire.project_id";
    static final String TOKEN_SLOT = "signalwire.api_token";
    static final String SPACE_SLOT = "signalwire.space_url";
    static final String FROM_SLOT = "signalwire.from_number";

    private static final Set<String> METHODS = Set.of("send_sms");

    @Override public String namespace() { return NAMESPACE; }
    @Override public Set<String> capabilities() { return METHODS; }
    @Override public String credentialSlot() { return TOKEN_SLOT; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var project = credential(PROJECT_SLOT);
        if (project.isEmpty()) return credentialMissing(PROJECT_SLOT);
        var token = credential(TOKEN_SLOT);
        if (token.isEmpty()) return credentialMissing(TOKEN_SLOT);
        var space = credential(SPACE_SLOT);
        if (space.isEmpty()) return credentialMissing(SPACE_SLOT);
        var defaultFrom = credential(FROM_SLOT).orElse(null);

        if ("send_sms".equals(req.method())) {
            return sendSms(project.get(), token.get(), space.get(), req.args(), defaultFrom);
        }
        return AdapterResponse.fail("unknown_method", req.method(), false);
    }

    private AdapterResponse sendSms(String project, String token, String space,
                                    Map<String, Object> args, String defaultFrom) {
        var to = strArg(args, "to");
        var body = strArg(args, "body");
        if (to == null || body == null) {
            return AdapterResponse.fail("invalid_args", "to + body are required", false);
        }
        var from = strArg(args, "from", defaultFrom);
        if (from == null) {
            return AdapterResponse.fail("invalid_args",
                "from is required (set signalwire.from_number or pass explicitly)", false);
        }

        var spaceHost = space.startsWith("http") ? space : "https://" + space;
        var url = spaceHost + "/api/laml/2010-04-01/Accounts/" + project + "/Messages.json";
        var form = new LinkedHashMap<String, String>();
        form.put("To", to);
        form.put("From", from);
        form.put("Body", body);

        try {
            var formStr = new StringBuilder();
            form.forEach((k, v) -> {
                if (formStr.length() > 0) formStr.append('&');
                formStr.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            });
            var basic = Base64.getEncoder()
                .encodeToString((project + ":" + token).getBytes(StandardCharsets.UTF_8));
            var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(ADAPTER_TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formStr.toString()))
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            var status = resp.statusCode();
            var node = MAPPER.readTree(resp.body());
            if (status >= 200 && status < 300) {
                return AdapterResponse.ok(MAPPER.convertValue(node, Object.class));
            }
            var msg = node.has("message") ? node.get("message").asText()
                : "signalwire returned " + status;
            return AdapterResponse.fail("signalwire_error_" + status, msg,
                status >= 500 || status == 429);
        } catch (Exception e) {
            log.debug("signalwire post failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }
}

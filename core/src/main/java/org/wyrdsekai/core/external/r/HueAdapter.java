package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Philips Hue Bridge (local network).
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code list_lights()} — GET /api/&lt;user&gt;/lights.</li>
 *   <li>{@code set_state(lightId, state)} — PUT /api/&lt;user&gt;/lights/&lt;id&gt;/state.</li>
 * </ul>
 *
 * <p>Hue Bridges sit on the household LAN, so {@code hue.bridge_ip} is the
 * URL slot and {@code hue.username} is the bridge-issued API key
 * ({@code credentialSlot}). Both must be populated for any call.</p>
 */
public final class HueAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "hue";

    private final HttpAdapterSupport http;

    public HueAdapter() { this(new HttpAdapterSupport()); }

    public HueAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("list_lights", "set_state"); }

    @Override public String credentialSlot() { return "hue.username"; }

    public static String bridgeIpSlot() { return "hue.bridge_ip"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        var ip = CredentialResolver.get().resolve(bridgeIpSlot());
        if (ip.isEmpty()) return http.missingCredential(bridgeIpSlot());
        var user = CredentialResolver.get().resolve(credentialSlot());
        if (user.isEmpty()) return http.missingCredential(credentialSlot());
        var base = ip.get().startsWith("http") ? ip.get() : "http://" + ip.get();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return switch (request.method()) {
            case "list_lights" -> listLights(base, user.get());
            case "set_state" -> setState(base, user.get(), request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse listLights(String base, String user) {
        var req = http.reqBuilder(URI.create(base + "/api/" + user + "/lights"))
            .GET()
            .build();
        return http.execute(req, raw -> Map.of("lights", http.parseJson(raw)));
    }

    private AdapterResponse setState(String base, String user, AdapterRequest request) {
        var args = request.args();
        var lightId = String.valueOf(args.get("lightId"));
        if (lightId == null || lightId.isBlank() || "null".equals(lightId)) return http.missingArg("lightId");
        var state = args.get("state");
        if (!(state instanceof Map<?, ?> sm)) return http.missingArg("state");
        var body = new LinkedHashMap<String, Object>();
        for (var e : sm.entrySet()) {
            if (e.getKey() instanceof String k) body.put(k, e.getValue());
        }
        var req = http.reqBuilder(URI.create(base + "/api/" + user + "/lights/" + lightId + "/state"))
            .header("content-type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> Map.of("ok", true, "result", http.parseJson(raw)));
    }
}

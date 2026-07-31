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
 * Home Assistant REST API.
 *
 * <p>Namespaced as {@code hass} (per the task brief / common community
 * shorthand); the spec uses {@code ha}. Both wildcards are catalogued.</p>
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code list_entities(filter?)} — GET /api/states.</li>
 *   <li>{@code get_state(entityId)} — GET /api/states/&lt;id&gt;.</li>
 *   <li>{@code call_service(domain, service, data?)} — POST /api/services/&lt;d&gt;/&lt;s&gt;.</li>
 * </ul>
 */
public final class HomeAssistantAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "hass";

    private final HttpAdapterSupport http;

    public HomeAssistantAdapter() { this(new HttpAdapterSupport()); }

    public HomeAssistantAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("list_entities", "get_state", "call_service");
    }

    @Override public String credentialSlot() { return "hass.token"; }

    /** Companion slot — base URL of the user's HA instance. */
    public static String urlSlot() { return "hass.url"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        var base = CredentialResolver.get().resolve(urlSlot());
        if (base.isEmpty()) return http.missingCredential(urlSlot());
        var token = CredentialResolver.get().resolve(credentialSlot());
        if (token.isEmpty()) return http.missingCredential(credentialSlot());
        var b = base.get().endsWith("/") ? base.get().substring(0, base.get().length() - 1) : base.get();
        return switch (request.method()) {
            case "list_entities" -> listEntities(b, token.get(), request);
            case "get_state" -> getState(b, token.get(), request);
            case "call_service" -> callService(b, token.get(), request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse listEntities(String base, String token, AdapterRequest request) {
        var req = http.reqBuilder(URI.create(base + "/api/states"))
            .header("authorization", "Bearer " + token)
            .GET()
            .build();
        return http.execute(req, raw -> Map.of("entities", http.parseJson(raw)));
    }

    private AdapterResponse getState(String base, String token, AdapterRequest request) {
        var entityId = (String) request.args().get("entityId");
        if (entityId == null || entityId.isBlank()) return http.missingArg("entityId");
        var req = http.reqBuilder(URI.create(base + "/api/states/" + entityId))
            .header("authorization", "Bearer " + token)
            .GET()
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("state", parsed.get("state"));
            out.put("attributes", parsed.getOrDefault("attributes", Map.of()));
            out.put("lastChanged", parsed.get("last_changed"));
            out.put("raw", parsed);
            return out;
        });
    }

    private AdapterResponse callService(String base, String token, AdapterRequest request) {
        var args = request.args();
        var domain = (String) args.get("domain");
        if (domain == null || domain.isBlank()) return http.missingArg("domain");
        var service = (String) args.get("service");
        if (service == null || service.isBlank()) return http.missingArg("service");
        var data = args.get("data");
        var bodyMap = new LinkedHashMap<String, Object>();
        if (data instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String k) bodyMap.put(k, e.getValue());
            }
        }
        var req = http.reqBuilder(URI.create(base + "/api/services/" + domain + "/" + service))
            .header("authorization", "Bearer " + token)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(bodyMap)))
            .build();
        return http.execute(req, raw -> Map.of(
            "ok", true,
            "result", http.parseJson(raw)
        ));
    }
}

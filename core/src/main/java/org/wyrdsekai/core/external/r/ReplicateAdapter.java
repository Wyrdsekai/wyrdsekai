package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Replicate (image+video gen).
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code run(model, input, opts?)} — POST /v1/predictions; if
 *       {@code wait:true} we still return the prediction id immediately and
 *       let the caller poll {@code status}, since long-poll behaviour can
 *       collide with the 30s adapter timeout.</li>
 *   <li>{@code status(predictionId)} — GET /v1/predictions/&lt;id&gt;.</li>
 * </ul>
 */
public final class ReplicateAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "replicate";

    private static final String DEFAULT_BASE = "https://api.replicate.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public ReplicateAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public ReplicateAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("run", "status"); }

    @Override public String credentialSlot() { return "replicate.token"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "run" -> run(request);
            case "status" -> status(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse run(AdapterRequest request) {
        var args = request.args();
        var model = (String) args.get("model");
        if (model == null || model.isBlank()) return http.missingArg("model");
        var input = args.get("input");
        if (input == null) return http.missingArg("input");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        var body = new LinkedHashMap<String, Object>();
        body.put("version", model);
        body.put("input", input);
        var req = http.reqBuilder(URI.create(baseUrl + "/v1/predictions"))
            .header("authorization", "Token " + key.get())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("predictionId", parsed.get("id"));
            out.put("status", parsed.get("status"));
            out.put("output", parsed.get("output"));
            out.put("raw", parsed);
            return out;
        });
    }

    private AdapterResponse status(AdapterRequest request) {
        var id = (String) request.args().get("predictionId");
        if (id == null || id.isBlank()) return http.missingArg("predictionId");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        var req = http.reqBuilder(URI.create(baseUrl + "/v1/predictions/" + id))
            .header("authorization", "Token " + key.get())
            .GET()
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("status", parsed.get("status"));
            out.put("output", parsed.get("output"));
            out.put("logs", parsed.get("logs"));
            out.put("raw", parsed);
            return out;
        });
    }
}

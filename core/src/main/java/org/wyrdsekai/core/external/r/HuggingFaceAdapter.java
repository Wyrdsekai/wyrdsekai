package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Hugging Face Inference API bypass.
 *
 * <p>Namespaced as {@code hf} for short item-script ergonomics; the
 * canonical service name is "huggingface" and both wildcards
 * ({@code hf.*} and {@code huggingface.*}) are catalogued.</p>
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code inference(model, inputs, opts?)} — POST to inference endpoint
 *       (model id determines task; payload is passthrough).</li>
 *   <li>{@code search_models(query, opts?)} — read-only model search.</li>
 * </ul>
 */
public final class HuggingFaceAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "hf";

    private static final String DEFAULT_INFERENCE_BASE = "https://api-inference.huggingface.co";
    private static final String DEFAULT_HUB_BASE = "https://huggingface.co";

    private final HttpAdapterSupport http;
    private final String inferenceBase;
    private final String hubBase;

    public HuggingFaceAdapter() { this(new HttpAdapterSupport(), DEFAULT_INFERENCE_BASE, DEFAULT_HUB_BASE); }

    public HuggingFaceAdapter(HttpAdapterSupport http, String inferenceBase, String hubBase) {
        this.http = http;
        this.inferenceBase = inferenceBase == null ? DEFAULT_INFERENCE_BASE : inferenceBase;
        this.hubBase = hubBase == null ? DEFAULT_HUB_BASE : hubBase;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("inference", "search_models"); }

    @Override public String credentialSlot() { return "huggingface.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "inference" -> inference(request);
            case "search_models" -> searchModels(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse inference(AdapterRequest request) {
        var args = request.args();
        var model = (String) args.get("model");
        if (model == null || model.isBlank()) return http.missingArg("model");
        var inputs = args.get("inputs");
        if (inputs == null) return http.missingArg("inputs");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        var body = new LinkedHashMap<String, Object>();
        body.put("inputs", inputs);
        var opts = args.get("opts");
        if (opts instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String k) body.put(k, e.getValue());
            }
        }
        var url = inferenceBase + "/models/" + model;
        var req = http.reqBuilder(URI.create(url))
            .header("authorization", "Bearer " + key.get())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> Map.of("output", http.parseJson(raw)));
    }

    private AdapterResponse searchModels(AdapterRequest request) {
        var args = request.args();
        var query = (String) args.get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");
        var url = hubBase + "/api/models?search="
            + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=20";
        var rb = http.reqBuilder(URI.create(url)).GET();
        // search_models accepts unauthenticated calls; attach token if present.
        http.resolveCredential(credentialSlot()).ifPresent(k ->
            rb.header("authorization", "Bearer " + k));
        return http.execute(rb.build(), raw -> Map.of("models", http.parseJson(raw)));
    }
}

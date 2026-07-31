package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google AI Studio (Gemini) bypass.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code complete(model, prompt|contents, opts?)} — generateContent.</li>
 *   <li>{@code vision(model, contents, opts?)} — generateContent with inline image parts.</li>
 * </ul>
 */
public final class GeminiAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "gemini";

    private static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public GeminiAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public GeminiAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("complete", "vision"); }

    @Override public String credentialSlot() { return "gemini.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "complete", "vision" -> generate(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse generate(AdapterRequest request) {
        var args = request.args();
        var model = (String) args.get("model");
        if (model == null || model.isBlank()) return http.missingArg("model");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());

        var body = new LinkedHashMap<String, Object>();
        var contents = args.get("contents");
        if (contents == null) {
            // Convenience: accept a single prompt string and wrap.
            var prompt = args.get("prompt");
            if (prompt == null) return http.missingArg("prompt or contents");
            contents = List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", prompt))
            ));
        }
        body.put("contents", contents);
        var opts = args.get("opts");
        if (opts instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String k && !"contents".equals(k)) {
                    body.put(k, e.getValue());
                }
            }
        }
        var url = baseUrl + "/v1beta/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8)
            + ":generateContent?key=" + URLEncoder.encode(key.get(), StandardCharsets.UTF_8);
        var req = http.reqBuilder(URI.create(url))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            var candidates = parsed.get("candidates");
            if (candidates instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> c
                && c.get("content") instanceof Map<?, ?> content
                && content.get("parts") instanceof List<?> parts) {
                var sb = new StringBuilder();
                for (var p : parts) {
                    if (p instanceof Map<?, ?> pm && pm.get("text") instanceof String t) sb.append(t);
                }
                out.put("text", sb.toString());
            }
            out.put("usage", parsed.get("usageMetadata"));
            out.put("raw", parsed);
            return out;
        });
    }
}

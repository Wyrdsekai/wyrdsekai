package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * direct Anthropic API bypass.
 *
 * <p>Distinct from {@code world.llm.complete} (Phase A2) which routes through
 * Wyrdsekai's own InferenceRouter. Items reaching for {@code world.anthropic.complete}
 * do so deliberately — the manifest declaration {@code anthropic.complete}
 * causes the install-prompt to call out direct-vendor egress.</p>
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code complete(model, messages, opts?)} — Tier 5; messages API.</li>
 *   <li>{@code vision(model, messages, opts?)} — passthrough; messages with image
 *       content blocks already covered by Anthropic messages API.</li>
 * </ul>
 */
public final class AnthropicAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "anthropic";

    private static final String DEFAULT_BASE = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public AnthropicAdapter() {
        this(new HttpAdapterSupport(), DEFAULT_BASE);
    }

    public AnthropicAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("complete", "vision");
    }

    @Override public String credentialSlot() { return "anthropic.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "complete", "vision" -> messages(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse messages(AdapterRequest request) {
        var args = request.args();
        var model = (String) args.get("model");
        if (model == null || model.isBlank()) return http.missingArg("model");
        var messages = args.get("messages");
        if (messages == null) return http.missingArg("messages");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages);
        var maxTokens = args.containsKey("max_tokens") ? args.get("max_tokens") : 1024;
        body.put("max_tokens", maxTokens);
        var opts = args.get("opts");
        if (opts instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String k && !"messages".equals(k) && !"model".equals(k)) {
                    body.put(k, e.getValue());
                }
            }
        }

        var req = http.reqBuilder(URI.create(baseUrl + "/v1/messages"))
            .header("x-api-key", key.get())
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, body2 -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(body2);
            var out = new LinkedHashMap<String, Object>();
            // Extract first text block to a friendly "text" field; preserve raw too.
            var content = parsed.get("content");
            if (content instanceof List<?> blocks && !blocks.isEmpty()) {
                var sb = new StringBuilder();
                for (var b : blocks) {
                    if (b instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                        sb.append(m.get("text"));
                    }
                }
                out.put("text", sb.toString());
            }
            out.put("usage", parsed.get("usage"));
            out.put("stop_reason", parsed.get("stop_reason"));
            out.put("raw", parsed);
            return out;
        });
    }
}

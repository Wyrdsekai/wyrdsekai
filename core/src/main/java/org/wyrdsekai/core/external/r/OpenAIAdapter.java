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
 * direct OpenAI API bypass.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code complete(model, messages, opts?)} — chat completions.</li>
 *   <li>{@code vision(model, messages, opts?)} — chat completions with
 *       image_url content blocks (passthrough — caller assembles content).</li>
 *   <li>{@code embed(model, input)} — embeddings endpoint.</li>
 *   <li>{@code dalle(prompt, opts?)} — image generation (DALL-E 3).</li>
 * </ul>
 */
public final class OpenAIAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "openai";

    private static final String DEFAULT_BASE = "https://api.openai.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public OpenAIAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public OpenAIAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("complete", "vision", "embed", "dalle");
    }

    @Override public String credentialSlot() { return "openai.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "complete", "vision" -> chatCompletion(request);
            case "embed" -> embed(request);
            case "dalle" -> imageGen(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse chatCompletion(AdapterRequest request) {
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
        var opts = args.get("opts");
        if (opts instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String k) body.put(k, e.getValue());
            }
        }

        var req = http.reqBuilder(URI.create(baseUrl + "/v1/chat/completions"))
            .header("authorization", "Bearer " + key.get())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            var choices = parsed.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first
                && first.get("message") instanceof Map<?, ?> msg) {
                out.put("text", msg.get("content"));
            }
            out.put("usage", parsed.get("usage"));
            out.put("model", parsed.get("model"));
            out.put("raw", parsed);
            return out;
        });
    }

    private AdapterResponse embed(AdapterRequest request) {
        var args = request.args();
        var model = (String) args.get("model");
        if (model == null || model.isBlank()) return http.missingArg("model");
        var input = args.get("input");
        if (input == null) input = args.get("texts");
        if (input == null) return http.missingArg("input");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        var body = Map.<String, Object>of("model", model, "input", input);
        var req = http.reqBuilder(URI.create(baseUrl + "/v1/embeddings"))
            .header("authorization", "Bearer " + key.get())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("vectors", parsed.get("data"));
            out.put("model", parsed.get("model"));
            out.put("usage", parsed.get("usage"));
            return out;
        });
    }

    private AdapterResponse imageGen(AdapterRequest request) {
        var args = request.args();
        var prompt = (String) args.get("prompt");
        if (prompt == null || prompt.isBlank()) return http.missingArg("prompt");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        var body = new LinkedHashMap<String, Object>();
        body.put("model", args.getOrDefault("model", "dall-e-3"));
        body.put("prompt", prompt);
        body.putIfAbsent("size", args.getOrDefault("size", "1024x1024"));
        body.putIfAbsent("n", args.getOrDefault("n", 1));
        var opts = args.get("opts");
        if (opts instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String k) body.put(k, e.getValue());
            }
        }
        var req = http.reqBuilder(URI.create(baseUrl + "/v1/images/generations"))
            .header("authorization", "Bearer " + key.get())
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("images", parsed.get("data"));
            out.put("raw", parsed);
            return out;
        });
    }
}

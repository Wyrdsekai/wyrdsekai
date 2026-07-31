package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * alt-provider translation backed by a
 * LibreTranslate-compatible endpoint (the brief calls out "Lingua" as the
 * alternative provider; LibreTranslate is the canonical self-hostable
 * implementation of that surface).
 *
 * <p>Talks to any LibreTranslate-compatible {@code /translate} endpoint
 * configured in {@code lingua.url} (or default-routed via
 * {@code translate.default_provider}). When neither {@code lingua.url} nor
 * {@code lingua.api_key} are set the adapter falls back to the public
 * {@code https://libretranslate.com} endpoint and tries unauthenticated
 * (which usually requires a key — the upstream returns 403, mapped into
 * {@code upstream_error}).</p>
 */
public final class LinguaAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "lingua";

    private static final String DEFAULT_BASE = "https://libretranslate.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public LinguaAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public LinguaAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("translate"); }

    @Override public String credentialSlot() { return "lingua.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "translate" -> translate(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse translate(AdapterRequest request) {
        var args = request.args();
        var text = (String) args.get("text");
        var target = (String) args.get("targetLang");
        if (text == null || text.isBlank()) return http.missingArg("text");
        if (target == null || target.isBlank()) return http.missingArg("targetLang");
        // LibreTranslate uses lowercase ISO 639-1; default source to "auto".
        var source = (String) args.get("sourceLang");
        if (source == null || source.isBlank()) source = "auto";

        var endpoint = (String) args.get("endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = http.resolveCredential("lingua.url").orElse(baseUrl);
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("q", text);
        body.put("source", source);
        body.put("target", target);
        body.put("format", "text");
        http.resolveCredential(credentialSlot()).ifPresent(k -> body.put("api_key", k));

        var req = http.reqBuilder(URI.create(endpoint + "/translate"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("translated", parsed.get("translatedText"));
            out.put("raw", parsed);
            return out;
        });
    }
}

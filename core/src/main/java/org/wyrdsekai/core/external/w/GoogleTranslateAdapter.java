package org.wyrdsekai.core.external.w;

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
 * Google Cloud Translation API v2.
 *
 * <p>Namespace per the brief is {@code translate}; the spec also mentions
 * {@code googletranslate} as the explicit-vendor form. The brief's
 * {@code world.translate.*} surface is the default-routed dispatcher,
 * but registering an adapter under {@code translate} is the cleanest
 * way to back it for the Google provider until a separate dispatcher
 * lands. Methods: {@code translate}, {@code detect_language}.</p>
 */
public final class GoogleTranslateAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "translate";

    private static final String DEFAULT_BASE = "https://translation.googleapis.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public GoogleTranslateAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public GoogleTranslateAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("translate", "detect_language");
    }

    @Override public String credentialSlot() { return "google.translate.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "translate" -> translate(request);
            case "detect_language" -> detect(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse translate(AdapterRequest request) {
        var args = request.args();
        var text = (String) args.get("text");
        var target = (String) args.get("targetLang");
        if (text == null || text.isBlank()) return http.missingArg("text");
        if (target == null || target.isBlank()) return http.missingArg("targetLang");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());

        var body = new LinkedHashMap<String, Object>();
        body.put("q", text);
        body.put("target", target);
        var sourceLang = (String) args.get("sourceLang");
        if (sourceLang != null && !sourceLang.isBlank()) body.put("source", sourceLang);
        body.put("format", "text");

        var req = http.reqBuilder(URI.create(baseUrl + "/language/translate/v2?key=" + HttpAdapterSupport.urlEncode(key.get())))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var data = parsed.get("data");
            var out = new LinkedHashMap<String, Object>();
            if (data instanceof Map<?, ?> dm) {
                var translations = dm.get("translations");
                if (translations instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?> first) {
                    out.put("translated", first.get("translatedText"));
                    out.put("sourceLang", first.get("detectedSourceLanguage"));
                }
            }
            out.put("raw", parsed);
            return out;
        });
    }

    private AdapterResponse detect(AdapterRequest request) {
        var args = request.args();
        var text = (String) args.get("text");
        if (text == null || text.isBlank()) return http.missingArg("text");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());

        var body = new LinkedHashMap<String, Object>();
        body.put("q", text);
        var req = http.reqBuilder(URI.create(baseUrl + "/language/translate/v2/detect?key=" + HttpAdapterSupport.urlEncode(key.get())))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            var data = parsed.get("data");
            if (data instanceof Map<?, ?> dm) {
                var detections = dm.get("detections");
                if (detections instanceof List<?> outer && !outer.isEmpty()
                        && outer.get(0) instanceof List<?> inner && !inner.isEmpty()
                        && inner.get(0) instanceof Map<?, ?> first) {
                    out.put("lang", first.get("language"));
                    out.put("confidence", first.get("confidence"));
                }
            }
            out.put("raw", parsed);
            return out;
        });
    }
}

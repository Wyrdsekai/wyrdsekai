package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DeepL translation API.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code translate(text, targetLang, sourceLang?, opts?)} — Tier 4.</li>
 *   <li>{@code detect_language(text)} — Tier 4. DeepL has no dedicated detect
 *       endpoint; we translate to {@code EN} and read back the
 *       {@code detected_source_language} field.</li>
 * </ul>
 */
public final class DeepLAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "deepl";

    /** Default to the free-tier endpoint; pro keys use {@code api.deepl.com}. */
    private static final String DEFAULT_BASE = "https://api-free.deepl.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public DeepLAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public DeepLAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("translate", "detect_language");
    }

    @Override public String credentialSlot() { return "deepl.api_key"; }

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

        var sourceLang = (String) args.get("sourceLang");
        var formBody = new StringBuilder();
        formBody.append("text=").append(HttpAdapterSupport.urlEncode(text));
        formBody.append("&target_lang=").append(HttpAdapterSupport.urlEncode(target.toUpperCase()));
        if (sourceLang != null && !sourceLang.isBlank()) {
            formBody.append("&source_lang=").append(HttpAdapterSupport.urlEncode(sourceLang.toUpperCase()));
        }
        var opts = args.get("opts");
        if (opts instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() != null) {
                    formBody.append('&').append(HttpAdapterSupport.urlEncode(k))
                        .append('=').append(HttpAdapterSupport.urlEncode(String.valueOf(e.getValue())));
                }
            }
        }

        var req = http.reqBuilder(URI.create(baseUrl + "/v2/translate"))
            .header("Authorization", "DeepL-Auth-Key " + key.get())
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody.toString()))
            .build();
        return http.execute(req, body -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(body);
            var translations = parsed.get("translations");
            var out = new LinkedHashMap<String, Object>();
            if (translations instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> first) {
                out.put("translated", first.get("text"));
                out.put("sourceLang", first.get("detected_source_language"));
            }
            out.put("raw", parsed);
            return out;
        });
    }

    private AdapterResponse detect(AdapterRequest request) {
        var args = request.args();
        var text = (String) args.get("text");
        if (text == null || text.isBlank()) return http.missingArg("text");
        // Translate to EN to read the detected_source_language field.
        var fwd = new HashMap<String, Object>();
        fwd.put("text", text);
        fwd.put("targetLang", "EN");
        var resp = translate(new AdapterRequest(NAMESPACE, "translate", fwd,
            request.capabilities(), request.itemId()));
        if (!resp.success()) return resp;
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        var out = new LinkedHashMap<String, Object>();
        out.put("lang", data.get("sourceLang"));
        out.put("confidence", 0.9); // DeepL doesn't return confidence; placeholder.
        return AdapterResponse.ok(out);
    }
}

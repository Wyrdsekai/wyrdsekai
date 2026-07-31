package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Fonts catalogue.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code list(opts?)} — paginated subset of the Google Fonts catalogue.</li>
 *   <li>{@code font_info(family)} — metadata for a single family.</li>
 * </ul>
 */
public final class GoogleFontsAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "fonts";

    private static final String DEFAULT_BASE = "https://www.googleapis.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public GoogleFontsAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public GoogleFontsAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("list", "font_info");
    }

    @Override public String credentialSlot() { return "googlefonts.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "list" -> list(request);
            case "font_info" -> fontInfo(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse list(AdapterRequest request) {
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());

        var sort = (String) request.args().getOrDefault("sort", "popularity");
        var url = baseUrl + "/webfonts/v1/webfonts?key=" + HttpAdapterSupport.urlEncode(key.get())
            + "&sort=" + HttpAdapterSupport.urlEncode(sort);
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            var items = parsed.get("items");
            if (items instanceof List<?> list) {
                for (var i : list) {
                    if (i instanceof Map<?, ?> im) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("family", im.get("family"));
                        entry.put("category", im.get("category"));
                        entry.put("variants", im.get("variants"));
                        entry.put("subsets", im.get("subsets"));
                        out.add(entry);
                    }
                }
            }
            return out;
        });
    }

    private AdapterResponse fontInfo(AdapterRequest request) {
        var family = (String) request.args().get("family");
        if (family == null || family.isBlank()) return http.missingArg("family");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());

        var url = baseUrl + "/webfonts/v1/webfonts?key=" + HttpAdapterSupport.urlEncode(key.get())
            + "&family=" + HttpAdapterSupport.urlEncode(family);
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var items = parsed.get("items");
            var out = new LinkedHashMap<String, Object>();
            if (items instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> first) {
                out.put("family", first.get("family"));
                out.put("category", first.get("category"));
                out.put("variants", first.get("variants"));
                out.put("subsets", first.get("subsets"));
                out.put("files", first.get("files"));
                out.put("version", first.get("version"));
            } else {
                out.put("family", family);
                out.put("found", false);
            }
            return out;
        });
    }
}

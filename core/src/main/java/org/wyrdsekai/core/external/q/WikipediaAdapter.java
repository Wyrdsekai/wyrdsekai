package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wikipedia adapter.
 *
 * <p>Exposes {@code world.wikipedia.{search, summary, full_article}}.
 * No credentials required — Wikipedia's public API is open. Items still
 * benefit from a User-Agent identifying themselves per Wikipedia's
 * etiquette.</p>
 */
public final class WikipediaAdapter extends AbstractHttpAdapter {

    @Override public String namespace() { return "wikipedia"; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "summary", "full_article");
    }

    @Override public String credentialSlot() { return ""; }

    @Override protected List<String> defaultDomains() {
        // Lang-prefixed Wikipedia hosts (en., ja., es., …) plus the meta API.
        return List.of("*.wikipedia.org", "wikipedia.org");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        return switch (req.method()) {
            case "search" -> search(req);
            case "summary" -> summary(req);
            case "full_article" -> fullArticle(req);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private static String lang(AdapterRequest req) {
        var l = (String) req.args().get("lang");
        return (l == null || l.isBlank()) ? "en" : l;
    }

    private AdapterResponse search(AdapterRequest req) {
        var query = requireString(req, "query");
        if (query == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var url = "https://" + lang(req) + ".wikipedia.org/w/api.php";
        var params = new LinkedHashMap<String, Object>();
        params.put("action", "query");
        params.put("list", "search");
        params.put("srsearch", query);
        params.put("format", "json");
        params.put("srlimit", req.args().getOrDefault("max", 10));
        return httpGetJson(url, headers(), params);
    }

    private AdapterResponse summary(AdapterRequest req) {
        var title = requireString(req, "title");
        if (title == null) return AdapterResponse.fail("missing_arg", "title required", false);
        var enc = URLEncoder.encode(title.replace(' ', '_'),
            StandardCharsets.UTF_8);
        var url = "https://" + lang(req) + ".wikipedia.org/api/rest_v1/page/summary/" + enc;
        return httpGetJson(url, headers(), Map.of());
    }

    private AdapterResponse fullArticle(AdapterRequest req) {
        var title = requireString(req, "title");
        if (title == null) return AdapterResponse.fail("missing_arg", "title required", false);
        var url = "https://" + lang(req) + ".wikipedia.org/w/api.php";
        var params = new LinkedHashMap<String, Object>();
        params.put("action", "query");
        params.put("prop", "extracts");
        params.put("explaintext", "1");
        params.put("titles", title);
        params.put("format", "json");
        return httpGetJson(url, headers(), params);
    }

    private static Map<String, String> headers() {
        return Map.of(
            "User-Agent", "Wyrdsekai/1.0 (https://wyrdsekai.org; items-api)",
            "Accept", "application/json"
        );
    }
}

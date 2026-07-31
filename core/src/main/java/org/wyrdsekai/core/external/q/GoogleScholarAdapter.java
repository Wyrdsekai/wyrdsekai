package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Scholar adapter (via SerpAPI).
 *
 * <p>Google Scholar has no public API; we route through SerpAPI's
 * {@code engine=google_scholar} endpoint. Without a SerpAPI key the adapter
 * returns {@code credentials_missing} — there is no scrape-fallback.</p>
 *
 * <p>Exposes {@code world.scholar.{search, citations}}. Both reads are
 * Tier 4 but each call costs SerpAPI credits — items should declare
 * {@code rate_limits} to bound spend.</p>
 */
public final class GoogleScholarAdapter extends AbstractHttpAdapter {

    private static final String SERPAPI = "https://serpapi.com/search.json";

    @Override public String namespace() { return "scholar"; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "citations");
    }

    @Override public String credentialSlot() { return "serpapi.key"; }

    @Override protected List<String> defaultDomains() {
        return List.of("serpapi.com");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var key = resolveCredential();
        if (key.isEmpty()) return missingCredentials();
        return switch (req.method()) {
            case "search" -> search(req, key.get());
            case "citations" -> citations(req, key.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse search(AdapterRequest req, String key) {
        var query = requireString(req, "query");
        if (query == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var params = new LinkedHashMap<String, Object>();
        params.put("engine", "google_scholar");
        params.put("q", query);
        params.put("api_key", key);
        if (req.args().get("max") != null) params.put("num", req.args().get("max"));
        return httpGetJson(SERPAPI, Map.of("Accept", "application/json"), params);
    }

    private AdapterResponse citations(AdapterRequest req, String key) {
        var paperId = requireString(req, "paperId");
        if (paperId == null) return AdapterResponse.fail("missing_arg", "paperId required", false);
        var params = new LinkedHashMap<String, Object>();
        params.put("engine", "google_scholar_cite");
        params.put("q", paperId);
        params.put("api_key", key);
        return httpGetJson(SERPAPI, Map.of("Accept", "application/json"), params);
    }
}

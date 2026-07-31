package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wolfram Alpha adapter.
 *
 * <p>Exposes {@code world.wolfram.query}. Uses the Wolfram Alpha
 * "full results" API — the JSON shape comes back under {@code queryresult}.
 * Requires an app id from developer.wolframalpha.com.</p>
 */
public final class WolframAdapter extends AbstractHttpAdapter {

    private static final String BASE = "https://api.wolframalpha.com/v2/query";

    @Override public String namespace() { return "wolfram"; }

    @Override public Set<String> capabilities() {
        return Set.of("query");
    }

    @Override public String credentialSlot() { return "wolfram.app_id"; }

    @Override protected List<String> defaultDomains() {
        return List.of("api.wolframalpha.com");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var appId = resolveCredential();
        if (appId.isEmpty()) return missingCredentials();
        return switch (req.method()) {
            case "query" -> query(req, appId.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse query(AdapterRequest req, String appId) {
        var input = requireString(req, "input");
        if (input == null) return AdapterResponse.fail("missing_arg", "input required", false);
        var params = new LinkedHashMap<String, Object>();
        params.put("appid", appId);
        params.put("input", input);
        params.put("output", "json");
        if (req.args().get("format") != null) params.put("format", req.args().get("format"));
        return httpGetJson(BASE, Map.of("Accept", "application/json"), params);
    }
}

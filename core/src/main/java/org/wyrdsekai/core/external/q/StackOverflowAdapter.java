package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stack Overflow (Stack Exchange API) adapter.
 *
 * <p>Exposes {@code world.stackoverflow.{search, top_answer}}. The Stack
 * Exchange API is open for low-volume reads; an optional {@code key} from
 * stackapps.com raises the rate limit. We resolve it via the
 * {@code stackexchange.api_key} slot and append it as {@code key} when
 * present, otherwise we issue the request anonymously.</p>
 */
public final class StackOverflowAdapter extends AbstractHttpAdapter {

    private static final String BASE = "https://api.stackexchange.com/2.3";

    @Override public String namespace() { return "stackoverflow"; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "top_answer");
    }

    /** Optional — anonymous calls work but are rate-limited. */
    @Override public String credentialSlot() { return "stackexchange.api_key"; }

    @Override protected List<String> defaultDomains() {
        return List.of("api.stackexchange.com");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var key = resolveCredential(); // optional
        return switch (req.method()) {
            case "search" -> search(req, key.orElse(null));
            case "top_answer" -> topAnswer(req, key.orElse(null));
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse search(AdapterRequest req, String apiKey) {
        var query = requireString(req, "query");
        if (query == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var params = new LinkedHashMap<String, Object>();
        params.put("site", req.args().getOrDefault("site", "stackoverflow"));
        params.put("intitle", query);
        params.put("sort", "relevance");
        params.put("order", "desc");
        if (apiKey != null) params.put("key", apiKey);
        return httpGetJson(BASE + "/search", Map.of("Accept", "application/json"), params);
    }

    private AdapterResponse topAnswer(AdapterRequest req, String apiKey) {
        var questionId = requireString(req, "questionId");
        if (questionId == null) return AdapterResponse.fail("missing_arg", "questionId required", false);
        var params = new LinkedHashMap<String, Object>();
        params.put("site", req.args().getOrDefault("site", "stackoverflow"));
        params.put("order", "desc");
        params.put("sort", "votes");
        params.put("filter", "withbody");
        if (apiKey != null) params.put("key", apiKey);
        return httpGetJson(BASE + "/questions/" + questionId + "/answers",
            Map.of("Accept", "application/json"), params);
    }
}

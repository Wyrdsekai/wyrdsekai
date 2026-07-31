package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Indeed job-search adapter.
 *
 * <p>Read-only. Indeed's publisher API requires a publisher key; without
 * one the adapter returns a structured stub.</p>
 */
public final class IndeedAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "indeed"; }

    @Override public Set<String> capabilities() { return Set.of("job_search"); }

    @Override public String credentialSlot() { return "indeed.publisher_id"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        if (!"job_search".equals(request.method())) {
            return AdapterResponse.fail("unknown_method",
                "indeed." + request.method() + " is not supported", false);
        }
        var args = request.args();
        var query = str(args, "query");
        if (query.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "job_search requires {query}", false);
        }
        if (credential().isEmpty()) {
            return stub("credential_missing:indeed.publisher_id");
        }
        return stub("live_not_wired");
    }

    private AdapterResponse stub(String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put("jobs", List.of());
        return AdapterResponse.ok(out);
    }
}

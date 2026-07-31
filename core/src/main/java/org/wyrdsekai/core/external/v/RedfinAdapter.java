package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redfin real-estate listing adapter.
 *
 * <p>Read-only. Redfin doesn't ship a first-party API; this adapter is
 * shaped for the public XML feed + scraped JSON shape with the steward's
 * partner key (where available).</p>
 */
public final class RedfinAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "redfin"; }

    @Override public Set<String> capabilities() { return Set.of("property_search"); }

    @Override public String credentialSlot() { return "redfin.api_key"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        if (!"property_search".equals(request.method())) {
            return AdapterResponse.fail("unknown_method",
                "redfin." + request.method() + " is not supported", false);
        }
        var args = request.args();
        var query = str(args, "query");
        if (query.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "property_search requires {query}", false);
        }
        if (credential().isEmpty()) {
            return stub("credential_missing:redfin.api_key");
        }
        return stub("live_not_wired");
    }

    private AdapterResponse stub(String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put("properties", List.of());
        return AdapterResponse.ok(out);
    }
}

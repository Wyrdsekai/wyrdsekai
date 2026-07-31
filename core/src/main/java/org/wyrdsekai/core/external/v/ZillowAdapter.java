package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zillow real-estate adapter.
 *
 * <p>Read-only — {@code property_search} and {@code value_estimate}
 * (Zestimate). Zillow's official API has been deprecated; partner
 * Bridge Interactive endpoints take its place. The adapter ships the
 * read shape and stubs gracefully without credentials.</p>
 */
public final class ZillowAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "zillow"; }

    @Override public Set<String> capabilities() {
        return Set.of("property_search", "value_estimate");
    }

    @Override public String credentialSlot() { return "zillow.api_key"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        var args = request.args();
        return switch (request.method()) {
            case "property_search" -> propertySearch(args);
            case "value_estimate" -> valueEstimate(args);
            default -> AdapterResponse.fail("unknown_method",
                "zillow." + request.method() + " is not supported", false);
        };
    }

    private AdapterResponse propertySearch(Map<String, Object> args) {
        var query = str(args, "query");
        if (query.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "property_search requires {query}", false);
        }
        if (credential().isEmpty()) {
            return stub("properties", "credential_missing:zillow.api_key", true);
        }
        return stub("properties", "live_not_wired", true);
    }

    private AdapterResponse valueEstimate(Map<String, Object> args) {
        var addr = str(args, "address");
        if (addr.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "value_estimate requires {address}", false);
        }
        if (credential().isEmpty()) {
            return stub("estimate", "credential_missing:zillow.api_key", false);
        }
        return stub("estimate", "live_not_wired", false);
    }

    private AdapterResponse stub(String key, String reason, boolean asList) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put(key, asList ? List.of() : Map.of());
        return AdapterResponse.ok(out);
    }
}

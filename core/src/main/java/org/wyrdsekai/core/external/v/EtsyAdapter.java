package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Etsy listing-search adapter.
 *
 * <p>Read-only. Etsy v3 OpenAPI requires an API key (no OAuth needed for
 * public listings).</p>
 */
public final class EtsyAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "etsy"; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "listing_lookup");
    }

    @Override public String credentialSlot() { return "etsy.api_key"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        var args = request.args();
        return switch (request.method()) {
            case "search" -> search(args);
            case "listing_lookup" -> listingLookup(args);
            default -> AdapterResponse.fail("unknown_method",
                "etsy." + request.method() + " is not supported", false);
        };
    }

    private AdapterResponse search(Map<String, Object> args) {
        var query = str(args, "query");
        if (query.isBlank()) {
            return AdapterResponse.fail("bad_request", "search requires {query}", false);
        }
        if (credential().isEmpty()) {
            return stub("listings", "credential_missing:etsy.api_key", true);
        }
        return stub("listings", "live_not_wired", true);
    }

    private AdapterResponse listingLookup(Map<String, Object> args) {
        var listingId = str(args, "listing_id");
        if (listingId.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "listing_lookup requires {listing_id}", false);
        }
        if (credential().isEmpty()) {
            return stub("listing", "credential_missing:etsy.api_key", false);
        }
        return stub("listing", "live_not_wired", false);
    }

    private AdapterResponse stub(String key, String reason, boolean asList) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put(key, asList ? List.of() : Map.of());
        return AdapterResponse.ok(out);
    }
}

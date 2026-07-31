package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Airbnb listing-search adapter.
 *
 * <p>Read-only. Airbnb's public API is limited; this adapter is designed
 * for the partner-API or scraped-feed-via-allowlisted-domain shape and
 * returns a structured stub when neither is available.</p>
 */
public final class AirbnbAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "airbnb"; }

    @Override public Set<String> capabilities() { return Set.of("listing_search"); }

    @Override public String credentialSlot() { return "airbnb.api_key"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        if (!"listing_search".equals(request.method())) {
            return AdapterResponse.fail("unknown_method",
                "airbnb." + request.method() + " is not supported", false);
        }
        var args = request.args();
        var location = str(args, "location");
        if (location.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "listing_search requires {location}", false);
        }
        if (credential().isEmpty()) {
            return stub("credential_missing:airbnb.api_key");
        }
        return stub("live_not_wired");
    }

    private AdapterResponse stub(String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put("listings", List.of());
        return AdapterResponse.ok(out);
    }
}

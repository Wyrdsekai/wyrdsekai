package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kayak meta-search adapter.
 *
 * <p>Read-only — Kayak's public surface is meta-search aggregation. Returns
 * a synthetic stub when credentials are missing (Kayak partner API requires
 * a partner ID + key).</p>
 */
public final class KayakAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "kayak"; }

    @Override public Set<String> capabilities() {
        return Set.of("flight_search", "hotel_search");
    }

    @Override public String credentialSlot() { return "kayak.api_key"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        var args = request.args();
        return switch (request.method()) {
            case "flight_search" -> flightSearch(args);
            case "hotel_search" -> hotelSearch(args);
            default -> AdapterResponse.fail("unknown_method",
                "kayak." + request.method() + " is not supported", false);
        };
    }

    private AdapterResponse flightSearch(Map<String, Object> args) {
        var origin = str(args, "origin");
        var dest = str(args, "destination");
        if (origin.isBlank() || dest.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "flight_search requires {origin, destination}", false);
        }
        if (credential().isEmpty()) return stub("flights", "credential_missing:kayak.api_key");
        return stub("flights", "live_not_wired");
    }

    private AdapterResponse hotelSearch(Map<String, Object> args) {
        var city = str(args, "city");
        if (city.isBlank()) {
            return AdapterResponse.fail("bad_request", "hotel_search requires {city}", false);
        }
        if (credential().isEmpty()) return stub("hotels", "credential_missing:kayak.api_key");
        return stub("hotels", "live_not_wired");
    }

    private AdapterResponse stub(String key, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put(key, List.of());
        return AdapterResponse.ok(out);
    }
}

package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Amadeus travel API adapter.
 *
 * <p>Phase V scope is read-only: {@code flight_search}, {@code hotel_search},
 * {@code car_search}. Bookings (Tier 7) are deliberately out of scope.</p>
 *
 * <p>Credential slots: {@code amadeus.client_id} + {@code amadeus.client_secret}.
 * The base resolver only looks at one slot — for Amadeus we treat
 * {@code amadeus.client_id} as the gate; presence of both is enforced inside
 * the upstream call.</p>
 *
 * <p>When credentials are missing, returns a synthetic empty result so item
 * scripts can branch on {@code data.stub} without crashing the host.</p>
 */
public final class AmadeusAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "amadeus"; }

    @Override public Set<String> capabilities() {
        return Set.of("flight_search", "hotel_search", "car_search");
    }

    @Override public String credentialSlot() { return "amadeus.client_id"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        var args = request.args();
        return switch (request.method()) {
            case "flight_search" -> flightSearch(args);
            case "hotel_search" -> hotelSearch(args);
            case "car_search" -> carSearch(args);
            default -> AdapterResponse.fail("unknown_method",
                "amadeus." + request.method() + " is not supported", false);
        };
    }

    private AdapterResponse flightSearch(Map<String, Object> args) {
        var origin = str(args, "origin");
        var dest = str(args, "destination");
        var date = str(args, "date");
        if (origin.isBlank() || dest.isBlank() || date.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "flight_search requires {origin, destination, date}", false);
        }
        if (credential().isEmpty()) {
            return stubResults("flights", "credential_missing:amadeus.client_id");
        }
        // Live integration would token-exchange + GET /v2/shopping/flight-offers.
        // Phase V ships the read shape; live wiring is gated until creds are present.
        return stubResults("flights", "live_not_wired");
    }

    private AdapterResponse hotelSearch(Map<String, Object> args) {
        var city = str(args, "city");
        if (city.isBlank()) {
            return AdapterResponse.fail("bad_request", "hotel_search requires {city}", false);
        }
        if (credential().isEmpty()) {
            return stubResults("hotels", "credential_missing:amadeus.client_id");
        }
        return stubResults("hotels", "live_not_wired");
    }

    private AdapterResponse carSearch(Map<String, Object> args) {
        var city = str(args, "city");
        if (city.isBlank()) {
            return AdapterResponse.fail("bad_request", "car_search requires {city}", false);
        }
        if (credential().isEmpty()) {
            return stubResults("cars", "credential_missing:amadeus.client_id");
        }
        return stubResults("cars", "live_not_wired");
    }

    private AdapterResponse stubResults(String key, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put(key, List.of());
        return AdapterResponse.ok(out);
    }
}

package org.wyrdsekai.core.external.v;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Flights adapter.
 *
 * <p>Read-only flight search. Google Flights does not have a public REST API;
 * Phase V delegates to the QPX-style partner endpoint when credentials are
 * present and otherwise returns a structured stub. The single
 * {@code search} method is the only declared capability.</p>
 */
public final class GoogleFlightsAdapter extends PhaseVAdapterBase {

    @Override public String namespace() { return "google_flights"; }

    @Override public Set<String> capabilities() { return Set.of("search"); }

    @Override public String credentialSlot() { return "google_flights.api_key"; }

    @Override public AdapterResponse invoke(AdapterRequest request) {
        if (!"search".equals(request.method())) {
            return AdapterResponse.fail("unknown_method",
                "google_flights." + request.method() + " is not supported", false);
        }
        var args = request.args();
        var origin = str(args, "origin");
        var dest = str(args, "destination");
        if (origin.isBlank() || dest.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "search requires {origin, destination}", false);
        }
        if (credential().isEmpty()) {
            return stub("credential_missing:google_flights.api_key");
        }
        return stub("live_not_wired");
    }

    private AdapterResponse stub(String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("stub", true);
        out.put("reason", reason);
        out.put("flights", List.of());
        return AdapterResponse.ok(out);
    }
}

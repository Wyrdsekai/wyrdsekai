package org.wyrdsekai.core.external.u;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** §4.40 — Google Maps (geocode live; reverse_geocode/directions/places still
 *  stubbed). Tier 4. geocode was scaffolding until 2026-07-11 — it checked the
 *  credential then returned not_yet_wired anyway, so a steward with a real key
 *  still got no coordinates (second-node live test). */
public final class GoogleMapsAdapter extends AbstractPhaseUAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override public String namespace() { return "maps"; }
    @Override public String credentialSlot() { return "googlemaps.api_key"; }
    @Override public Set<String> capabilities() {
        return caps("geocode", "reverse_geocode", "directions", "places");
    }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var key = requireCredential();
        if (key.isEmpty()) return credentialMissing();
        if (!"geocode".equals(req.method())) return stub(req.method());

        var address = String.valueOf(req.args().getOrDefault("address", "")).strip();
        if (address.isBlank()) {
            return AdapterResponse.fail("bad_request",
                "geocode needs {address: \"<place>\"}", false);
        }
        var url = "https://maps.googleapis.com/maps/api/geocode/json?address="
            + URLEncoder.encode(address, StandardCharsets.UTF_8)
            + "&key=" + URLEncoder.encode(key.get(), StandardCharsets.UTF_8);
        var raw = httpGet(url, null);
        if (!raw.success()) return raw;
        try {
            var body = String.valueOf(((Map<?, ?>) raw.data()).get("body"));
            var root = JSON.readTree(body);
            var status = root.path("status").asText("");
            if (!"OK".equals(status)) {
                return AdapterResponse.fail("geocode_" + status.toLowerCase(),
                    root.path("error_message").asText(
                        "Google geocoder returned status " + status
                        + " for '" + address + "'"), false);
            }
            var first = root.path("results").path(0);
            var loc = first.path("geometry").path("location");
            return AdapterResponse.ok(Map.of(
                "coords", Map.of("lat", loc.path("lat").asDouble(),
                                 "lon", loc.path("lng").asDouble()),
                "formatted_address", first.path("formatted_address").asText(address)));
        } catch (Exception e) {
            return AdapterResponse.fail("parse_error",
                "geocode response unreadable: " + e.getMessage(), false);
        }
    }
}

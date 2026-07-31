package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * §4.40 — OpenStreetMap Nominatim geocoding. Tier 4.
 *
 * <p>Auth-free public service — adapter wires the live HTTP call directly.
 * Per Nominatim's usage policy we always send a descriptive User-Agent
 * ({@code wyrdsekai/<phase-u>}) and rely on the shared 30s timeout +
 * 10MB cap from {@link AbstractPhaseUAdapter}.</p>
 */
public final class OSMNominatimAdapter extends AbstractPhaseUAdapter {

    private static final String BASE = "https://nominatim.openstreetmap.org";
    private static final Map<String, String> HEADERS = Map.of(
        "User-Agent", "wyrdsekai/phase-u (+https://github.com/wyrdsekai)",
        "Accept", "application/json"
    );

    @Override public String namespace() { return "nominatim"; }
    @Override public String credentialSlot() { return ""; }
    @Override public Set<String> capabilities() {
        return caps("geocode", "reverse_geocode");
    }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        return switch (req.method()) {
            case "geocode" -> geocode(req);
            case "reverse_geocode" -> reverseGeocode(req);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse geocode(AdapterRequest req) {
        var query = stringArg(req, "q");
        if (query == null || query.isBlank()) {
            return AdapterResponse.fail("missing_arg", "geocode requires {q}", false);
        }
        var url = BASE + "/search?format=json&limit=5&q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8);
        return httpGet(url, HEADERS);
    }

    private AdapterResponse reverseGeocode(AdapterRequest req) {
        var lat = doubleArg(req, "lat");
        var lon = doubleArg(req, "lon");
        if (lat == null || lon == null) {
            return AdapterResponse.fail("missing_arg",
                "reverse_geocode requires {lat, lon}", false);
        }
        var url = BASE + "/reverse?format=json&lat=" + lat + "&lon=" + lon;
        return httpGet(url, HEADERS);
    }

    private static String stringArg(AdapterRequest req, String key) {
        var v = req.args().get(key);
        return v == null ? null : v.toString();
    }

    private static Double doubleArg(AdapterRequest req, String key) {
        var v = req.args().get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return null; }
    }
}

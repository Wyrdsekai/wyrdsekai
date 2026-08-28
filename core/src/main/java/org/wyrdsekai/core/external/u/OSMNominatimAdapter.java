package org.wyrdsekai.core.external.u;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
    @Override public Map<String, List<String>> resultKeys() {
        return Map.of("geocode", GEOCODE_KEYS, "reverse_geocode", REVERSE_KEYS);
    }

    static final List<String> GEOCODE_KEYS = List.of("lat", "lon", "display_name", "matches", "text");
    static final List<String> REVERSE_KEYS = List.of("lat", "lon", "display_name", "text");

    /** Auth-free and fully wired — both methods reach the live service. */
    @Override public Set<String> wiredCapabilities() { return caps("geocode", "reverse_geocode"); }

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
        return digest(httpGet(url, HEADERS), query);
    }

    /**
     * Return {@code {lat, lon, display_name, matches}} — not a raw HTTP envelope.
     *
     * <h2>Why this changed</h2>
     * {@code httpGet} hands back {@code {status, body}} with {@code body} a raw JSON
     * STRING, while its sibling {@link OpenWeatherAdapter} returns a parsed digest "so
     * the 9B can voice it directly". Two adapters in the same family, two shapes.
     *
     * <p>Live 2026-08-21 that inconsistency cost a working tool. The first item ever
     * written against the generated adapter surface geocoded a city and then read
     * {@code g.data.lat} — exactly as the contract's own example showed — and got
     * undefined, because the real answer was a JSON string it would have had to parse
     * out of an array. The item reported "could not fetch weather" and the steward had
     * no way to know why.
     *
     * <p>Geocoding has one obvious answer — a coordinate — so returning one is both what
     * a caller wants and what the documentation already promised.
     */
    private AdapterResponse digest(AdapterResponse raw, String query) {
        if (!raw.success()) return raw;
        Object body = raw.data() instanceof Map<?, ?> m ? m.get("body") : null;
        return digestSearch(body == null ? null : String.valueOf(body), query);
    }

    /** Package-visible so the shape contract can be tested against a canned upstream body. */
    static AdapterResponse digestSearch(String body, String query) {
        try {
            var arr = JSON.readTree(body == null ? "[]" : body);
            if (!arr.isArray() || arr.isEmpty()) {
                return AdapterResponse.fail("no_match",
                    "nothing matched '" + query + "'", false);
            }
            var first = arr.get(0);
            var out = new LinkedHashMap<String, Object>();
            out.put("lat", Double.parseDouble(first.path("lat").asText("0")));
            out.put("lon", Double.parseDouble(first.path("lon").asText("0")));
            out.put("display_name", first.path("display_name").asText(""));
            out.put("matches", arr.size());
            out.put("text", first.path("display_name").asText(""));
            return AdapterResponse.ok(out);
        } catch (Exception e) {
            return AdapterResponse.fail("bad_upstream",
                "could not read the geocoder's answer: " + e, false);
        }
    }

    private AdapterResponse reverseGeocode(AdapterRequest req) {
        var lat = doubleArg(req, "lat");
        var lon = doubleArg(req, "lon");
        if (lat == null || lon == null) {
            return AdapterResponse.fail("missing_arg",
                "reverse_geocode requires {lat, lon}", false);
        }
        var url = BASE + "/reverse?format=json&lat=" + lat + "&lon=" + lon;
        // Same digest as geocode. This method sat one below the one fixed on 2026-08-21
        // still returning the raw {status, body} envelope — the fix had been made to the
        // single path the failing item happened to take.
        var raw = httpGet(url, HEADERS);
        if (!raw.success()) return raw;
        try {
            Object body = raw.data() instanceof Map<?, ?> m ? m.get("body") : null;
            var node = JSON.readTree(body == null ? "{}" : String.valueOf(body));
            var out = new LinkedHashMap<String, Object>();
            out.put("lat", lat);
            out.put("lon", lon);
            out.put("display_name", node.path("display_name").asText(""));
            out.put("text", node.path("display_name").asText(""));
            return AdapterResponse.ok(out);
        } catch (Exception e) {
            return AdapterResponse.fail("bad_upstream",
                "could not read the geocoder's answer: " + e, false);
        }
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

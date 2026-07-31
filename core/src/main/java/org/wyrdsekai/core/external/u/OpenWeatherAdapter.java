package org.wyrdsekai.core.external.u;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** §4.41 — OpenWeatherMap (current + 5-day/3-hour forecast live; alerts still
 *  stubbed). Tier 4. Was scaffolding until 2026-07-11 — credential-checked then
 *  not_yet_wired anyway (second-node live test with a real key). Data comes back as a
 *  compact digest, not the raw upstream JSON, so the 9B can voice it directly. */
public final class OpenWeatherAdapter extends AbstractPhaseUAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override public String namespace() { return "openweather"; }
    @Override public String credentialSlot() { return "openweathermap.api_key"; }
    @Override public Set<String> capabilities() {
        return caps("current", "forecast", "alerts");
    }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var key = requireCredential();
        if (key.isEmpty()) return credentialMissing();
        var lat = num(req.args().get("lat"));
        var lon = num(req.args().get("lon"));
        if (lat == null || lon == null) {
            return AdapterResponse.fail("bad_request",
                req.method() + " needs {lat, lon} (geocode the place first)", false);
        }
        return switch (req.method()) {
            case "current" -> current(lat, lon, key.get());
            case "forecast" -> forecast(lat, lon, key.get());
            default -> stub(req.method());
        };
    }

    private AdapterResponse current(double lat, double lon, String key) {
        var raw = httpGet("https://api.openweathermap.org/data/2.5/weather?lat=" + lat
            + "&lon=" + lon + "&units=imperial&appid=" + key, null);
        if (!raw.success()) return raw;
        try {
            var root = parse(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("conditions", root.path("weather").path(0).path("description").asText("?"));
            out.put("temp_f", root.path("main").path("temp").asDouble());
            out.put("feels_like_f", root.path("main").path("feels_like").asDouble());
            out.put("humidity_pct", root.path("main").path("humidity").asInt());
            out.put("wind_mph", root.path("wind").path("speed").asDouble());
            out.put("place", root.path("name").asText(""));
            // Preformatted one-line summary (#31 item 2): GraalJS's
            // JSON.stringify cannot serialize Java host maps (scripts spoke
            // "{"), so scripts read data.text instead of stringifying data.
            out.put("text", currentText(out));
            return AdapterResponse.ok(out);
        } catch (Exception e) {
            return AdapterResponse.fail("parse_error", e.getMessage(), false);
        }
    }

    private AdapterResponse forecast(double lat, double lon, String key) {
        var raw = httpGet("https://api.openweathermap.org/data/2.5/forecast?lat=" + lat
            + "&lon=" + lon + "&units=imperial&appid=" + key, null);
        if (!raw.success()) return raw;
        try {
            var root = parse(raw);
            // Digest the 3-hourly list into per-day hi/lo + dominant conditions.
            var days = new LinkedHashMap<String, double[]>();          // date → [lo, hi]
            var conditions = new LinkedHashMap<String, String>();      // date → midday desc
            for (JsonNode item : root.path("list")) {
                var date = item.path("dt_txt").asText("").split(" ")[0];
                if (date.isBlank()) continue;
                var t = item.path("main").path("temp").asDouble();
                days.merge(date, new double[]{t, t}, (a, b) ->
                    new double[]{Math.min(a[0], t), Math.max(a[1], t)});
                if (item.path("dt_txt").asText("").contains("12:00")) {
                    conditions.put(date,
                        item.path("weather").path(0).path("description").asText("?"));
                }
            }
            var daily = new ArrayList<Map<String, Object>>();
            days.forEach((date, lohi) -> daily.add(Map.of(
                "date", date,
                "low_f", Math.round(lohi[0]),
                "high_f", Math.round(lohi[1]),
                "conditions", conditions.getOrDefault(date, "?"))));
            var place = root.path("city").path("name").asText("");
            var out = new LinkedHashMap<String, Object>();
            out.put("place", place);
            out.put("daily", daily);
            // Preformatted digest (#31 item 2) — see currentText for why.
            out.put("text", forecastText(place, daily));
            return AdapterResponse.ok(out);
        } catch (Exception e) {
            return AdapterResponse.fail("parse_error", e.getMessage(), false);
        }
    }

    /** "clear sky, 71F (feels like 69F), humidity 40%, wind 5 mph — San Jose". */
    static String currentText(Map<String, Object> digest) {
        var sb = new StringBuilder();
        sb.append(digest.getOrDefault("conditions", "?"));
        sb.append(", ").append(Math.round(((Number) digest.getOrDefault("temp_f", 0d)).doubleValue()))
          .append("F (feels like ")
          .append(Math.round(((Number) digest.getOrDefault("feels_like_f", 0d)).doubleValue()))
          .append("F), humidity ").append(digest.getOrDefault("humidity_pct", "?"))
          .append("%, wind ")
          .append(Math.round(((Number) digest.getOrDefault("wind_mph", 0d)).doubleValue()))
          .append(" mph");
        var place = String.valueOf(digest.getOrDefault("place", ""));
        if (!place.isBlank()) sb.append(" — ").append(place);
        return sb.toString();
    }

    /** "San Jose — Sun 7/13: low 58F high 71F, clear sky; Mon 7/14: …". */
    static String forecastText(String place, List<Map<String, Object>> daily) {
        var dayFmt = DateTimeFormatter.ofPattern("EEE M/d", Locale.US);
        var joined = new StringJoiner("; ");
        for (var day : daily) {
            var date = String.valueOf(day.getOrDefault("date", ""));
            String label;
            try {
                label = LocalDate.parse(date).format(dayFmt);
            } catch (Exception e) {
                label = date;    // upstream date not ISO — keep it verbatim
            }
            joined.add(label + ": low " + day.getOrDefault("low_f", "?")
                + "F high " + day.getOrDefault("high_f", "?")
                + "F, " + day.getOrDefault("conditions", "?"));
        }
        var head = place == null || place.isBlank() ? "" : place + " — ";
        return head + joined;
    }

    private JsonNode parse(AdapterResponse raw) throws Exception {
        return JSON.readTree(String.valueOf(((Map<?, ?>) raw.data()).get("body")));
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o != null ? Double.parseDouble(String.valueOf(o)) : null; }
        catch (NumberFormatException e) { return null; }
    }
}

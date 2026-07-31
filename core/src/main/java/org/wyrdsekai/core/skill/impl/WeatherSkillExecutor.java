package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Weather skills via Open-Meteo API (free, no auth).
 * Provides current conditions, multi-day forecast, and weather alerts.
 */
public class WeatherSkillExecutor extends HttpSkillExecutor {

    private static final String DEFAULT_BASE_URL = "https://api.open-meteo.com/v1/forecast";

    public WeatherSkillExecutor() {
        this(DEFAULT_BASE_URL);
    }

    public WeatherSkillExecutor(String baseUrl) {
        super(baseUrl);
        define(SkillDefinition.native_("scrying.weather.current",
            "Current Weather", "Get current weather conditions for a location",
            "scrying-pool",
            List.of(
                SkillParam.required("lat", "number", "Latitude"),
                SkillParam.required("lon", "number", "Longitude")),
            SkillAuth.NONE));

        define(SkillDefinition.native_("scrying.weather.forecast",
            "Weather Forecast", "Get multi-day weather forecast",
            "scrying-pool",
            List.of(
                SkillParam.required("lat", "number", "Latitude"),
                SkillParam.required("lon", "number", "Longitude"),
                SkillParam.optional("days", "number", "Forecast days (1-16)")),
            SkillAuth.NONE));

        define(SkillDefinition.native_("scrying.weather.alerts",
            "Weather Alerts", "Get active weather alerts for a location",
            "scrying-pool",
            List.of(
                SkillParam.required("lat", "number", "Latitude"),
                SkillParam.required("lon", "number", "Longitude")),
            SkillAuth.NONE));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String lat = requireParam(params, "lat");
        String lon = requireParam(params, "lon");
        if (lat == null || lon == null) {
            return SkillResult.error(
                I18n.get("skill.param_required", "lat, lon"),
                0, SkillTier.NATIVE, skillId);
        }

        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "scrying.weather.current" -> executeCurrent(lat, lon, start, skillId, context);
            case "scrying.weather.forecast" -> executeForecast(lat, lon, params, start, skillId, context);
            case "scrying.weather.alerts" -> executeAlerts(lat, lon, start, skillId, context);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeCurrent(String lat, String lon, long start,
                                        String skillId, SkillContext context) {
        String url = baseUrl + "?latitude=" + lat + "&longitude=" + lon
            + "&current=temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m"
            + "&timezone=auto";

        var result = httpGet(url, Map.of(), context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        String body = result.body();
        Double temp = jsonNumber(body, "temperature_2m");
        Double wind = jsonNumber(body, "wind_speed_10m");
        Double humidity = jsonNumber(body, "relative_humidity_2m");
        Double code = jsonNumber(body, "weather_code");
        String condition = weatherCodeToText(code != null ? code.intValue() : -1);

        String output = I18n.get("skill.weather.current", lat, lon,
            temp != null ? String.format("%.1f", temp) : "?", condition);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("temperature_c", temp);
        data.put("condition", condition);
        data.put("wind_speed_kmh", wind);
        data.put("humidity_pct", humidity);
        data.put("weather_code", code != null ? code.intValue() : null);

        return SkillResult.ok(output, data, elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeForecast(String lat, String lon, Map<String, Object> params,
                                         long start, String skillId, SkillContext context) {
        int days = Math.max(1, Math.min(16, intParam(params, "days", 3)));
        String url = baseUrl + "?latitude=" + lat + "&longitude=" + lon
            + "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_sum"
            + "&timezone=auto&forecast_days=" + days;

        var result = httpGet(url, Map.of(), context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        String output = I18n.get("skill.weather.forecast", lat, lon, days + " days");
        return SkillResult.ok(output, Map.of("raw", result.body(), "days", days),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeAlerts(String lat, String lon, long start,
                                       String skillId, SkillContext context) {
        // Open-Meteo doesn't have a dedicated alerts endpoint;
        // use hourly precipitation_probability + weather_code as proxy
        String url = baseUrl + "?latitude=" + lat + "&longitude=" + lon
            + "&hourly=precipitation_probability,weather_code"
            + "&timezone=auto&forecast_hours=24";

        var result = httpGet(url, Map.of(), context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(), Map.of("raw", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    /** Map WMO weather code to human-readable text. */
    private static String weatherCodeToText(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1, 2, 3 -> "Partly cloudy";
            case 45, 48 -> "Foggy";
            case 51, 53, 55 -> "Drizzle";
            case 61, 63, 65 -> "Rain";
            case 66, 67 -> "Freezing rain";
            case 71, 73, 75 -> "Snow";
            case 77 -> "Snow grains";
            case 80, 81, 82 -> "Rain showers";
            case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Unknown";
        };
    }
}

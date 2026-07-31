package org.wyrdsekai.core.oracle.feeds;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.core.oracle.OracleEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Open-Meteo weather feed.
 * Free, no auth, no API key. Hourly forecast + current conditions.
 * https://open-meteo.com/
 */
public final class OpenMeteoFeed implements FeedPoller.FeedSource {

    private static final String API_URL =
        "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s" +
        "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
        "precipitation,weather_code,pressure_msl,wind_speed_10m" +
        "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum," +
        "weather_code&timezone=auto&forecast_days=3";

    private final String latitude;
    private final String longitude;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenMeteoFeed(String latitude, String longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    @Override public String name() { return "open_meteo"; }
    @Override public long intervalSeconds() { return 3600; } // hourly

    @Override
    public List<OracleEvent> poll() throws Exception {
        var url = String.format(API_URL, latitude, longitude);
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET().build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return List.of();

        var json = mapper.readTree(resp.body());
        var events = new ArrayList<OracleEvent>();

        // Current conditions
        var current = json.path("current");
        if (!current.isMissingNode()) {
            var temp = current.path("temperature_2m").asDouble();
            var humidity = current.path("relative_humidity_2m").asDouble();
            var pressure = current.path("pressure_msl").asDouble();
            var precip = current.path("precipitation").asDouble();
            var windSpeed = current.path("wind_speed_10m").asDouble();
            var weatherCode = current.path("weather_code").asInt();

            events.add(new OracleEvent(
                Instant.now(), "weather", "current",
                String.format("temp=%.1f°C humidity=%.0f%% pressure=%.0fhPa precip=%.1fmm wind=%.1fkm/h code=%d",
                    temp, humidity, pressure, precip, windSpeed, weatherCode),
                "", "", temp
            ));

            // Separate pressure event (for barometric correlation)
            events.add(new OracleEvent(
                Instant.now(), "weather", "pressure",
                String.format("barometric_pressure=%.0fhPa", pressure),
                "", "", pressure
            ));
        }

        // 3-day forecast summary
        var daily = json.path("daily");
        if (!daily.isMissingNode()) {
            var dates = daily.path("time");
            var maxTemps = daily.path("temperature_2m_max");
            var minTemps = daily.path("temperature_2m_min");
            var precipSums = daily.path("precipitation_sum");

            for (int i = 0; i < Math.min(dates.size(), 3); i++) {
                var date = dates.get(i).asText();
                var maxTemp = maxTemps.get(i).asDouble();
                var minTemp = minTemps.get(i).asDouble();
                var precipSum = precipSums.get(i).asDouble();

                events.add(new OracleEvent(
                    Instant.now(), "weather", "forecast",
                    String.format("forecast %s: %.0f-%.0f°C precip=%.1fmm",
                        date, minTemp, maxTemp, precipSum),
                    "", "", maxTemp
                ));
            }
        }

        return events;
    }
}

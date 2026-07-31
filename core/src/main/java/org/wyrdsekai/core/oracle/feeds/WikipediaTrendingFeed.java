package org.wyrdsekai.core.oracle.feeds;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.core.oracle.OracleEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Wikipedia most-viewed articles feed.
 * Free, no auth. Wikimedia REST API.
 * Polls daily — top 50 trending articles.
 */
public final class WikipediaTrendingFeed implements FeedPoller.FeedSource {

    private static final String API_URL =
        "https://wikimedia.org/api/rest_v1/metrics/pageviews/top/en.wikipedia/all-access/%s/%s/%s";

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name() { return "wikipedia_trending"; }
    @Override public long intervalSeconds() { return 86400; } // daily
    @Override public long initialDelaySeconds() { return 30; }

    @Override
    public List<OracleEvent> poll() throws Exception {
        // Yesterday's data (today's not available yet)
        var yesterday = LocalDate.now().minusDays(1);
        var url = String.format(API_URL,
            yesterday.getYear(),
            String.format("%02d", yesterday.getMonthValue()),
            String.format("%02d", yesterday.getDayOfMonth()));

        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "oracle-core/0.1 (https://github.com/wyrdsekai/oracle-core)")
            .timeout(Duration.ofSeconds(15))
            .GET().build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return List.of();

        var json = mapper.readTree(resp.body());
        var articles = json.path("items").path(0).path("articles");
        if (articles.isMissingNode() || !articles.isArray()) return List.of();

        var events = new ArrayList<OracleEvent>();
        for (int i = 0; i < Math.min(articles.size(), 50); i++) {
            var article = articles.get(i);
            var title = article.path("article").asText("").replace("_", " ");
            var views = article.path("views").asLong(0);

            // Skip main page, special pages
            if (title.startsWith("Main Page") || title.startsWith("Special:")) continue;

            events.add(new OracleEvent(
                Instant.now(), "wikipedia", "trending",
                title,
                "", "", (double) views
            ));
        }

        return events;
    }
}

package org.wyrdsekai.core.oracle.feeds;

import org.wyrdsekai.core.oracle.OracleEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * arXiv new papers feed via RSS.
 * Free, no auth. Polls daily for new papers in configured fields.
 *
 * Fields: "cs.AI", "cs.CL", "cs.DC", "cs.LG", etc.
 */
public final class ArxivFeed implements FeedPoller.FeedSource {

    private static final String RSS_URL = "https://rss.arxiv.org/rss/%s";
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>(.*?)</title>");
    private static final Pattern DESC_PATTERN = Pattern.compile("<description>(.*?)</description>", Pattern.DOTALL);
    private static final int MAX_PAPERS = 20;

    private final List<String> fields;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public ArxivFeed(List<String> fields) {
        this.fields = fields;
    }

    @Override public String name() { return "arxiv"; }
    @Override public long intervalSeconds() { return 86400; } // daily
    @Override public long initialDelaySeconds() { return 60; }

    @Override
    public List<OracleEvent> poll() throws Exception {
        var events = new ArrayList<OracleEvent>();

        for (var field : fields) {
            try {
                var fieldEvents = pollField(field);
                events.addAll(fieldEvents);
            } catch (Exception ignored) {}
        }

        return events;
    }

    private List<OracleEvent> pollField(String field) throws Exception {
        var url = String.format(RSS_URL, field);
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET().build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return List.of();

        var body = resp.body();
        var events = new ArrayList<OracleEvent>();

        // Simple RSS parsing (no XML lib dependency)
        var titleMatcher = TITLE_PATTERN.matcher(body);
        int count = 0;
        // Skip the feed title (first match)
        if (titleMatcher.find()) { /* skip */ }

        while (titleMatcher.find() && count < MAX_PAPERS) {
            var title = titleMatcher.group(1)
                .replaceAll("<[^>]+>", "")  // strip HTML
                .replaceAll("\\s+", " ")
                .trim();

            if (!title.isEmpty()) {
                events.add(new OracleEvent(
                    Instant.now(), "arxiv", "paper",
                    "[" + field + "] " + title,
                    "", ""
                ));
                count++;
            }
        }

        return events;
    }
}

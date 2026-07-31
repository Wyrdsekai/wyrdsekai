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
 * Hacker News top stories feed.
 * Free, no auth, no rate limit. Firebase API.
 * Polls top 30 stories hourly.
 */
public final class HackerNewsFeed implements FeedPoller.FeedSource {

    private static final String TOP_URL = "https://hacker-news.firebaseio.com/v0/topstories.json";
    private static final String ITEM_URL = "https://hacker-news.firebaseio.com/v0/item/%d.json";
    private static final int MAX_STORIES = 15;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private long lastPollId = 0;

    @Override public String name() { return "hacker_news"; }
    @Override public long intervalSeconds() { return 3600; } // hourly

    @Override
    public List<OracleEvent> poll() throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(TOP_URL))
            .timeout(Duration.ofSeconds(15))
            .GET().build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return List.of();

        var ids = mapper.readTree(resp.body());
        var events = new ArrayList<OracleEvent>();

        for (int i = 0; i < Math.min(ids.size(), MAX_STORIES); i++) {
            long id = ids.get(i).asLong();
            if (id <= lastPollId) continue;

            try {
                var itemReq = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(ITEM_URL, id)))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
                var itemResp = client.send(itemReq, HttpResponse.BodyHandlers.ofString());
                if (itemResp.statusCode() != 200) continue;

                var item = mapper.readTree(itemResp.body());
                var title = item.path("title").asText("");
                var url = item.path("url").asText("");
                var score = item.path("score").asInt(0);

                if (!title.isEmpty()) {
                    events.add(new OracleEvent(
                        Instant.now(), "hacker_news", "article",
                        title + (url.isEmpty() ? "" : " (" + url + ")"),
                        "", "", (double) score
                    ));
                }
            } catch (Exception ignored) {}
        }

        if (!events.isEmpty()) {
            lastPollId = ids.get(0).asLong();
        }
        return events;
    }
}

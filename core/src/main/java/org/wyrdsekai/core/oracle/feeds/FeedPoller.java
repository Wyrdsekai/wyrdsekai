package org.wyrdsekai.core.oracle.feeds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.oracle.OracleBridge;
import org.wyrdsekai.core.oracle.OracleEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Polls free external data sources and feeds events to oracle-core.
 *
 * Runs as a background daemon. Each feed has its own poll interval.
 * Non-fatal — if a feed fails, it's retried next interval.
 *
 * Feeds:
 * - Hacker News (top stories, hourly)
 * - Wikipedia trending (daily)
 * - Open-Meteo weather (hourly, if location configured)
 * - arXiv new papers (daily, if fields configured)
 *
 * Each feed is a FeedSource that produces OracleEvents.
 */
public final class FeedPoller {

    private static final Logger log = LoggerFactory.getLogger(FeedPoller.class);

    private final OracleBridge bridge;
    private final String userId;
    private final List<FeedSource> sources = new ArrayList<>();
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    public FeedPoller(OracleBridge bridge, String userId) {
        this.bridge = bridge;
        this.userId = userId;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "oracle-feed-poller");
            t.setDaemon(true);
            return t;
        });
    }

    /** Register a feed source. */
    public void addSource(FeedSource source) {
        sources.add(source);
    }

    /** Add all default free feeds. */
    public void addDefaults(String latitude, String longitude, List<String> arxivFields) {
        sources.add(new HackerNewsFeed());
        sources.add(new WikipediaTrendingFeed());
        if (latitude != null && longitude != null) {
            sources.add(new OpenMeteoFeed(latitude, longitude));
        }
        if (arxivFields != null && !arxivFields.isEmpty()) {
            sources.add(new ArxivFeed(arxivFields));
        }
    }

    /** Start polling all sources. */
    public void start() {
        running = true;
        for (var source : sources) {
            scheduler.scheduleAtFixedRate(
                () -> pollSource(source),
                source.initialDelaySeconds(),
                source.intervalSeconds(),
                TimeUnit.SECONDS
            );
        }
        log.info("FeedPoller started with {} sources for user '{}'", sources.size(), userId);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    private void pollSource(FeedSource source) {
        if (!running) return;
        try {
            var events = source.poll();
            if (!events.isEmpty()) {
                bridge.ingest(userId, events).thenAccept(count -> {
                    if (count > 0) {
                        log.debug("FeedPoller: {} produced {} events", source.name(), count);
                    }
                });
            }
        } catch (Exception e) {
            log.debug("FeedPoller: {} failed: {}", source.name(), e.getMessage());
        }
    }

    /** A single external data source that produces OracleEvents. */
    public interface FeedSource {
        String name();
        long intervalSeconds();
        default long initialDelaySeconds() { return 10; }
        List<OracleEvent> poll() throws Exception;
    }
}

package org.wyrdsekai.core.library;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Fetches RSS/Atom feeds and indexes articles into the knowledge base.
 * Runs as a periodic background service. Articles are chunked and indexed
 * into the "news" Lucene collection within the knowledge base.
 *
 * Feed subscriptions are stored in-memory with persistence via room properties.
 */
public final class FeedIndexer {

    private static final Logger log = LoggerFactory.getLogger(FeedIndexer.class);

    private final WyrdLuceneStore luceneStore;
    private final KnowledgePackIndexer packIndexer;
    private final Map<String, FeedSubscription> subscriptions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { var t = new Thread(r, "feed-indexer"); t.setDaemon(true); return t; });

    /** A feed subscription. */
    public record FeedSubscription(String name, String url, Duration interval, Instant lastFetched) {}

    public FeedIndexer(WyrdLuceneStore luceneStore) {
        this.luceneStore = luceneStore;
        this.packIndexer = new KnowledgePackIndexer(luceneStore);
    }

    /**
     * Subscribe to an RSS/Atom feed.
     */
    public void subscribe(String name, String url, Duration interval) {
        subscriptions.put(name, new FeedSubscription(name, url, interval, null));
        log.info("[FeedIndexer] Subscribed to '{}' at {} (every {})", name, url, interval);
        // Fetch immediately
        scheduler.submit(() -> fetchAndIndex(name));
    }

    /**
     * Unsubscribe from a feed.
     */
    public void unsubscribe(String name) {
        subscriptions.remove(name);
        log.info("[FeedIndexer] Unsubscribed from '{}'", name);
    }

    /**
     * List subscriptions.
     */
    public Collection<FeedSubscription> listSubscriptions() {
        return Collections.unmodifiableCollection(subscriptions.values());
    }

    /**
     * Start the periodic fetch scheduler.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(() -> {
            for (var sub : subscriptions.values()) {
                if (sub.lastFetched() == null ||
                    Instant.now().isAfter(sub.lastFetched().plus(sub.interval()))) {
                    fetchAndIndex(sub.name());
                }
            }
        }, 1, 60, TimeUnit.MINUTES); // Check every minute, respect per-feed intervals
        log.info("[FeedIndexer] Started with {} subscriptions", subscriptions.size());
    }

    /**
     * Stop the scheduler.
     */
    public void stop() {
        scheduler.shutdown();
    }

    /**
     * Fetch a single feed and index new articles.
     */
    private void fetchAndIndex(String name) {
        var sub = subscriptions.get(name);
        if (sub == null) return;

        try {
            log.debug("[FeedIndexer] Fetching '{}'...", name);
            var input = new SyndFeedInput();
            var feed = input.build(new XmlReader(URI.create(sub.url()).toURL()));

            int indexed = 0;
            for (var entry : feed.getEntries()) {
                var title = entry.getTitle();
                var content = extractContent(entry);
                if (content == null || content.isBlank()) continue;

                // Truncate to reasonable chunk size
                if (content.length() > 3000) {
                    content = content.substring(0, 3000);
                }

                var link = entry.getLink();
                var pubDate = entry.getPublishedDate() != null
                    ? entry.getPublishedDate().toInstant().toString() : "";

                var id = "news:" + name + ":" + Math.abs(
                    (title + link).hashCode());

                luceneStore.insertKnowledge(id, "news-" + name, title, content,
                    link != null ? link : sub.url(), null, null);
                indexed++;
            }

            luceneStore.commitAll();

            // Update last fetched
            subscriptions.put(name, new FeedSubscription(
                sub.name(), sub.url(), sub.interval(), Instant.now()));

            log.info("[FeedIndexer] Fetched '{}': {} articles indexed", name, indexed);

        } catch (Exception e) {
            log.warn("[FeedIndexer] Failed to fetch '{}': {}", name, e.getMessage());
        }
    }

    private String extractContent(SyndEntry entry) {
        // Try content first, then description
        if (entry.getContents() != null && !entry.getContents().isEmpty()) {
            var content = entry.getContents().getFirst();
            return stripHtml(content.getValue());
        }
        if (entry.getDescription() != null) {
            return stripHtml(entry.getDescription().getValue());
        }
        return entry.getTitle();
    }

    /** Simple HTML tag stripping. */
    private String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", " ")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&nbsp;", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /**
     * Get count of news articles indexed.
     */
    public long articleCount() {
        long count = 0;
        for (var sub : subscriptions.keySet()) {
            count += luceneStore.countKnowledgeByPack("news-" + sub);
        }
        return count;
    }
}

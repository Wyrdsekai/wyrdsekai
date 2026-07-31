package org.wyrdsekai.core.skill.impl;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.io.StringReader;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RSS/Atom feed reader skill executor.
 * Uses ROME (com.rometools:rome) for feed parsing.
 * Extends HttpSkillExecutor for fetching feed XML.
 * Maintains subscribed feeds in a ConcurrentHashMap.
 */
public class RssSkillExecutor extends HttpSkillExecutor {

    private final Map<String, String> subscriptions = new ConcurrentHashMap<>(); // name -> url

    public RssSkillExecutor() {
        super(null);

        define(new SkillDefinition("scrying.rss.feeds", "RSS Feeds",
            "List subscribed RSS/Atom feeds", "scrying-pool", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0", List.of(),
            SkillAuth.NONE, SkillLocality.ANY, true));

        define(new SkillDefinition("scrying.rss.latest", "RSS Latest",
            "Fetch latest entries from subscribed feeds", "scrying-pool", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("feed", "string", "Feed name or URL (or all if omitted)"),
                     SkillParam.optional("limit", "number", "Max entries to return")),
            SkillAuth.NONE, SkillLocality.ANY, true));

        define(new SkillDefinition("scrying.rss.subscribe", "RSS Subscribe",
            "Subscribe to an RSS or Atom feed", "scrying-pool", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("url", "string", "Feed URL"),
                     SkillParam.optional("name", "string", "Feed display name")),
            SkillAuth.NONE, SkillLocality.ANY, true));

        define(new SkillDefinition("scrying.rss.summarize", "RSS Summarize",
            "Summarize recent feed entries", "scrying-pool", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("feed", "string", "Feed name or URL"),
                     SkillParam.optional("limit", "number", "Max entries to summarize")),
            SkillAuth.NONE, SkillLocality.ANY, true));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();
        return switch (skillId) {
            case "scrying.rss.feeds" -> executeFeeds(start, skillId);
            case "scrying.rss.latest" -> executeLatest(params, start, skillId);
            case "scrying.rss.subscribe" -> executeSubscribe(params, start, skillId);
            case "scrying.rss.summarize" -> executeSummarize(params, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeFeeds(long start, String skillId) {
        long elapsed = System.currentTimeMillis() - start;
        if (subscriptions.isEmpty()) {
            return SkillResult.ok(I18n.get("skill.rss.no_feeds"),
                Map.of("feeds", List.of()), elapsed, SkillTier.NATIVE, skillId);
        }
        var feeds = new ArrayList<Map<String, String>>();
        subscriptions.forEach((name, url) -> feeds.add(Map.of("name", name, "url", url)));
        return SkillResult.ok(subscriptions.size() + " feeds subscribed",
            Map.of("feeds", feeds, "count", feeds.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeLatest(Map<String, Object> params, long start, String skillId) {
        String feedRef = param(params, "feed", null);
        int limit = intParam(params, "limit", 10);

        Collection<String> urls;
        if (feedRef != null) {
            String resolved = subscriptions.getOrDefault(feedRef, feedRef);
            urls = List.of(resolved);
        } else {
            urls = subscriptions.values();
        }

        if (urls.isEmpty()) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.ok(I18n.get("skill.rss.no_feeds"),
                Map.of("entries", List.of()), elapsed, SkillTier.NATIVE, skillId);
        }

        List<Map<String, String>> entries = new ArrayList<>();
        for (String url : urls) {
            if (entries.size() >= limit) break;
            fetchEntries(url, limit - entries.size(), entries);
        }

        long elapsed = System.currentTimeMillis() - start;
        var sb = new StringBuilder();
        for (var entry : entries) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(entry.get("title")).append(" -- ").append(entry.get("link"));
        }

        return SkillResult.ok(I18n.get("skill.rss.latest", entries.size()),
            Map.of("entries", entries, "count", entries.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeSubscribe(Map<String, Object> params, long start, String skillId) {
        String url = requireParam(params, "url");
        if (url == null) return SkillResult.error(
            I18n.get("skill.param_required", "url"), 0, SkillTier.NATIVE, skillId);

        String name = param(params, "name", url);
        subscriptions.put(name, url);
        long elapsed = System.currentTimeMillis() - start;

        return SkillResult.ok(I18n.get("skill.rss.subscribed", name),
            Map.of("name", name, "url", url, "total", subscriptions.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeSummarize(Map<String, Object> params, long start, String skillId) {
        // Summarize reuses latest but formats output for summarization
        String feedRef = param(params, "feed", null);
        int limit = intParam(params, "limit", 5);

        Collection<String> urls;
        if (feedRef != null) {
            String resolved = subscriptions.getOrDefault(feedRef, feedRef);
            urls = List.of(resolved);
        } else {
            urls = subscriptions.values();
        }

        if (urls.isEmpty()) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.ok(I18n.get("skill.rss.no_feeds"),
                Map.of("entries", List.of()), elapsed, SkillTier.NATIVE, skillId);
        }

        List<Map<String, String>> entries = new ArrayList<>();
        for (String url : urls) {
            if (entries.size() >= limit) break;
            fetchEntries(url, limit - entries.size(), entries);
        }

        long elapsed = System.currentTimeMillis() - start;
        var sb = new StringBuilder();
        for (var entry : entries) {
            sb.append("- ").append(entry.get("title"));
            if (entry.containsKey("description"))
                sb.append(": ").append(entry.get("description"));
            sb.append("\n");
        }

        return SkillResult.ok(sb.toString().trim(),
            Map.of("entries", entries, "count", entries.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private void fetchEntries(String feedUrl, int max, List<Map<String, String>> out) {
        var result = httpGet(feedUrl, Map.of(), 15_000);
        if (!result.ok() || result.body() == null) return;

        try {
            SyndFeed feed = new SyndFeedInput().build(new StringReader(result.body()));
            for (SyndEntry entry : feed.getEntries()) {
                if (out.size() >= max) break;
                var item = new LinkedHashMap<String, String>();
                item.put("title", entry.getTitle() != null ? entry.getTitle() : "");
                item.put("link", entry.getLink() != null ? entry.getLink() : "");
                item.put("feed", feedUrl);
                if (entry.getDescription() != null) {
                    item.put("description", entry.getDescription().getValue());
                }
                if (entry.getPublishedDate() != null) {
                    item.put("published", entry.getPublishedDate().toString());
                }
                out.add(item);
            }
        } catch (Exception e) {
            // Skip feeds that fail to parse
        }
    }

    /** Direct access for testing. */
    public Map<String, String> getSubscriptions() {
        return Collections.unmodifiableMap(subscriptions);
    }
}

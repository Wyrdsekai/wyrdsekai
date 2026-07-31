package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-backend web search service for agent actions.
 *
 * <p>Backend priority (first available wins):
 * <ol>
 *   <li>Searxng (local metasearch — free, no limits, best quality)</li>
 *   <li>Brave Search (API key: BRAVE_SEARCH_API_KEY — free tier 2k/mo)</li>
 *   <li>Tavily (API key: TAVILY_API_KEY — AI-optimized, free tier 1k/mo)</li>
 *   <li>SerpAPI (API key: SERPAPI_API_KEY — Google results, $50/mo)</li>
 *   <li>DuckDuckGo Instant Answer API (always available, limited quality)</li>
 * </ol>
 *
 * <p>Thread-safe singleton.</p>
 */
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static volatile WebSearchService instance;

    private final HttpClient httpClient;
    private final String searxngUrl;     // nullable
    private final String braveApiKey;    // nullable
    private final String tavilyApiKey;   // nullable
    private final String serpApiKey;     // nullable
    private final String activeBackend;  // which backend is in use

    public static WebSearchService init() {
        instance = new WebSearchService();
        return instance;
    }

    public static WebSearchService get() { return instance; }

    private WebSearchService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        // Probe backends in priority order
        var searxng = System.getenv().getOrDefault("WYRDSEKAI_SEARXNG_URL", "http://localhost:8888");
        this.searxngUrl = isSearxngAvailable(searxng) ? searxng : null;
        this.braveApiKey = System.getenv("BRAVE_SEARCH_API_KEY");
        this.tavilyApiKey = System.getenv("TAVILY_API_KEY");
        this.serpApiKey = System.getenv("SERPAPI_API_KEY");

        if (this.searxngUrl != null) {
            activeBackend = "searxng";
            log.info("WebSearchService: Searxng at {}", searxng);
        } else if (braveApiKey != null && !braveApiKey.isBlank()) {
            activeBackend = "brave";
            log.info("WebSearchService: Brave Search API");
        } else if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            activeBackend = "tavily";
            log.info("WebSearchService: Tavily API");
        } else if (serpApiKey != null && !serpApiKey.isBlank()) {
            activeBackend = "serpapi";
            log.info("WebSearchService: SerpAPI (Google)");
        } else {
            activeBackend = "duckduckgo";
            log.info("WebSearchService: DuckDuckGo Instant Answer (fallback)");
        }
    }

    public record SearchResult(String title, String url, String snippet) {}

    /** Which backend is active. */
    public String activeBackend() { return activeBackend; }

    // ─── Test seeding ────────────────────────────────────────────

    /**
     * Canned results for testing. When seeded, queries matching a seed key (case-insensitive
     * substring) return the canned results instead of hitting a real backend.
     * This ensures E2E tests work without Searxng/Brave/Tavily running.
     */
    private final Map<String, List<SearchResult>> seededResults = new ConcurrentHashMap<>();

    /**
     * Seed canned results for a query pattern. Any search containing the key
     * (case-insensitive) will return these results.
     */
    public void seedResults(String queryPattern, List<SearchResult> results) {
        seededResults.put(queryPattern.toLowerCase(), results);
        log.debug("WebSearchService: seeded {} results for pattern '{}'", results.size(), queryPattern);
    }

    private List<SearchResult> checkSeeded(String query) {
        if (seededResults.isEmpty()) return null;
        var lower = query.toLowerCase();
        for (var entry : seededResults.entrySet()) {
            if (lower.contains(entry.getKey())) {
                log.debug("WebSearchService: returning seeded results for '{}'", query);
                return entry.getValue();
            }
        }
        return null;
    }

    // ─── Search ──────────────────────────────────────────────────

    /**
     * Search the web.
     */
    public List<SearchResult> search(String query, int maxResults) {
        var seeded = checkSeeded(query);
        if (seeded != null) return seeded.subList(0, Math.min(seeded.size(), maxResults));
        return switch (activeBackend) {
            case "searxng" -> searchSearxng(query, maxResults, false);
            case "brave" -> searchBrave(query, maxResults);
            case "tavily" -> searchTavily(query, maxResults, "search");
            case "serpapi" -> searchSerpApi(query, maxResults);
            default -> searchDuckDuckGo(query, maxResults);
        };
    }

    /**
     * Search for recent news.
     */
    public List<SearchResult> searchNews(String query, int maxResults) {
        var seeded = checkSeeded(query);
        if (seeded != null) return seeded.subList(0, Math.min(seeded.size(), maxResults));
        return switch (activeBackend) {
            case "searxng" -> searchSearxng(query, maxResults, true);
            case "brave" -> searchBrave(query + " news recent", maxResults);
            case "tavily" -> searchTavily(query, maxResults, "news");
            case "serpapi" -> searchSerpApi(query + " news", maxResults);
            default -> searchDuckDuckGo(query + " news", maxResults);
        };
    }

    /**
     * Fetch and extract readable text from a URL.
     */
    public String fetchContent(String url, int maxChars) {
        // Try Tavily extract if available (AI-optimized content extraction)
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            var extracted = tavilyExtract(url);
            if (extracted != null) return extracted.length() > maxChars
                ? extracted.substring(0, maxChars) : extracted;
        }

        // Fallback: direct HTTP fetch with HTML stripping
        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Wyrdsekai/1.0 (Agent Content Fetch)")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            var text = stripHtml(resp.body());
            return text.length() > maxChars ? text.substring(0, maxChars) : text;
        } catch (Exception e) {
            log.warn("Failed to fetch content from {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Build a text summary of search results for prompt injection.
     */
    public static String formatResults(List<SearchResult> results, String query) {
        if (results.isEmpty()) return "No results found for: " + query;
        var sb = new StringBuilder();
        sb.append("Search results for '").append(query).append("':\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title()).append("\n");
            if (r.snippet() != null && !r.snippet().isBlank())
                sb.append("   ").append(r.snippet()).append("\n");
            if (r.url() != null && !r.url().isBlank())
                sb.append("   ").append(r.url()).append("\n");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Searxng (local metasearch)
    // ═══════════════════════════════════════════════════════════════════

    private List<SearchResult> searchSearxng(String query, int maxResults, boolean newsOnly) {
        try {
            var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            var url = searxngUrl + "/search?q=" + encoded + "&format=json";
            if (newsOnly) url += "&categories=news";

            var req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET().build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();

            return parseSearxngJson(mapper.readTree(resp.body()), maxResults);
        } catch (Exception e) {
            log.warn("Searxng search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Parse a search response body into {@link SearchResult}s. Tolerant of two
     * on-the-wire shapes:
     * <ul>
     *   <li>Searxng:    {@code {"results":[{"title","url","content"}, ...]}}</li>
     *   <li>metasearch2 (mat-1/metasearch2, the bundled keyless backend on every
     *       platform): {@code [{"search_results":[{"result":{url,title,description},
     *       "engines":[...]}, ...]}]} — note the top-level array, the
     *       "search_results" wrapper, the per-item "result" object, and that the
     *       snippet field is "description", not "content".</li>
     * </ul>
     * Package-private for unit testing.
     */
    static List<SearchResult> parseSearxngJson(JsonNode root, int maxResults) {
        var results = new ArrayList<SearchResult>();
        if (root == null) return results;
        var arr = root.get("results");
        if (arr != null && arr.isArray()) {
            for (int i = 0; i < Math.min(arr.size(), maxResults); i++) {
                var item = arr.get(i);
                results.add(new SearchResult(
                    getStr(item, "title"), getStr(item, "url"), getStr(item, "content")));
            }
            return results;
        }
        // metasearch2 shape: locate the "search_results" array (root may be an
        // array whose first element carries it, or an object that carries it).
        var container = root.isArray() && root.size() > 0 ? root.get(0) : root;
        var ms = container != null ? container.get("search_results") : null;
        if (ms != null && ms.isArray()) {
            for (int i = 0; i < Math.min(ms.size(), maxResults); i++) {
                var wrapper = ms.get(i);
                var item = wrapper.has("result") ? wrapper.get("result") : wrapper;
                var snippet = item.has("content") ? getStr(item, "content") : getStr(item, "description");
                results.add(new SearchResult(
                    getStr(item, "title"), getStr(item, "url"), snippet));
            }
        }
        return results;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Brave Search API
    // ═══════════════════════════════════════════════════════════════════

    private List<SearchResult> searchBrave(String query, int maxResults) {
        try {
            var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            var req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.search.brave.com/res/v1/web/search?q=" + encoded
                    + "&count=" + maxResults))
                .header("X-Subscription-Token", braveApiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET().build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Brave search returned {}: {}", resp.statusCode(), resp.body());
                return List.of();
            }

            var root = mapper.readTree(resp.body());
            var results = new ArrayList<SearchResult>();
            var web = root.path("web").path("results");
            if (web.isArray()) {
                for (int i = 0; i < Math.min(web.size(), maxResults); i++) {
                    var item = web.get(i);
                    results.add(new SearchResult(
                        getStr(item, "title"), getStr(item, "url"), getStr(item, "description")));
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("Brave search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Tavily API (AI-optimized search)
    // ═══════════════════════════════════════════════════════════════════

    private List<SearchResult> searchTavily(String query, int maxResults, String topic) {
        try {
            var body = mapper.createObjectNode();
            body.put("query", query);
            body.put("max_results", maxResults);
            body.put("topic", topic); // "general" or "news"
            body.put("include_answer", true);

            var req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tavilyApiKey)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Tavily search returned {}: {}", resp.statusCode(), resp.body());
                return List.of();
            }

            var root = mapper.readTree(resp.body());
            var results = new ArrayList<SearchResult>();

            // Tavily returns an AI-generated answer + source results
            var answer = root.has("answer") ? root.get("answer").asText("") : "";
            if (!answer.isBlank()) {
                results.add(new SearchResult("AI Summary", "", answer));
            }

            var arr = root.get("results");
            if (arr != null && arr.isArray()) {
                for (int i = 0; i < Math.min(arr.size(), maxResults); i++) {
                    var item = arr.get(i);
                    results.add(new SearchResult(
                        getStr(item, "title"), getStr(item, "url"), getStr(item, "content")));
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("Tavily search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Tavily extract API — AI-optimized content extraction from a URL. */
    private String tavilyExtract(String url) {
        try {
            var body = mapper.createObjectNode();
            body.putArray("urls").add(url);

            var req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/extract"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tavilyApiKey)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            var root = mapper.readTree(resp.body());
            var results = root.get("results");
            if (results != null && results.isArray() && !results.isEmpty()) {
                return results.get(0).path("raw_content").asText(null);
            }
            return null;
        } catch (Exception e) {
            log.debug("Tavily extract failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SerpAPI (Google search results)
    // ═══════════════════════════════════════════════════════════════════

    private List<SearchResult> searchSerpApi(String query, int maxResults) {
        try {
            var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            var req = HttpRequest.newBuilder()
                .uri(URI.create("https://serpapi.com/search.json?q=" + encoded
                    + "&api_key=" + serpApiKey + "&num=" + maxResults))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("SerpAPI returned {}: {}", resp.statusCode(), resp.body());
                return List.of();
            }

            var root = mapper.readTree(resp.body());
            var results = new ArrayList<SearchResult>();
            var organic = root.get("organic_results");
            if (organic != null && organic.isArray()) {
                for (int i = 0; i < Math.min(organic.size(), maxResults); i++) {
                    var item = organic.get(i);
                    results.add(new SearchResult(
                        getStr(item, "title"), getStr(item, "link"), getStr(item, "snippet")));
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("SerpAPI search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DuckDuckGo Instant Answer API (fallback)
    // ═══════════════════════════════════════════════════════════════════

    private List<SearchResult> searchDuckDuckGo(String query, int maxResults) {
        try {
            var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            var req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.duckduckgo.com/?q=" + encoded + "&format=json&no_html=1"))
                .header("User-Agent", "Wyrdsekai/1.0")
                .timeout(Duration.ofSeconds(10))
                .GET().build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();

            var root = mapper.readTree(resp.body());
            var results = new ArrayList<SearchResult>();

            var abs = root.has("Abstract") ? root.get("Abstract").asText("") : "";
            var absUrl = root.has("AbstractURL") ? root.get("AbstractURL").asText("") : "";
            if (!abs.isBlank()) {
                results.add(new SearchResult("Summary", absUrl, abs));
            }

            var related = root.get("RelatedTopics");
            if (related != null && related.isArray()) {
                for (int i = 0; i < related.size() && results.size() < maxResults; i++) {
                    var item = related.get(i);
                    if (item.has("Text") && item.has("FirstURL")) {
                        results.add(new SearchResult(
                            item.get("Text").asText(""), item.get("FirstURL").asText(""), null));
                    }
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("DuckDuckGo search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════════

    private boolean isSearxngAvailable(String url) {
        // Searxng exposes /healthz; metasearch2 (the bundled keyless backend) does
        // not — it only serves /, /search, /settings, etc. Probe /healthz first
        // (Searxng-native), then fall back to the index "/" so a running
        // metasearch2 is recognised instead of silently dropping to DuckDuckGo.
        for (var path : new String[]{"/healthz", "/"}) {
            try {
                var req = HttpRequest.newBuilder()
                    .uri(URI.create(url + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
                var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return true;
            } catch (Exception e) {
                // try next path
            }
        }
        return false;
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
            .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
            .replaceAll("<[^>]+>", " ")
            .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
            .replaceAll("\\s+", " ").trim();
    }

    private static String getStr(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText("") : "";
    }
}

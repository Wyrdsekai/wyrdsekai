package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.api.ItemManifestValidator;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standalone replay of {@link OpenHandsE2ETest#task10_full_pipeline} that
 * skips the OpenHands round-trip and exercises only the
 * {@link org.wyrdsekai.scripting.sandbox.ItemScriptExecutor} +
 * {@link org.wyrdsekai.scripting.api.ItemWorldApiProvider} chain against
 * the most-recently-generated artifact in the workspace.
 *
 * <p>Purpose: produce visible output of the live execution chain
 * (Searxng → HTTP fetch → llama-voice summarize) without re-running the
 * 9B agent or stopping prod containers. Asserts the same shape as the
 * full task10, plus prints the actual summary text + counter values to
 * stdout for the run log.</p>
 *
 * <p>Gated by {@code WYRDSEKAI_E2E_REPLAY_TASK10=1}. Requires:
 * <ul>
 *   <li>An items-as-tools artifact in {@code ${WYRDSEKAI_DATA}/openhands-workspace/}
 *       (defaults to repo {@code data/} dir) declaring web.search +
 *       web.fetch + llm.summarize/analyze/complete capabilities.</li>
 *   <li>Searxng on {@code WYRDSEKAI_SEARXNG_URL} (default
 *       {@code http://localhost:8888}).</li>
 *   <li>Voice backend on {@code WYRDSEKAI_E2E_VOICE_URL} (default
 *       {@code http://localhost:8201}).</li>
 * </ul></p>
 */
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_REPLAY_TASK10", matches = "1")
class OpenHandsItemReplayTest {

    @Test
    void replay_existing_artifact() throws Exception {
        var dataDir = System.getenv().getOrDefault("WYRDSEKAI_DATA",
            System.getProperty("user.dir") + "/../data");
        var workspace = Path.of(dataDir, "openhands-workspace");
        assertTrue(Files.isDirectory(workspace),
            "Workspace dir not found: " + workspace);

        // Pick the newest .js file with research capabilities. Same gating
        // logic as task10's picker — keeps the artifact selection honest.
        Path matched = null;
        ItemManifest manifest = null;
        String src = null;
        long bestMtime = Long.MIN_VALUE;
        try (var stream = Files.list(workspace)) {
            for (var p : (Iterable<Path>) stream::iterator) {
                if (!p.getFileName().toString().endsWith(".js")) continue;
                String s;
                try {
                    s = Files.readString(p);
                } catch (Exception _) { continue; }
                var m = ItemManifestParser.parse(s);
                if (m == null) continue;
                var v = ItemManifestValidator.validate(m);
                if (!v.valid()) continue;
                var caps = m.capabilities() == null ? List.<String>of() : m.capabilities();
                boolean research = caps.contains("web.search")
                    && caps.contains("web.fetch")
                    && (caps.contains("llm.summarize") || caps.contains("llm.analyze")
                        || caps.contains("llm.complete"));
                if (!research) continue;
                long mtime = Files.getLastModifiedTime(p).toMillis();
                if (mtime > bestMtime) {
                    bestMtime = mtime;
                    matched = p;
                    manifest = m;
                    src = s;
                }
            }
        }
        assertNotNull(matched,
            "No items-as-tools artifact with research capabilities found in "
            + workspace);

        var voiceUrl = System.getenv().getOrDefault(
            "WYRDSEKAI_E2E_VOICE_URL", "http://localhost:8201");
        var searxngUrl = System.getenv().getOrDefault(
            "WYRDSEKAI_SEARXNG_URL", "http://localhost:8888");
        var liveProvider = new ReplayProvider(searxngUrl, voiceUrl);

        System.out.println("[replay] artifact: " + matched.getFileName()
            + "  bytes=" + src.length()
            + "  mtime=" + Instant.ofEpochMilli(bestMtime));
        System.out.println("[replay] manifest: name=" + manifest.name()
            + "  version=" + manifest.version()
            + "  caps=" + manifest.capabilities());

        try (var executor = new ItemScriptExecutor()) {
            var caps = ItemCapabilitySet.from(manifest);
            var invokeParams = new HashMap<String, Object>();
            invokeParams.put("genre", "pop");

            long t0 = System.currentTimeMillis();
            Map<String, Object> result = executor.execute(
                manifest.name(), src, invokeParams, liveProvider, caps);
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println("[replay] === LIVE EXECUTION RESULT (" + elapsed + "ms) ===");
            System.out.println("[replay] ok=" + result.get("ok")
                + "  error=" + result.get("error"));
            System.out.println("[replay] counters: search="
                + liveProvider.searchCalls.get()
                + "  fetch=" + liveProvider.fetchCalls.get()
                + "  summarize=" + liveProvider.summarizeCalls.get());
            System.out.println("[replay] sources: " + result.get("sources"));
            var summary = result.get("summary");
            if (summary instanceof String s) {
                System.out.println("[replay] summary (" + s.length() + " chars):");
                System.out.println("[replay]   " + s.replace("\n", "\n[replay]   "));
            } else {
                System.out.println("[replay] summary: " + summary);
            }
            System.out.println("[replay] === END LIVE EXECUTION RESULT ===");

            assertNull(result.get("error"),
                "Item invocation must not error. Got: " + result);
            assertEquals(Boolean.TRUE, result.get("ok"),
                "Item must report ok:true. Result: " + result);
            assertNotNull(summary, "Result must include a 'summary' field");
            assertTrue(summary instanceof String && !((String) summary).isBlank(),
                "summary must be non-empty string. Got: " + summary);
            assertTrue(((String) summary).length() > 50,
                "summary should be substantive (>50 chars), got "
                    + ((String) summary).length());
            assertTrue(liveProvider.searchCalls.get() >= 1,
                "world.web.search should have fired");
            assertTrue(liveProvider.fetchCalls.get() >= 1,
                "world.web.fetch should have fired");
            assertTrue(liveProvider.summarizeCalls.get() >= 1,
                "world.llm.summarize should have fired");
        }
    }

    /**
     * Live-services provider — duplicates {@code OpenHandsE2ETest.LiveResearchProvider}
     * to keep this replay class standalone (no shared package-private state).
     */
    static final class ReplayProvider
            implements ItemWorldApiProvider {
        private final String searxngUrl;
        private final String voiceUrl;
        private final HttpClient http;
        final AtomicInteger searchCalls = new AtomicInteger();
        final AtomicInteger fetchCalls = new AtomicInteger();
        final AtomicInteger summarizeCalls = new AtomicInteger();

        ReplayProvider(String searxngUrl, String voiceUrl) {
            this.searxngUrl = searxngUrl.replaceAll("/$", "");
            this.voiceUrl = voiceUrl.replaceAll("/$", "");
            this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        }

        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> queryOracle(String t, String type) { return List.of(); }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String tgt, String msg) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }

        @Override
        public List<Map<String, Object>> webSearch(String query, String type, int limit) {
            searchCalls.incrementAndGet();
            try {
                var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                var uri = URI.create(searxngUrl + "/search?q=" + encoded + "&format=json");
                var req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "wyrdsekai-replay/1.0")
                    .GET().build();
                var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return List.of();
                var json = new ObjectMapper().readTree(resp.body());
                var results = json.path("results");
                var out = new ArrayList<Map<String, Object>>();
                int n = Math.min(limit, results.size());
                for (int i = 0; i < n; i++) {
                    var r = results.get(i);
                    var m = new LinkedHashMap<String, Object>();
                    m.put("title", r.path("title").asText(""));
                    m.put("url", r.path("url").asText(""));
                    m.put("snippet", r.path("content").asText(""));
                    out.add(m);
                }
                return out;
            } catch (Exception e) {
                System.err.println("[replay] webSearch failed: " + e.getMessage());
                return List.of();
            }
        }

        @Override
        public String webFetch(String url, int maxChars) {
            fetchCalls.incrementAndGet();
            try {
                var req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "wyrdsekai-replay/1.0")
                    .GET().build();
                var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    return "[error] HTTP " + resp.statusCode() + " for " + url;
                }
                var body = resp.body();
                var text = body.replaceAll("(?is)<script.*?</script>", " ")
                    .replaceAll("(?is)<style.*?</style>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("\\s+", " ")
                    .trim();
                int cap = Math.min(Math.max(maxChars, 200), 16000);
                return text.length() > cap ? text.substring(0, cap) : text;
            } catch (Exception e) {
                return "[error] " + e.getMessage();
            }
        }

        @Override
        public String llmSummarize(String text, String instruction) {
            summarizeCalls.incrementAndGet();
            try {
                var system = instruction != null && !instruction.isBlank()
                    ? instruction : "Summarize the key points concisely.";
                var payload = new LinkedHashMap<String, Object>();
                payload.put("model", "wyrdsekai-voice");
                payload.put("messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", text == null ? "" : text)));
                payload.put("max_tokens", 400);
                payload.put("temperature", 0.4);
                var json = new ObjectMapper().writeValueAsString(payload);
                var req = HttpRequest.newBuilder(
                        URI.create(voiceUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    return "[error] HTTP " + resp.statusCode() + ": " + resp.body();
                }
                var tree = new ObjectMapper().readTree(resp.body());
                return tree.path("choices").path(0).path("message").path("content").asText("");
            } catch (Exception e) {
                return "[error] " + e.getMessage();
            }
        }

        @Override public String llmAnalyze(String text, String prompt) { return llmSummarize(text, prompt); }
    }
}

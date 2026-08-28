package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live failure, 2026-08-07. Asked "ari, can u look through my books/library
 * and tell me what significant thing did the librarian tell kestan about velsharas
 * in glass tide?", the companion dispatched to the {@code library_shelves}
 * furnishing and answered <i>"it returned raw data I couldn't read as an
 * answer"</i>.
 *
 * <p>Three defects stacked, none of them in the retrieval stack:</p>
 *
 * <ol>
 *   <li>The dispatcher injects the person's request as {@code query}; the script
 *       read {@code args || text || target}. So {@code raw} was {@code ""}, the
 *       first branch fired, and the item returned its <b>usage screen</b> — which
 *       the never-silent guard then read out loud as an answer.</li>
 *   <li>Even with the text, the whole sentence went to BM25. "ari," and "can u
 *       look through my" match nothing and drown the two words that matter.</li>
 *   <li>{@code world.library.search} reads the KNOWLEDGE collection. The 74,697
 *       volumes live in STUDY, so the shelves could not find a book by
 *       construction — no query would ever have worked.</li>
 * </ol>
 *
 * <p>(3) is fixed in {@code ItemWorldApiProviderImpl.searchKnowledge}, which now
 * merges consent-granted Study hits; this test covers (1) and (2) and pins the
 * output shape to prose the companion can actually speak.</p>
 */
class LibraryShelvesAnswersFromBooksTest {

    /** The exact sentence the bondholder typed. */
    private static final String THE_UTTERANCE =
        "ari, can u look through my books/library and tell me what significant "
        + "thing did the librarian tell kestan about velsharas in glass tide?";

    private ScriptedItemLoader loader;
    private ItemScriptExecutor executor;

    @BeforeEach
    void setUp() {
        loader = ScriptedItemLoader.get();
        var fromCore = Paths.get("..", "scripts", "items");
        var fromRoot = Paths.get("scripts", "items");
        var dir = Files.isDirectory(fromCore) ? fromCore : fromRoot;
        loader.setSearchDirs(List.of(dir));
        loader.reloadAll();
        executor = new ItemScriptExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.close();
        loader.setSearchDirs(List.of());
        loader.reloadAll();
    }

    private Map<String, Object> invoke(Map<String, Object> params, RecordingProvider p) {
        var def = loader.get("library_shelves").orElseThrow();
        return executor.execute(def.itemId(), def.scriptSource(), params, p);
    }

    // ─── (1) the request must arrive ──────────────────────────────────

    /** THE case: a request injected as `query` must reach the search. */
    @Test
    void a_request_injected_as_query_reaches_the_search() {
        var p = new RecordingProvider(List.of(hit("Glass Tide",
            "The Librarian said: a vel-shara is a speech with power, "
            + "the vel-shara of Adrun was a counter-virus.")));

        var out = invoke(new LinkedHashMap<>(Map.of("query", THE_UTTERANCE)), p);

        assertThat(p.queries)
            .as("the person's request must reach world.library.search — "
                + "reading only args/text/target is why it never did")
            .isNotEmpty();
        assertThat(out.get("ok")).isEqualTo(true);
    }

    /** A bare question is a search, not "no such compartment". */
    @Test
    void a_bare_question_is_treated_as_a_search() {
        var p = new RecordingProvider(List.of(hit("Glass Tide", "…vel-shara of Adrun…")));

        var out = invoke(new LinkedHashMap<>(Map.of("args", "vel-shara of Adrun")), p);

        assertThat(p.queries).isNotEmpty();
        assertThat((String) out.get("text"))
            .as("must not report an unknown compartment for a real question")
            .doesNotContain("no compartment labeled");
    }

    /** The usage screen must still be reachable, and must be MARKED as one. */
    @Test
    void an_empty_invocation_still_returns_help_but_flags_it() {
        var p = new RecordingProvider(List.of());

        var out = invoke(new LinkedHashMap<>(), p);

        assertThat(out.get("help"))
            .as("a usage screen must be distinguishable from a finding, or it "
                + "gets spoken as one")
            .isEqualTo(true);
        assertThat(p.queries).as("no search for a bare 'use'").isEmpty();
    }

    // ─── (2) the query must be a query ────────────────────────────────

    /** The vocative and the fetch-preamble must not reach BM25. */
    @Test
    void the_query_drops_the_vocative_and_the_preamble() {
        var p = new RecordingProvider(List.of(hit("Glass Tide", "…")));

        invoke(new LinkedHashMap<>(Map.of("query", THE_UTTERANCE)), p);

        var q = p.queries.get(0).toLowerCase();
        assertThat(q).as("the address must go: " + q).doesNotStartWith("ari");
        assertThat(q).as("the fetch preamble must go: " + q)
            .doesNotContain("can u look through");
        assertThat(q).as("the substance must survive: " + q)
            .contains("velshara");
    }

    /** Whatever we strip, we must never hand BM25 an empty query. */
    @Test
    void never_searches_for_nothing() {
        var p = new RecordingProvider(List.of());
        for (var utterance : new String[]{
                "ari, what", "hey ari — tell me", "books", "?"}) {
            p.queries.clear();
            invoke(new LinkedHashMap<>(Map.of("query", utterance)), p);
            assertThat(p.queries)
                .allSatisfy(q -> assertThat(q.strip()).isNotEmpty());
        }
    }

    // ─── output shape: speakable, not a digest ────────────────────────

    /**
     * The result must be passages long enough to answer FROM. A 100-char stub
     * plus a chunk id is a machine digest, which is exactly what the never-silent
     * guard refused to speak.
     */
    @Test
    void returns_passages_not_a_catalogue() {
        var body = "The Librarian explained that a vel-shara is a speech with "
            + "power — an incantation that, spoken aloud, reprograms the listener. "
            + "The vel-shara of Adrun was the counter-virus that shattered the "
            + "original tongue and gave humanity its many languages.";
        var p = new RecordingProvider(List.of(hit("Glass Tide", body)));

        var out = invoke(new LinkedHashMap<>(Map.of("query", THE_UTTERANCE)), p);

        var text = (String) out.get("text");
        assertThat(text).contains("vel-shara");
        assertThat(text)
            .as("enough of the passage to answer from, not a 100-char stub")
            .contains("counter-virus");
        assertThat(text).as("chunk ids are plumbing, not speech")
            .doesNotContain("chunk:");
        assertThat(text).as("a usage footer must not ride along on an answer")
            .doesNotContain("use library shelves search");
    }

    /** An honest empty result stays honest — and stays short. */
    @Test
    void an_empty_result_says_so_without_a_usage_dump() {
        var p = new RecordingProvider(List.of());

        var out = invoke(new LinkedHashMap<>(Map.of("query", "vel-shara of Adrun")), p);

        var text = (String) out.get("text");
        assertThat(text).containsIgnoringCase("nothing");
        assertThat(text).doesNotContain("Commands:");
    }

    // ─── fixtures ─────────────────────────────────────────────────────

    private static Map<String, Object> hit(String title, String text) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", "chunk-" + Math.abs(title.hashCode()));
        m.put("title", title);
        m.put("text", text);
        m.put("pack", "study");
        m.put("score", 1.0);
        return m;
    }

    /** Captures what the script actually asked the Library for. */
    static class RecordingProvider implements ItemWorldApiProvider {
        final List<String> queries = new ArrayList<>();
        private final List<Map<String, Object>> hits;

        RecordingProvider(List<Map<String, Object>> hits) { this.hits = hits; }

        @Override
        public List<Map<String, Object>> searchKnowledge(String query, int limit) {
            queries.add(query);
            return hits;
        }

        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int n) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String topic, String type) { return List.of(); }
        @Override public String llmSummarize(String text, String instr) { return ""; }
        @Override public String llmAnalyze(String text, String prompt) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String content) {}
        @Override public void agentTell(String target, String msg) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) {
            return Map.of("error", "not_supported_in_test");
        }
    }
}

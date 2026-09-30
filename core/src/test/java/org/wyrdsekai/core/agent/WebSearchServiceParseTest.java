package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSearchService must accept BOTH the Searxng JSON shape and the metasearch2
 * (mat-1/metasearch2) shape, since metasearch2 is the bundled keyless search
 * backend across every platform (.deb/.pkg/.msi). A prior bug parsed only
 * {@code root.get("results")}, so metasearch2's {@code search_results} envelope
 * silently yielded zero results everywhere it was the active backend.
 */
class WebSearchServiceParseTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode tree(String json) throws Exception {
        return M.readTree(json);
    }

    @Test
    void parsesSearxngShape() throws Exception {
        var root = tree("""
            {"results":[
              {"title":"Linux","url":"https://en.wikipedia.org/wiki/Linux","content":"a kernel"},
              {"title":"Wikipedia","url":"https://wikipedia.org","content":"encyclopedia"}
            ]}""");
        List<WebSearchService.SearchResult> r = WebSearchService.parseSearxngJson(root, 10);
        assertEquals(2, r.size());
        assertEquals("Linux", r.get(0).title());
        assertEquals("https://en.wikipedia.org/wiki/Linux", r.get(0).url());
        assertEquals("a kernel", r.get(0).snippet());
    }

    @Test
    void parsesMetasearch2Shape() throws Exception {
        // Top-level array → search_results[] → {result:{url,title,description}, engines:[...]}
        var root = tree("""
            [{"search_results":[
              {"result":{"url":"https://en.wikipedia.org/wiki/Linux","title":"Linux - Wikipedia","description":"a family of OSes"},"engines":["Brave","Marginalia"],"score":1.25},
              {"result":{"url":"https://wikipedia.org","title":"Wikipedia","description":"free encyclopedia"},"engines":["Bing"],"score":1.0}
            ]}]""");
        List<WebSearchService.SearchResult> r = WebSearchService.parseSearxngJson(root, 10);
        assertEquals(2, r.size());
        assertEquals("Linux - Wikipedia", r.get(0).title());
        assertEquals("https://en.wikipedia.org/wiki/Linux", r.get(0).url());
        assertEquals("a family of OSes", r.get(0).snippet(), "metasearch2 snippet comes from 'description'");
    }

    @Test
    void honoursMaxResults() throws Exception {
        var root = tree("""
            [{"search_results":[
              {"result":{"url":"u1","title":"t1","description":"d1"}},
              {"result":{"url":"u2","title":"t2","description":"d2"}},
              {"result":{"url":"u3","title":"t3","description":"d3"}}
            ]}]""");
        assertEquals(2, WebSearchService.parseSearxngJson(root, 2).size());
    }

    @Test
    void emptyOrUnknownShapeYieldsNoResults() throws Exception {
        assertTrue(WebSearchService.parseSearxngJson(tree("{}"), 10).isEmpty());
        assertTrue(WebSearchService.parseSearxngJson(tree("[]"), 10).isEmpty());
        assertTrue(WebSearchService.parseSearxngJson(tree("{\"unexpected\":1}"), 10).isEmpty());
    }

    @Test
    void readsTheWikipediaAnswerThatComesAsAnInfobox() throws Exception {
        // SearXNG's Wikipedia engine answers an exact article as an infobox, not a result: live on
        // a household node (2026-09-22) "Attention (machine learning)" came back as results: 0,
        // infoboxes: 1, and was read as nothing.
        var root = tree("""
            {"results":[],"infoboxes":[
              {"infobox":"Attention (machine learning)","id":"https://en.wikipedia.org/wiki/Attention_(machine_learning)",
               "content":"In machine learning, attention is a method that determines the importance of each component.",
               "urls":[{"title":"Wikipedia","url":"https://en.wikipedia.org/wiki/Attention_(machine_learning)"}]},
              {"infobox":"Empty","content":""}
            ]}""");
        var r = WebSearchService.parseSearxngJson(root, 10);
        assertEquals(1, r.size(), "an infobox with no text is not an answer");
        assertEquals("Attention (machine learning)", r.get(0).title());
        assertEquals("https://en.wikipedia.org/wiki/Attention_(machine_learning)", r.get(0).url());
        assertTrue(r.get(0).snippet().startsWith("In machine learning, attention"));
    }

    @Test
    void anInfoboxComesFirstAndTheResultsAfterItUpToTheLimit() throws Exception {
        var root = tree("""
            {"infoboxes":[{"infobox":"Transformer","id":"https://en.wikipedia.org/wiki/Transformer_(deep_learning)","content":"A transformer is an architecture."}],
             "results":[{"title":"A","url":"https://a","content":"a"},{"title":"B","url":"https://b","content":"b"}]}""");
        var r = WebSearchService.parseSearxngJson(root, 2);
        assertEquals(List.of("Transformer", "A"), r.stream().map(WebSearchService.SearchResult::title).toList());
    }
}

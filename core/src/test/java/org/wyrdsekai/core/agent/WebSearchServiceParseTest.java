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
}

package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The science-category pass after an empty general search. It ran after every empty list, and
 * an empty list also meant "failed" or "timed out": a hung SearXNG held the companion's actor
 * thread for two 15 s timeouts, and metasearch2 (which has no categories) got the identical query
 * a second time. It may run only after a 200 with no results from a real SearXNG.
 */
class WebSearchServiceScienceRetryTest {

    private static final WebSearchService.SearchResult PAPER = new WebSearchService.SearchResult(
        "Attention Is All You Need", "https://www.semanticscholar.org/paper/attention", "transformer");

    /** Answers each category from the map (null key = the general pass) and records what was asked. */
    private static final class Backend {
        final List<String> asked = new ArrayList<>();
        final Map<String, List<WebSearchService.SearchResult>> answers;

        Backend(List<WebSearchService.SearchResult> general, List<WebSearchService.SearchResult> other) {
            var m = new HashMap<String, List<WebSearchService.SearchResult>>();
            m.put(null, general);
            m.put("news", other);
            m.put("science", other);
            this.answers = m;
        }

        List<WebSearchService.SearchResult> ask(String categories) {
            asked.add(categories);
            return answers.get(categories);
        }
    }

    @Test
    void anEmpty200FromRealSearxngAsksTheScienceEngines() {
        var backend = new Backend(List.of(), List.of(PAPER));
        var r = WebSearchService.withScienceRetry(backend::ask, false, true);
        assertEquals(Arrays.asList(null, "science"), backend.asked);
        assertEquals(List.of(PAPER), r);
    }

    @Test
    void aFailedOrTimedOutGeneralSearchIsNotAskedAgain() {
        // null = non-200 or exception/timeout; a second ask would be a second 15 s wait
        var backend = new Backend(null, List.of(PAPER));
        var r = WebSearchService.withScienceRetry(backend::ask, false, true);
        assertEquals(Arrays.asList((String) null), backend.asked);
        assertTrue(r.isEmpty());
    }

    @Test
    void metasearch2IsNotSentTheSameQueryTwice() {
        var backend = new Backend(List.of(), List.of(PAPER));
        var r = WebSearchService.withScienceRetry(backend::ask, false, false);
        assertEquals(Arrays.asList((String) null), backend.asked);
        assertTrue(r.isEmpty());
    }

    @Test
    void aNewsSearchWithNothingAsksTheGeneralEngines() {
        // Household node, 2026-10-01: all four news engines unresponsive, the general ones had 25.
        var backend = new Backend(List.of(PAPER), List.of());
        var r = WebSearchService.withScienceRetry(backend::ask, true, true);
        assertEquals(Arrays.asList("news", null), backend.asked);
        assertEquals(List.of(PAPER), r);
    }

    @Test
    void aNewsSearchThatAnswersIsNotAskedAgain() {
        var backend = new Backend(List.of(), List.of(PAPER));
        var r = WebSearchService.withScienceRetry(backend::ask, true, true);
        assertEquals(List.of("news"), backend.asked);
        assertEquals(List.of(PAPER), r);
    }

    @Test
    void aNewsSearchOnMetasearch2HasNoSecondPass() {
        var backend = new Backend(List.of(PAPER), List.of());
        var r = WebSearchService.withScienceRetry(backend::ask, true, false);
        assertEquals(List.of("news"), backend.asked);
        assertTrue(r.isEmpty());
    }

    @Test
    void generalResultsAreReturnedWithoutASecondAsk() {
        var backend = new Backend(List.of(PAPER), List.of());
        var r = WebSearchService.withScienceRetry(backend::ask, false, true);
        assertEquals(Arrays.asList((String) null), backend.asked);
        assertEquals(List.of(PAPER), r);
    }

    @Test
    void aFailedSciencePassStillReadsAsNoResults() {
        var backend = new Backend(List.of(), null);
        var r = WebSearchService.withScienceRetry(backend::ask, false, true);
        assertEquals(Arrays.asList(null, "science"), backend.asked);
        assertNotNull(r);
        assertTrue(r.isEmpty());
    }
}

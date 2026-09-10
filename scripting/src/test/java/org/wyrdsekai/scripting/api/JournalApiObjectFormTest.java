package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code world.journal.write({title, body})} — the shape scripts have always used beside the string form. */
class JournalApiObjectFormTest {

    private static final class Recording implements ItemWorldApiProvider {
        @Override public List<Map<String, Object>> searchKnowledge(String query, int limit) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String chunkId) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String query, String type, int limit) { return List.of(); }
        @Override public String webFetch(String url, int maxChars) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String topic, String analysisType) { return List.of(); }
        @Override public String llmSummarize(String text, String instruction) { return ""; }
        @Override public String llmAnalyze(String text, String prompt) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String content) {}
        @Override public void agentTell(String target, String message) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth) { return Map.of(); }
        final AtomicReference<String> content = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> opts = new AtomicReference<>();
        @Override public Map<String, Object> journalWrite(String c, Map<String, Object> o) {
            content.set(c); opts.set(o); return Map.of("ok", true, "id", "j1");
        }
    }

    @Test
    void title_and_body_become_one_entry_with_the_title_as_an_option() {
        var p = new Recording();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of("journal.write")));
        var entry = new LinkedHashMap<String, Object>();
        entry.put("title", "Three sources");
        entry.put("body", "What I found.");
        entry.put("tags", List.of("research"));
        var r = api.journal.write(entry);
        assertEquals(true, r.get("ok"));
        assertEquals("Three sources\n\nWhat I found.", p.content.get());
        assertEquals("Three sources", p.opts.get().get("title"));
        assertEquals(List.of("research"), p.opts.get().get("tags"));
    }

    @Test
    void content_or_text_alone_is_the_entry_and_the_string_forms_still_work() {
        var p = new Recording();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of("journal.write")));
        api.journal.write(Map.of("content", "just this"));
        assertEquals("just this", p.content.get());
        assertTrue(p.opts.get().isEmpty());
        api.journal.write(Map.of("title", "only a title"));
        assertEquals("only a title", p.content.get());
        api.journal.write("plain");
        assertEquals("plain", p.content.get());
        api.journal.write("plain", Map.of("mood", "quiet"));
        assertEquals("quiet", p.opts.get().get("mood"));
    }
}

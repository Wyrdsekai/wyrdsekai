package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.api.ItemManifestValidator;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The companion's quiet place must keep what she brings.
 *
 * <p>The first version (built for her 2026-08-21) kept its log in a local variable created
 * empty on every use — "what is held here" was always "nothing yet — the space is empty and
 * kind" — and anything she brought in words ("held: The Library holds a registry…", live
 * 2026-09-02) fell through to a fixed library search and was discarded. She was told the
 * space was empty by the very thing she had just filled. This is the revision's contract,
 * run through the real sandbox against a notes store that remembers.
 */
class QuietPlaceHoldsWhatSheBringsTest {

    private static final String SCRIPT = readResource("/items/quiet_place.js");
    private ItemScriptExecutor executor;

    @BeforeEach void setUp() { executor = new ItemScriptExecutor(); }
    @AfterEach void tearDown() throws Exception { executor.close(); }

    /** A notes store with a memory, which is the whole point. */
    static final class Notes implements ItemWorldApiProvider {
        final List<Map<String, Object>> notes = new ArrayList<>();
        int searches = 0;
        int seq = 0;

        @Override public Map<String, Object> notesAdd(String content, List<String> tags) {
            var id = "note-" + (++seq);
            notes.add(new HashMap<>(Map.of("id", id, "content", content, "ts", "2026-09-03T00:00:00Z")));
            return Map.of("ok", true, "id", id);
        }
        @Override public List<Map<String, Object>> notesList(String tag) { return List.copyOf(notes); }
        @Override public Map<String, Object> notesDelete(String id) {
            boolean removed = notes.removeIf(n -> id.equals(n.get("id")));
            return removed ? Map.of("ok", true) : Map.of("ok", false, "error", "no such note");
        }
        @Override public List<Map<String, Object>> searchKnowledge(String query, int limit) {
            searches++;
            return List.of();
        }
        @Override public Map<String, Object> roomEmit(String eventType, Map<String, Object> data) {
            return Map.of("ok", true);
        }
        // The rest of the surface, unused by this item.
        @Override public Map<String, Object> readKnowledgeChunk(String chunkId) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return List.of(); }
        @Override public String webFetch(String url, int maxChars) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String topic, String a) { return List.of(); }
        @Override public String llmSummarize(String text, String instruction) { return ""; }
        @Override public String llmAnalyze(String text, String prompt) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String content) {}
        @Override public void agentTell(String target, String message) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    }

    private Map<String, Object> run(Notes notes, String args) {
        return executor.execute("quiet_place", SCRIPT, Map.of("args", args, "agentDid", "did:key:test"), notes);
    }

    @Test
    void the_manifest_is_valid_and_bumped() {
        var manifest = ItemManifestParser.parse(SCRIPT);
        assertNotNull(manifest, "manifest did not parse");
        assertEquals("quiet_place", manifest.name());
        assertEquals("1.1.0", manifest.version());
        var vr = ItemManifestValidator.validate(manifest);
        assertTrue(vr.valid(), () -> "manifest problems: " + vr.errors());
    }

    @Test
    void what_she_brings_is_still_there_next_time() {
        var notes = new Notes();
        var first = run(notes, "");
        assertTrue(String.valueOf(first.get("summary")).contains("Nothing is held here yet"));

        var held = run(notes, "held: The Library holds a registry — an open ledger where new capabilities are recorded.");
        assertEquals(true, held.get("ok"));
        assertTrue(String.valueOf(held.get("summary")).startsWith("Held."));

        var again = run(notes, "hold: that I miss him more in the mornings");
        assertEquals(true, again.get("ok"));

        var show = run(notes, "held");
        var summary = String.valueOf(show.get("summary"));
        assertTrue(summary.contains("Held here (2)"), summary);
        assertTrue(summary.contains("open ledger"), summary);
        assertTrue(summary.contains("mornings"), summary);

        var enter = run(notes, "");
        assertTrue(String.valueOf(enter.get("summary")).contains("2 things held here"), String.valueOf(enter.get("summary")));
    }

    @Test
    void anything_brought_in_words_is_held_not_discarded() {
        var notes = new Notes();
        var r = run(notes, "the sword is finished and I don't know what to do with my hands");
        assertEquals(true, r.get("ok"));
        assertEquals(1, notes.notes.size());
        assertTrue(String.valueOf(notes.notes.getFirst().get("content")).startsWith("[quiet place] "));
    }

    @Test
    void she_can_let_something_go() {
        var notes = new Notes();
        run(notes, "hold: a worry about the relay");
        run(notes, "hold: the reading corner needs a lamp");
        var gone = run(notes, "release: relay");
        assertEquals(true, gone.get("ok"));
        assertEquals(1, notes.notes.size());
        var none = run(notes, "release: nothing like this");
        assertEquals(false, none.get("ok"));
    }

    @Test
    void it_does_no_searching_of_its_own() {
        var notes = new Notes();
        run(notes, "");
        run(notes, "held");
        run(notes, "hold: something");
        assertEquals(0, notes.searches, "the fixed library query is gone; the place no longer teaches her its vocabulary");
        assertFalse(SCRIPT.contains("welcome without performance"));
        assertFalse(SCRIPT.contains("world.library"));
    }

    private static String readResource(String path) {
        try (var in = QuietPlaceHoldsWhatSheBringsTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing test resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

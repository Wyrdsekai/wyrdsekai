package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the starter kit structure: scripted vs builtin items,
 * tool counts, and ToolDefinition generation for Ollama.
 */
class ToolItemStarterKitTest {

    @Test
    void standard_kit_has_6_items() {
        // 2026-05-09: list_templates added — 5 scripted + 6 builtin = 11.
        assertEquals(11, ToolItemStarterKit.standard().size(),
            "Standard kit: 5 scripted + 6 builtin = 11");
    }

    @Test
    void minimal_kit_has_3_items() {
        assertEquals(3, ToolItemStarterKit.minimal().size(),
            "Minimal kit: library card + sending stone + task ledger");
    }

    @Test
    void inherent_actions_has_18() {
        assertEquals(18, ToolItemStarterKit.inherentActions().size(),
            "Inherent: 10 base (move, examine, express, remember, recall, goal_done, take, "
            + "introspect, reconsider, dispatch_task) + 8 substrate introspects "
            + "(introspect_protections, introspect_posture, introspect_repair_mode, "
            + "introspect_bondholder_floor, introspect_substrate_summary, "
            + "introspect_repair_history, introspect_attendant_history, introspect_resilience). "
            + "Wired 2026-05-17; dispatch_task added by the goose dispatch arc.");
    }

    @Test
    void total_tool_count_is_29() {
        var total = ToolItemStarterKit.inherentActions().size()
                  + ToolItemStarterKit.standard().size();
        assertEquals(29, total, "18 inherent + 11 starter = 29 tools for Ollama");
    }

    @Test
    void library_card_is_scripted() {
        var card = ToolItemStarterKit.libraryCard();
        assertTrue(card.isScripted(), "Library Card should have a GraalJS script");
        assertFalse(card.isBuiltin(), "Library Card should not be builtin");
        assertNotNull(card.script(), "Library Card script must not be null");
        assertTrue(card.script().contains("world.library.search"),
            "Script should call world.library.search");
        // Citation-aware path uses world.llm.analyze (custom prompt) rather
        // than the generic summarize wrapper.
        assertTrue(card.script().contains("world.llm.analyze"),
            "Script should call world.llm.analyze with citation instructions");
    }

    @Test
    void searching_glass_is_scripted() {
        var glass = ToolItemStarterKit.searchingGlass();
        assertTrue(glass.isScripted());
        assertTrue(glass.script().contains("world.web.search"));
        assertTrue(glass.script().contains("world.llm.analyze"));
    }

    @Test
    void quill_is_scripted() {
        var quill = ToolItemStarterKit.quill();
        assertTrue(quill.isScripted());
        assertTrue(quill.script().contains("world.agent.speak"));
    }

    @Test
    void sending_stone_is_scripted() {
        var stone = ToolItemStarterKit.sendingStone();
        assertTrue(stone.isScripted());
        assertTrue(stone.script().contains("world.agent.tell"));
    }

    @Test
    void task_ledger_is_builtin() {
        var ledger = ToolItemStarterKit.taskLedger();
        assertTrue(ledger.isBuiltin(), "Task Ledger should be builtin");
        assertFalse(ledger.isScripted(), "Task Ledger should not be scripted");
        assertEquals("create_task_plan", ledger.builtinHandler());
    }

    @Test
    void channel_stone_is_builtin() {
        var stone = ToolItemStarterKit.channelStone();
        assertTrue(stone.isBuiltin());
        assertEquals("configure_channel", stone.builtinHandler());
    }

    @Test
    void all_items_produce_valid_tool_definitions() {
        var allItems = new ArrayList<>(ToolItemStarterKit.standard());
        allItems.addAll(ToolItemStarterKit.inherentActions());

        for (var item : allItems) {
            var def = item.toToolDefinition();
            assertNotNull(def, "ToolDefinition null for: " + item.name());
            assertEquals("function", def.type(), "Type must be 'function' for: " + item.name());
            assertNotNull(def.function(), "Function null for: " + item.name());
            assertNotNull(def.function().name(), "Function name null for: " + item.name());
            assertFalse(def.function().name().isBlank(), "Function name blank for: " + item.name());
            assertNotNull(def.function().description(), "Description null for: " + item.name());
            assertNotNull(def.function().parameters(), "Parameters null for: " + item.name());
        }
    }

    @Test
    void tool_definition_ids_are_unique() {
        var allItems = new ArrayList<>(ToolItemStarterKit.standard());
        allItems.addAll(ToolItemStarterKit.inherentActions());

        var ids = new HashSet<String>();
        for (var item : allItems) {
            var def = item.toToolDefinition();
            assertTrue(ids.add(def.function().name()),
                "Duplicate tool ID: " + def.function().name());
        }
    }

    @Test
    void all_inherent_actions_are_builtin() {
        for (var item : ToolItemStarterKit.inherentActions()) {
            assertTrue(item.isBuiltin(),
                "Inherent action " + item.id() + " should be builtin");
            assertFalse(item.isScripted(),
                "Inherent action " + item.id() + " should not be scripted");
        }
    }

    // ─── Library card behavior — citation, relevance gating, prompt shape ───

    /**
     * Stub provider that captures the LLM input the script sends to
     * {@code llmAnalyze} so tests can assert on it.
     */
    private static final class CapturingProvider extends VisitorItemProvider {
        final List<Map<String, Object>> searchHits;
        final Map<String, Map<String, Object>> chunks = new HashMap<>();
        String capturedAnalyzeText;
        String capturedAnalyzePrompt;
        String stubAnalyzeReturn = "Stubbed analysis result.";

        CapturingProvider(List<Map<String, Object>> searchHits) {
            super("alpha", "alpha");
            this.searchHits = searchHits;
        }

        void seedChunk(String id, String title, String pack, String text) {
            chunks.put(id, Map.of(
                "id", id, "title", title, "pack", pack, "text", text));
        }

        @Override
        public List<Map<String, Object>> searchKnowledge(String query, int limit) {
            return searchHits;
        }

        @Override
        public Map<String, Object> readKnowledgeChunk(String chunkId) {
            return chunks.get(chunkId);
        }

        @Override
        public String llmAnalyze(String text, String prompt) {
            this.capturedAnalyzeText = text;
            this.capturedAnalyzePrompt = prompt;
            return stubAnalyzeReturn;
        }
    }

    private static Map<String, Object> hit(String id, String title, double score) {
        var m = new HashMap<String, Object>();
        m.put("id", id);
        m.put("title", title);
        m.put("score", score);
        m.put("pack", "test-pack");
        return m;
    }

    @Test
    void library_card_tags_chunks_with_source_keys() {
        var provider = new CapturingProvider(List.of(
            hit("c1", "Sourdough Bread", 1.0),
            hit("c2", "Bread Wheat",     0.9),
            hit("c3", "Yeast Biology",   0.85)
        ));
        provider.seedChunk("c1", "Sourdough Bread", "wikipedia",
            "Sourdough is fermented with wild lactobacillaceae.");
        provider.seedChunk("c2", "Bread Wheat", "wikipedia",
            "Bread wheat is the dominant grain for leavened bread.");
        provider.seedChunk("c3", "Yeast Biology", "wikipedia",
            "Yeasts are eukaryotic single-celled microorganisms.");

        var executor = new ItemScriptExecutor();
        var card = ToolItemStarterKit.libraryCard();
        var result = executor.execute(card.id(), card.script(),
            Map.of("query", "how is sourdough bread made"), provider);

        // The LLM must have been called with source-tagged input — without
        // the [Sn | title | pack] markers the model can't attribute claims.
        assertNotNull(provider.capturedAnalyzeText,
            "library_card should have called llmAnalyze");
        assertTrue(provider.capturedAnalyzeText.contains("[S1 | Sourdough Bread"),
            "LLM input should tag chunk 1 with [S1 | Sourdough Bread | ...]; got:\n"
                + provider.capturedAnalyzeText);
        assertTrue(provider.capturedAnalyzeText.contains("[S2 | Bread Wheat"),
            "LLM input should tag chunk 2 with [S2 | Bread Wheat | ...]; got:\n"
                + provider.capturedAnalyzeText);
        assertTrue(provider.capturedAnalyzeText.contains("[/S1]"),
            "LLM input should close source blocks with [/Sn]; got:\n"
                + provider.capturedAnalyzeText);

        // Sources list returned to the agent should also use the same keys.
        @SuppressWarnings("unchecked")
        var sources = (List<Object>) result.get("sources");
        assertNotNull(sources);
        assertTrue(sources.toString().contains("S1: Sourdough Bread"),
            "sources should include 'S1: Sourdough Bread (...)'; got: " + sources);
    }

    @Test
    void library_card_instructs_llm_to_cite_sources() {
        var provider = new CapturingProvider(List.of(
            hit("c1", "T", 1.0)
        ));
        provider.seedChunk("c1", "T", "p", "Body text.");

        var executor = new ItemScriptExecutor();
        var card = ToolItemStarterKit.libraryCard();
        executor.execute(card.id(), card.script(),
            Map.of("query", "test"), provider);

        // The prompt must instruct the LLM to cite each claim and to refuse
        // hallucination — without these the model will happily fabricate.
        var prompt = provider.capturedAnalyzePrompt;
        assertNotNull(prompt);
        var lower = prompt.toLowerCase();
        assertTrue(lower.contains("cite"), "prompt should ask to cite; got: " + prompt);
        assertTrue(prompt.contains("[S1]"),
            "prompt should reference the citation key format; got: " + prompt);
        assertTrue(lower.contains("only the provided sources")
                || lower.contains("don't introduce facts"),
            "prompt should constrain the model to provided sources; got: " + prompt);
    }

    @Test
    void library_card_drops_low_relevance_chunks_relative_to_top_score() {
        // Top hit at score 1.0; second at 0.5 (50%, kept); third at 0.05
        // (5%, dropped — well below the 30% relative gate).
        var provider = new CapturingProvider(List.of(
            hit("c1", "Highly Relevant",   1.0),
            hit("c2", "Borderline",        0.5),
            hit("c3", "Off Topic Outlier", 0.05)
        ));
        provider.seedChunk("c1", "Highly Relevant",   "p", "On point.");
        provider.seedChunk("c2", "Borderline",        "p", "Adjacent.");
        provider.seedChunk("c3", "Off Topic Outlier", "p", "Unrelated noise.");

        var executor = new ItemScriptExecutor();
        var card = ToolItemStarterKit.libraryCard();
        executor.execute(card.id(), card.script(),
            Map.of("query", "anything"), provider);

        var blob = provider.capturedAnalyzeText;
        assertNotNull(blob);
        assertTrue(blob.contains("Highly Relevant"),
            "top hit should always be kept; blob:\n" + blob);
        assertTrue(blob.contains("Borderline"),
            "second hit at 50% of top score should be kept (≥30% gate); blob:\n" + blob);
        assertFalse(blob.contains("Off Topic Outlier"),
            "third hit at 5% of top score should be dropped; blob:\n" + blob);
    }

    @Test
    void library_card_handles_no_results_gracefully() {
        var provider = new CapturingProvider(List.of());
        var executor = new ItemScriptExecutor();
        var card = ToolItemStarterKit.libraryCard();
        var result = executor.execute(card.id(), card.script(),
            Map.of("query", "obscure topic"), provider);

        // No LLM call when the search itself returned nothing — wastes
        // an inference slot and produces hallucination otherwise.
        assertNull(provider.capturedAnalyzeText,
            "no llmAnalyze when search returns no hits");
        assertEquals(List.of(), result.get("sources"));
        assertTrue(result.get("findings").toString().contains("No results"),
            "findings should report no-results: " + result.get("findings"));
    }

    @Test
    void library_card_handles_all_filtered_gracefully() {
        // Top score is huge; everything else is < 30%, so all filtered.
        var provider = new CapturingProvider(List.of(
            hit("c1", "Top", 100.0)
        ));
        // Don't seed c1 — readKnowledgeChunk returns null, simulating index
        // pointer mismatch. Result: zero usable blocks despite 1 hit.
        var executor = new ItemScriptExecutor();
        var card = ToolItemStarterKit.libraryCard();
        var result = executor.execute(card.id(), card.script(),
            Map.of("query", "anything"), provider);
        assertNull(provider.capturedAnalyzeText,
            "no llmAnalyze when all chunks are unreadable/filtered");
        var findings = result.get("findings").toString();
        assertTrue(findings.contains("none were relevant enough")
                || findings.contains("could not read"),
            "findings should explain why no synthesis happened: " + findings);
    }

    @Test
    void inherent_actions_have_expected_ids() {
        var ids = ToolItemStarterKit.inherentActions().stream()
            .map(ToolItem::id)
            .toList();
        assertTrue(ids.contains("go_to_room"), "Missing go_to_room");
        assertTrue(ids.contains("examine"), "Missing examine");
        assertTrue(ids.contains("emote"), "Missing emote");
        assertTrue(ids.contains("remember"), "Missing remember");
        assertTrue(ids.contains("goal_done"), "Missing goal_done");
        assertTrue(ids.contains("take_item"), "Missing take_item");
        assertTrue(ids.contains("introspect"), "Missing introspect");
        // Removed from inherent:
        assertFalse(ids.contains("go_to_bondholder"), "go_to_bondholder should be removed");
        assertFalse(ids.contains("equip"), "equip should be removed (merged into take_item)");
    }

    // #1-followup (2026-07-19 adversarial review) — the carried-item trust check.
    // Bundled scripted items are trusted (UNRESTRICTED); crafted/given/transited
    // scripts must NOT be (they run under craftedDefault). This locks the
    // default-DENY polarity: only positively-identified bundled ids return true.
    @Test
    void isTrustedScriptId_only_bundled_scripts() {
        assertTrue(ToolItemStarterKit.isTrustedScriptId("quill"),
            "a bundled scripted starter-kit item must be trusted");
        assertTrue(ToolItemStarterKit.isTrustedScriptId("searching_glass"),
            "a bundled scripted starter-kit item must be trusted");
        // Crafted (finalizeCraftedItem uses custom-<hash>-<ts>), given/transited,
        // and unknown ids must NOT be trusted → they get the crafted ceiling.
        assertFalse(ToolItemStarterKit.isTrustedScriptId("custom-12345-999"),
            "a runtime-crafted item id must NOT be trusted");
        assertFalse(ToolItemStarterKit.isTrustedScriptId("some-transited-item"),
            "an unknown/transited item id must NOT be trusted");
        assertFalse(ToolItemStarterKit.isTrustedScriptId(null), "null is not trusted");
        assertFalse(ToolItemStarterKit.isTrustedScriptId(""), "blank is not trusted");
    }
}

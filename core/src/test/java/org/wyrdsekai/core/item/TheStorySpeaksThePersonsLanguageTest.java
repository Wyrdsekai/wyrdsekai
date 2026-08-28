package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.OpenHandsBackend;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The language of what an item FINDS must not decide the language of what it
 * SAYS.
 *
 * <h2>What went wrong</h2>
 * Home node, 2026-08-24: {@code use library_fairytale glass tide} — an English
 * speaker, an English request — returned the whole tale in Spanish, because
 * the library hits happened to be Spanish catalog rows and the item passed
 * them to {@code llm.complete} with no language instruction. The item could
 * not have done better: {@code invoke(params)} never carried the caller's
 * locale, so no item ever knew what language its person speaks.
 */
class TheStorySpeaksThePersonsLanguageTest {

    @Test
    @DisplayName("invoke params carry the caller's locale")
    void paramsCarryTheLocale() {
        assertThat(CarriedItemUse.params("p1", "glass tide", "ja"))
            .containsEntry("locale", "ja");
        // Callers with no locale in hand still hand the item a real value.
        assertThat(CarriedItemUse.params("p1", "glass tide"))
            .containsEntry("locale", "en");
        assertThat(CarriedItemUse.params("p1", "x", "  "))
            .containsEntry("locale", "en");
    }

    /** Captures what reaches the household delegate. */
    private static final class CapturingHome implements ItemWorldApiProvider {
        final Map<String, Object> seen = new HashMap<>();
        @Override public Map<String, Object> llmComplete(String prompt, Map<String, Object> opts) {
            seen.put("prompt", prompt); seen.put("opts", opts); return Map.of("text", "ok");
        }
        @Override public String llmSummarize(String text, String instruction) {
            seen.put("instruction", instruction); return "ok";
        }
        @Override public String llmAnalyze(String text, String prompt) {
            seen.put("prompt", prompt); return "ok";
        }
        @Override public Map<String, Object> inventoryUse(String itemId,
                Map<String, Object> params, int depth) {
            return Map.of();
        }
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return List.of(); }
        @Override public String webFetch(String url, int maxChars) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public void agentSpeak(String text) { }
        @Override public void agentRemember(String content) { }
        @Override public void agentTell(String target, String message) { }
    }

    @Test
    @DisplayName("the runtime appends a BLUNT language line at the prompt tail")
    void theRuntimeOwnsTheDefault() {
        var home = new CapturingHome();
        var p = new VisitorItemProvider("z", "z")
            .withHouseholdContent(home).withCallerLocale("en");

        // Blunt and last: the first cut used a yielding conditional in the
        // SYSTEM prompt and a 9B under a page of Spanish material walked past
        // it twice (dev10, home node). The imperative rides the tail of the
        // user prompt — the strongest position a small model has.
        p.llmComplete("tell a tale about what was found", null);
        assertThat(String.valueOf(home.seen.get("prompt")))
            .endsWith("Write your answer in English.");

        // Conditionality lives in CODE: an item that names its language is
        // left entirely alone.
        home.seen.clear();
        p.llmComplete("retell this in Spanish for the visiting cousin", null);
        assertThat(String.valueOf(home.seen.get("prompt")))
            .doesNotContain("Write your answer in English");

        p.llmSummarize("hallazgos", "two paragraphs");
        assertThat(String.valueOf(home.seen.get("instruction"))).contains("English");
        p.llmAnalyze("hallazgos", "what stands out?");
        assertThat(String.valueOf(home.seen.get("prompt"))).contains("English");
    }

    @Test
    @DisplayName("with no locale attached, prompts pass through untouched")
    void noLocaleNoInjection() {
        var home = new CapturingHome();
        var p = new VisitorItemProvider("z", "z").withHouseholdContent(home);
        p.llmComplete("x", Map.of("system", "You are a bard."));
        assertThat(String.valueOf(home.seen.get("prompt"))).isEqualTo("x");
    }

    @Test
    @DisplayName("the preamble teaches params.locale and the found-vs-said rule")
    void thePreambleTeachesTheRule() {
        var preamble = OpenHandsBackend.itemsAsToolsPreambleCwd(
            ItemCapabilitySet.craftedDefault());
        assertThat(preamble).contains("params.locale");
        assertThat(preamble)
            .as("prose for the person is in THEIR language, whatever the sources speak")
            .contains("SAY WHICH LANGUAGE");
        assertThat(preamble)
            .as("adapter results are maps, not strings — the startsWith death "
                + "(2026-08-25) is taught as a named mistake")
            .contains("Unknown identifier: startsWith");
    }
}

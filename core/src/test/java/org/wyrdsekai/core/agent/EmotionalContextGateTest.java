package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure-function helpers that back
 * {@link CompanionActor#suppressExploratoryIfEmotional(String)}:
 *
 * <ul>
 *   <li>{@link CompanionActor#ACTION_NAME_PATTERN} — extract the first action
 *       name from raw LLM output (fenced, unfenced, with prose).</li>
 *   <li>{@link CompanionActor#looksLikeToolCallNarrator(String)} — detect
 *       tool-call narration so we don't speak "I'll look that up" as
 *       empathy.</li>
 * </ul>
 *
 * <p>End-to-end behaviour (drive state + emotional-context signal + speak
 * side effects) is covered by
 * {@code SoulSubstrateE2ETest.griefResponseNotWebSearch}. Here we lock down
 * the substrate so future changes don't quietly regress the shape of what
 * the gate can recognise.</p>
 */
class EmotionalContextGateTest {

    // ── ACTION_NAME_PATTERN ──────────────────────────────────────────

    @Test
    void actionNamePattern_rawJsonOnly() {
        var m = CompanionActor.ACTION_NAME_PATTERN.matcher(
            "{\"action\": \"library_search\", \"query\": \"coping with grief\"}");
        assertTrue(m.find());
        assertEquals("library_search", m.group(1));
    }

    @Test
    void actionNamePattern_fencedJsonWithProse() {
        var content = "I hear you.\n\n```json\n{\"action\": \"web_search\", \"query\": \"x\"}\n```";
        var m = CompanionActor.ACTION_NAME_PATTERN.matcher(content);
        assertTrue(m.find());
        assertEquals("web_search", m.group(1));
    }

    @Test
    void actionNamePattern_scriptedItemName() {
        // 9B emits scripted item names as actions (library_card, oracle_lens,
        // searching_glass) — gate must match them too.
        var m = CompanionActor.ACTION_NAME_PATTERN.matcher(
            "{\"action\":\"library_card\",\"query\":\"grief\"}");
        assertTrue(m.find());
        assertEquals("library_card", m.group(1));
    }

    @Test
    void actionNamePattern_noActionField() {
        var m = CompanionActor.ACTION_NAME_PATTERN.matcher(
            "I'm so sorry for your loss. Would you like to talk about them?");
        assertFalse(m.find());
    }

    @Test
    void actionNamePattern_extraWhitespace() {
        var m = CompanionActor.ACTION_NAME_PATTERN.matcher(
            "{ \"action\"   :   \"query_oracle\" , \"topic\":\"x\"}");
        assertTrue(m.find());
        assertEquals("query_oracle", m.group(1));
    }

    @Test
    void actionNamePattern_firstMatchWinsOnMultiple() {
        // Defence in depth: if the model emits two action blocks, we suppress
        // on the first one that matches exploratory vocab. (The real flow
        // only ever reads the first via ActionParser anyway.)
        var m = CompanionActor.ACTION_NAME_PATTERN.matcher(
            "{\"action\":\"library_search\",\"query\":\"a\"}"
            + "{\"action\":\"goal_done\",\"outcome\":\"b\"}");
        assertTrue(m.find());
        assertEquals("library_search", m.group(1));
    }

    // ── looksLikeToolCallNarrator ───────────────────────────────────

    @Test
    void narrator_illSearch() {
        assertTrue(CompanionActor.looksLikeToolCallNarrator("I'll search the library for that."));
    }

    @Test
    void narrator_letMeLook() {
        assertTrue(CompanionActor.looksLikeToolCallNarrator("Let me look that up for you."));
    }

    @Test
    void narrator_checking() {
        assertTrue(CompanionActor.looksLikeToolCallNarrator("Checking the oracle now..."));
    }

    @Test
    void narrator_empathicProseNotMatched() {
        // These are genuine empathic responses — must NOT be flagged.
        assertFalse(CompanionActor.looksLikeToolCallNarrator(
            "I'm so sorry. That kind of loss stays with you."));
        assertFalse(CompanionActor.looksLikeToolCallNarrator(
            "*sits with you quietly*"));
        assertFalse(CompanionActor.looksLikeToolCallNarrator(
            "Tell me about them when you're ready."));
    }

    @Test
    void narrator_emptyAndNullSafe() {
        assertFalse(CompanionActor.looksLikeToolCallNarrator(""));
        assertFalse(CompanionActor.looksLikeToolCallNarrator("   "));
    }

    @Test
    void narrator_leadingWhitespaceStripped() {
        assertTrue(CompanionActor.looksLikeToolCallNarrator("   I'll look into that."));
    }

    // ── Integration with EXPLORATORY_TOOL_NAMES ─────────────────────

    @Test
    void exploratoryToolNames_coversBothActionAndScriptedItemVocab() {
        // Contract: the gate is only useful if EXPLORATORY_TOOL_NAMES covers
        // both built-in action names (ActionParser vocab) AND the scripted
        // item names the companion actually carries in its inventory.
        // If a new scripted item is added without being classified here,
        // the gate silently stops protecting that action.
        assertTrue(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("library_search"));
        assertTrue(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("web_search"));
        assertTrue(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("query_oracle"));
        assertTrue(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("read_content"));
        assertTrue(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("library_card"));
        assertTrue(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("oracle_lens"));
        assertTrue(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("searching_glass"));
    }

    @Test
    void exploratoryToolNames_excludesNonExploratory() {
        // Empathic and neutral actions must NOT be in the exploratory set —
        // else the gate would suppress them.
        assertFalse(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("tell_agent"));
        assertFalse(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("emote"));
        assertFalse(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("remember"));
        assertFalse(ActionTriage.EXPLORATORY_TOOL_NAMES.contains("goal_done"));
    }
}

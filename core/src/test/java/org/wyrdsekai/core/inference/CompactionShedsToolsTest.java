package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the messages have nothing left to give, the tools go — the ranked head and the
 * escape hatch first, then all of them.
 *
 * <p>Household node, 2026-09-12: six turns in six hours ended in {@code Context overflow …
 * nothing left to compact after round 1 of 3: 3 message(s) [system:64, user:18,
 * assistant:64], tools=188}. The 188 schemas were ~17k tokens against a 16,384 window; the
 * three messages were a few hundred. The compactor knew only how to shrink messages, so
 * every one of those turns failed permanently and her judgment after the tool result never
 * ran.</p>
 */
class CompactionShedsToolsTest {

    private static InferenceClient.ChatMessage msg(String role, String content) {
        return new InferenceClient.ChatMessage(role, content);
    }

    private static InferenceClient.ToolDefinition tool(String name) {
        return InferenceClient.ToolDefinition.function(name,
            "A tool called " + name + ". " + "description ".repeat(30),
            Map.of("type", "object", "properties", Map.of(
                "query", Map.of("type", "string", "description", "what to do with it"),
                "mode", Map.of("type", "string", "description", "how"))));
    }

    private static InferenceClient.ChatRequest req(List<InferenceClient.ToolDefinition> tools) {
        return new InferenceClient.ChatRequest("m",
            // Each under the shear floor: the messages have nothing left to give, as on the
            // household node (system:64, user:18, assistant:64).
            List.of(msg("system", "You are a companion. " + "word ".repeat(20)),
                    msg("user", "[Tool completed] Held. It stays here until you let it go."),
                    msg("assistant", "I held it. " + "word ".repeat(20))),
            1024, 0.7, null, null, null, null, tools, "auto", null, null, null);
    }

    @Test
    @DisplayName("188 tools and three short messages: the ranked head and the hatch survive, the rest go")
    void shedsToTheRankedHead() {
        var tools = new ArrayList<InferenceClient.ToolDefinition>();
        for (int i = 0; i < 188; i++) tools.add(tool("tool_" + i));
        tools.add(100, tool("use_item"));                        // the hatch, wherever it sat
        var request = req(tools);
        int toolTokens = InferenceRouter.estimateToolTokens(tools);
        assertTrue(toolTokens > 16384, "the fixture must overflow on tools alone: " + toolTokens);

        var out = InferenceRouter.compactToFit(request, 16384, 17131, 1);
        assertNotNull(out, "a prompt that is all tools can still be made to fit");
        assertNotNull(out.tools());
        assertEquals(InferenceRouter.COMPACT_TOOL_HEAD + 1, out.tools().size());
        for (int i = 0; i < InferenceRouter.COMPACT_TOOL_HEAD; i++) {
            assertEquals("tool_" + i, out.tools().get(i).function().name(), "order is rank — keep the head");
        }
        assertEquals("use_item", out.tools().getLast().function().name(), "the hatch is never shed");
        assertEquals("auto", out.toolChoice());
        assertEquals(3, out.messages().size(), "the messages were not the problem");
    }

    @Test
    @DisplayName("a short list that still does not fit is dropped whole, and tool_choice with it")
    void dropsAShortListWhole() {
        var tools = new ArrayList<InferenceClient.ToolDefinition>();
        for (int i = 0; i < 6; i++) tools.add(tool("tool_" + i));
        var out = InferenceRouter.compactToFit(req(tools), 16384, 17131, 1);
        assertNotNull(out);
        assertNull(out.tools(), "six schemas cannot be halved into fitting — send none");
        assertNull(out.toolChoice(), "no tools, no tool_choice");
    }

    @Test
    @DisplayName("while the messages can still give, the tools stay — shedding is the last round's move")
    void messagesFirst() {
        var tools = new ArrayList<InferenceClient.ToolDefinition>();
        for (int i = 0; i < 188; i++) tools.add(tool("tool_" + i));
        var withHistory = new InferenceClient.ChatRequest("m",
            List.of(msg("system", "You are a companion."),
                    msg("user", "earlier " + "word ".repeat(200)),
                    msg("assistant", "earlier reply " + "word ".repeat(200)),
                    msg("user", "[Tool completed] Held."),
                    msg("assistant", "I held it.")),
            1024, 0.7, null, null, null, null, tools, "auto", null, null, null);
        var out = InferenceRouter.compactToFit(withHistory, 16384, 17131, 1);
        assertNotNull(out);
        assertEquals(188, out.tools().size(), "history went first; the tools are untouched this round");
        assertTrue(out.messages().size() < 5, "a history turn was dropped");
    }

    @Test
    @DisplayName("without tools, a prompt with nothing to give still fails honestly")
    void noToolsStillNull() {
        var short3 = new InferenceClient.ChatRequest("m",
            List.of(msg("system", "brief"), msg("user", "hi"), msg("assistant", "hello")),
            1024, 0.7, null, null, null, null, null, null, null, null, null);
        assertNull(InferenceRouter.compactToFit(short3, 16384, 17131, 1));
    }

    @Test
    @DisplayName("keepRankedHead keeps order and finds the hatch anywhere")
    void keepRankedHead() {
        var tools = List.of(tool("a"), tool("b"), tool("use_item"), tool("c"), tool("d"));
        var kept = InferenceRouter.keepRankedHead(tools, 2);
        assertEquals(List.of("a", "b", "use_item"),
            kept.stream().map(t -> t.function().name()).toList());
        assertNull(InferenceRouter.keepRankedHead(null, 2));
    }
}

package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceClient.ChatMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationCompressorTest {

    @Test
    void history_under_threshold_unchanged() {
        var history = List.of(
            new ChatMessage("user", "hello"),
            new ChatMessage("assistant", "hi there")
        );
        var result = ConversationCompressor.compress(history, 4096, 512);
        assertEquals(history, result);
    }

    @Test
    void null_or_small_history_unchanged() {
        assertNull(ConversationCompressor.compress(null, 4096, 512));
        var small = List.of(new ChatMessage("user", "hi"));
        assertEquals(small, ConversationCompressor.compress(small, 4096, 512));
    }

    @Test
    void large_history_compressed_keeps_recent() {
        // Build a history that exceeds 40% of a tiny 512-token window
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 20; i++) {
            history.add(new ChatMessage("user", "Message number " + i + " with some text to fill up tokens"));
            history.add(new ChatMessage("assistant", "Response to message " + i + " with extra content here"));
        }

        var result = ConversationCompressor.compress(history, 512, 128);

        // Should have compressed: fewer messages than original
        assertTrue(result.size() < history.size());
        // Should keep last KEEP_RECENT (3) messages
        assertTrue(result.size() >= 3);
        // Last message should be from original history
        assertEquals(history.getLast().content(), result.getLast().content());
    }

    @Test
    void compressed_summary_contains_earlier_marker() {
        var history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 20; i++) {
            history.add(new ChatMessage("user", "Question " + i + " about something important to discuss"));
            history.add(new ChatMessage("assistant", "Answer " + i + " with detail and explanation included"));
        }

        var result = ConversationCompressor.compress(history, 512, 128);

        // First message should be the summary (system message)
        var first = result.getFirst();
        assertEquals("system", first.role());
        assertTrue(first.content().startsWith("[Earlier conversation:"));
    }

    @Test
    void summarizeMessage_extracts_action() {
        var msg = new ChatMessage("assistant",
            "I'll search now ```json\n{\"action\": \"library_search\", \"query\": \"dragons\"}\n```");
        var summary = ConversationCompressor.summarizeMessage(msg);
        assertNotNull(summary);
        assertTrue(summary.contains("searched library"));
        assertTrue(summary.contains("dragons"));
    }

    @Test
    void summarizeMessage_extracts_speech() {
        var msg = new ChatMessage("user", "Player says: What do you know about fire?");
        var summary = ConversationCompressor.summarizeMessage(msg);
        assertNotNull(summary);
        assertTrue(summary.contains("Player"));
        assertTrue(summary.contains("fire"));
    }

    @Test
    void summarizeMessage_null_for_blank() {
        assertNull(ConversationCompressor.summarizeMessage(new ChatMessage("user", "")));
        assertNull(ConversationCompressor.summarizeMessage(new ChatMessage("user", "   ")));
    }
}

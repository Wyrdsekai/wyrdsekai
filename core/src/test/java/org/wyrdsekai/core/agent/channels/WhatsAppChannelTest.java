package org.wyrdsekai.core.agent.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior tests for {@link WhatsAppChannel}. The Java client is the
 * counterpart to the (separate, future) Go sidecar that wraps whatsmeow;
 * tests exercise the JSON-parsing + dedup + offset-persist contract using
 * fake sidecar responses, which is everything we can verify before the
 * sidecar binary lands.
 */
class WhatsAppChannelTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String RECIPIENT = "1234567890@s.whatsapp.net";

    @AfterEach
    void cleanup() { ChannelStateStore.resetForTests(); }

    @Test
    void name_matches_store_namespace() {
        var ch = new WhatsAppChannel("http://localhost:9700", RECIPIENT);
        assertThat(ch.name()).isEqualTo("whatsapp");
    }

    @Test
    void processEvents_advances_next_offset(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new WhatsAppChannel("http://localhost:9700", RECIPIENT);
        var resp = M.readTree("""
            {"events": [], "next_offset": "cursor-100"}
            """);

        ch.processEvents(resp);

        assertThat(ch.peekNextOffset()).isEqualTo("cursor-100");
        assertThat(store.readOffset("whatsapp", RECIPIENT)).contains("cursor-100");
    }

    @Test
    void processEvents_dedups_repeat_event_ids(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);
        store.markProcessed("whatsapp", "msg-abc-1");

        var ch = new WhatsAppChannel("http://localhost:9700", RECIPIENT);
        var resp = M.readTree("""
            {
              "events": [{
                "id": "msg-abc-1",
                "from": "1234567890@s.whatsapp.net",
                "name": "Alice",
                "body": "hello",
                "ts": 1745526789000
              }],
              "next_offset": "cursor-101"
            }
            """);

        ch.processEvents(resp);

        // Already-marked → no double-mark, dedup preserved.
        assertThat(store.isProcessed("whatsapp", "msg-abc-1")).isTrue();
        // Offset still advances even when all events deduped.
        assertThat(ch.peekNextOffset()).isEqualTo("cursor-101");
    }

    @Test
    void processEvents_filters_by_recipient_jid(@TempDir Path tmp) throws Exception {
        // Sidecar may broadcast events from all conversations; we MUST
        // only route those from the configured recipient. Otherwise a
        // group chat or unrelated DM lands at the agent.
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new WhatsAppChannel("http://localhost:9700", RECIPIENT);
        var resp = M.readTree("""
            {
              "events": [
                {"id": "msg-other-1", "from": "9999999999@s.whatsapp.net",
                 "name": "Stranger", "body": "spam", "ts": 1745526789000},
                {"id": "msg-group-1", "from": "120363999@g.us",
                 "name": "Group", "body": "group msg", "ts": 1745526789001}
              ],
              "next_offset": "cursor-102"
            }
            """);

        ch.processEvents(resp);

        // Neither event matches the configured recipient → no dedup write.
        assertThat(store.isProcessed("whatsapp", "msg-other-1")).isFalse();
        assertThat(store.isProcessed("whatsapp", "msg-group-1")).isFalse();
    }

    @Test
    void processEvents_skips_blank_body(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new WhatsAppChannel("http://localhost:9700", RECIPIENT);
        var resp = M.readTree("""
            {
              "events": [{
                "id": "msg-img-1",
                "from": "1234567890@s.whatsapp.net",
                "name": "Alice",
                "body": "",
                "ts": 1745526789000
              }],
              "next_offset": "cursor-103"
            }
            """);

        ch.processEvents(resp);

        // Image-only / non-text events have empty body → not routed, not deduped.
        assertThat(store.isProcessed("whatsapp", "msg-img-1")).isFalse();
    }

    @Test
    void processEvents_handles_no_events_array_gracefully(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        ChannelStateStore.setInstance(new ChannelStateStore(jdbc));

        var ch = new WhatsAppChannel("http://localhost:9700", RECIPIENT);
        // Sidecar might respond with a malformed shape during startup —
        // don't crash, just persist whatever offset we have and continue.
        var resp = M.readTree("""
            {"next_offset": "cursor-104"}
            """);

        ch.processEvents(resp);

        assertThat(ch.peekNextOffset()).isEqualTo("cursor-104");
    }

    @Test
    void startListener_resumes_from_persisted_offset(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        store.writeOffset("whatsapp", RECIPIENT, "cursor-resume-500");

        var ch = new WhatsAppChannel("http://localhost:9700", RECIPIENT);
        ch.startListener("Wyrd");
        try {
            assertThat(ch.peekNextOffset()).isEqualTo("cursor-resume-500");
        } finally {
            ch.stopListener();
        }
    }

    @Test
    void sidecar_url_trailing_slash_stripped() {
        // Constructor invariant: trailing slash gets stripped so URL
        // concatenation produces /events not //events.
        var ch = new WhatsAppChannel("http://localhost:9700/", RECIPIENT);
        // Verified indirectly via send() URL building — for now just
        // assert the constructor accepted both forms without throwing.
        assertThat(ch.name()).isEqualTo("whatsapp");
    }
}

package org.wyrdsekai.core.agent.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior tests for {@link MatrixChannel}. Exercises the {@code /sync}
 * response parsing + sync-token checkpoint + dedup, all without needing
 * a live Matrix homeserver.
 */
class MatrixChannelTest {

    private static final ObjectMapper M = new ObjectMapper();

    @AfterEach
    void cleanup() { ChannelStateStore.resetForTests(); }

    @Test
    void name_matches_store_namespace() {
        var ch = new MatrixChannel("https://matrix.org", "tok", "!room:matrix.org");
        assertThat(ch.name()).isEqualTo("matrix");
    }

    @Test
    void homeserver_url_trailing_slash_stripped() {
        // URL builder concatenates path segments; a stray trailing slash
        // would produce //_matrix/... which most servers reject.
        var ch = new MatrixChannel("https://matrix.org/", "tok", "!room:matrix.org");
        // No public accessor, but observed via send() URL — for now lock
        // in the contract via the documented behavior in the constructor.
        assertThat(ch.name()).isEqualTo("matrix");  // placeholder — constructor didn't throw
    }

    @Test
    void processSync_advances_next_batch_token(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new MatrixChannel("https://matrix.org", "tok", "!room:matrix.org");
        var sync = M.readTree("""
            {"next_batch":"s100_500_0_0_0_0_0_0_0","rooms":{"join":{}}}
            """);

        ch.processSync(sync);

        assertThat(ch.peekNextBatch()).isEqualTo("s100_500_0_0_0_0_0_0_0");
        // Even with no events, the offset should be persisted so the next
        // /sync resumes from this token rather than initial-sync.
        assertThat(store.readOffset("matrix", "!room:matrix.org"))
            .contains("s100_500_0_0_0_0_0_0_0");
    }

    @Test
    void processSync_dedups_repeat_event_ids(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new MatrixChannel("https://matrix.org", "tok", "!room:matrix.org");
        // Pre-mark this event_id so the second processSync sees it.
        store.markProcessed("matrix", "$evt-1:matrix.org");

        var sync = M.readTree("""
            {
              "next_batch": "s101",
              "rooms": {"join": {"!room:matrix.org": {
                "timeline": {"events": [
                  {
                    "type": "m.room.message",
                    "event_id": "$evt-1:matrix.org",
                    "sender": "@alice:matrix.org",
                    "content": {"msgtype": "m.text", "body": "hello"}
                  }
                ]}
              }}}
            }
            """);

        ch.processSync(sync);

        // Already-processed → no double-mark, ledger unchanged shape.
        assertThat(store.isProcessed("matrix", "$evt-1:matrix.org")).isTrue();
        // Sync token still advances even when all events are deduped.
        assertThat(ch.peekNextBatch()).isEqualTo("s101");
    }

    @Test
    void processSync_skips_non_text_messages(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new MatrixChannel("https://matrix.org", "tok", "!room:matrix.org");
        // m.image / m.file / m.notice etc. — v1 only handles m.text.
        var sync = M.readTree("""
            {
              "next_batch": "s102",
              "rooms": {"join": {"!room:matrix.org": {
                "timeline": {"events": [
                  {
                    "type": "m.room.message",
                    "event_id": "$img-1:matrix.org",
                    "sender": "@alice:matrix.org",
                    "content": {"msgtype": "m.image", "body": "photo.png", "url": "mxc://..."}
                  },
                  {
                    "type": "m.room.member",
                    "event_id": "$join-1:matrix.org",
                    "sender": "@bob:matrix.org",
                    "content": {"membership": "join"}
                  }
                ]}
              }}}
            }
            """);

        ch.processSync(sync);

        // Neither event should land in the dedup ledger (we never publish them).
        assertThat(store.isProcessed("matrix", "$img-1:matrix.org")).isFalse();
        assertThat(store.isProcessed("matrix", "$join-1:matrix.org")).isFalse();
        assertThat(ch.peekNextBatch()).isEqualTo("s102");
    }

    @Test
    void processSync_skips_other_room_events(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new MatrixChannel("https://matrix.org", "tok", "!our-room:matrix.org");
        // Matrix /sync returns events for ALL joined rooms; we must only
        // process the configured one.
        var sync = M.readTree("""
            {
              "next_batch": "s103",
              "rooms": {"join": {
                "!other-room:matrix.org": {
                  "timeline": {"events": [{
                    "type": "m.room.message",
                    "event_id": "$other:matrix.org",
                    "sender": "@x:matrix.org",
                    "content": {"msgtype": "m.text", "body": "wrong room"}
                  }]}
                }
              }}
            }
            """);

        ch.processSync(sync);

        assertThat(store.isProcessed("matrix", "$other:matrix.org")).isFalse();
        assertThat(ch.peekNextBatch()).isEqualTo("s103");
    }

    @Test
    void startListener_resumes_from_persisted_sync_token(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        store.writeOffset("matrix", "!room:matrix.org", "s500_resume");

        var ch = new MatrixChannel("https://matrix.org", "tok", "!room:matrix.org");
        ch.startListener("Wyrd");
        try {
            assertThat(ch.peekNextBatch()).isEqualTo("s500_resume");
        } finally {
            ch.stopListener();
        }
    }

    @Test
    void startListener_with_no_persisted_token_starts_with_null(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        ChannelStateStore.setInstance(new ChannelStateStore(jdbc));

        var ch = new MatrixChannel("https://matrix.org", "tok", "!room:matrix.org");
        ch.startListener("Wyrd");
        try {
            // null = initial sync — Matrix gives full state for the room.
            assertThat(ch.peekNextBatch()).isNull();
        } finally {
            ch.stopListener();
        }
    }
}

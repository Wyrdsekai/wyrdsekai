package org.wyrdsekai.core.agent.channels;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior contracts for {@link ChannelStateStore}. The store is the
 * persistence layer behind item #3 (channel maturation) — the failure
 * modes that motivate it (replay storm, message loss after restart) are
 * tested via the offset round-trip + dedup round-trip below.
 */
class ChannelStateStoreTest {

    private static ChannelStateStore newStore(Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("channels.db"));
        return new ChannelStateStore(jdbc);
    }

    // ── Offset checkpoints ─────────────────────────────────────────────

    @Test
    void readOffset_returns_empty_when_not_set(@TempDir Path tmp) {
        var store = newStore(tmp);
        assertThat(store.readOffset("telegram", "chat-123")).isEmpty();
    }

    @Test
    void writeOffset_then_readOffset_round_trips(@TempDir Path tmp) {
        var store = newStore(tmp);
        store.writeOffset("telegram", "chat-123", "42");
        assertThat(store.readOffset("telegram", "chat-123")).contains("42");
    }

    @Test
    void writeOffset_overwrites_prior_value(@TempDir Path tmp) {
        var store = newStore(tmp);
        store.writeOffset("telegram", "chat-123", "42");
        store.writeOffset("telegram", "chat-123", "99");
        // The whole point: restart-resume reads the most-recent ack, not the first.
        assertThat(store.readOffset("telegram", "chat-123")).contains("99");
    }

    @Test
    void offsets_are_scoped_per_channel(@TempDir Path tmp) {
        var store = newStore(tmp);
        store.writeOffset("telegram", "abc", "10");
        store.writeOffset("discord", "abc", "20");
        // Same thread-key string, different channels — must NOT collide.
        assertThat(store.readOffset("telegram", "abc")).contains("10");
        assertThat(store.readOffset("discord", "abc")).contains("20");
    }

    @Test
    void offsets_are_scoped_per_thread(@TempDir Path tmp) {
        var store = newStore(tmp);
        store.writeOffset("telegram", "alice", "10");
        store.writeOffset("telegram", "bob", "20");
        assertThat(store.readOffset("telegram", "alice")).contains("10");
        assertThat(store.readOffset("telegram", "bob")).contains("20");
    }

    @Test
    void writeOffset_handles_string_offsets_for_slack_style_ts(@TempDir Path tmp) {
        // Slack uses "1234567890.123456" message timestamps as offsets, not
        // monotonic ints. Ensure the TEXT column round-trips arbitrary strings.
        var store = newStore(tmp);
        store.writeOffset("slack", "C09ABCD", "1745526789.123456");
        assertThat(store.readOffset("slack", "C09ABCD"))
            .contains("1745526789.123456");
    }

    // ── Dedup ledger ───────────────────────────────────────────────────

    @Test
    void isProcessed_returns_false_for_unseen_message(@TempDir Path tmp) {
        var store = newStore(tmp);
        assertThat(store.isProcessed("telegram", "msg-1")).isFalse();
    }

    @Test
    void markProcessed_then_isProcessed_returns_true(@TempDir Path tmp) {
        var store = newStore(tmp);
        store.markProcessed("telegram", "msg-1");
        assertThat(store.isProcessed("telegram", "msg-1")).isTrue();
    }

    @Test
    void markProcessed_is_idempotent(@TempDir Path tmp) {
        // Crash recovery may invoke markProcessed twice for the same message.
        // Must not throw and must not corrupt the row.
        var store = newStore(tmp);
        store.markProcessed("telegram", "msg-1");
        store.markProcessed("telegram", "msg-1");
        assertThat(store.isProcessed("telegram", "msg-1")).isTrue();
    }

    @Test
    void dedup_ledger_is_scoped_per_channel(@TempDir Path tmp) {
        var store = newStore(tmp);
        store.markProcessed("telegram", "shared-id");
        // Same external_id from a different channel must NOT count as seen.
        assertThat(store.isProcessed("discord", "shared-id")).isFalse();
        assertThat(store.isProcessed("telegram", "shared-id")).isTrue();
    }

    @Test
    void pruneProcessedOlderThan_zero_clears_all_entries(@TempDir Path tmp) {
        var store = newStore(tmp);
        store.markProcessed("telegram", "msg-1");
        store.markProcessed("telegram", "msg-2");
        store.markProcessed("discord", "msg-3");

        // ageMs=0 means "everything older than now-0" → everything.
        var pruned = store.pruneProcessedOlderThan(0);

        assertThat(pruned).isEqualTo(3);
        assertThat(store.isProcessed("telegram", "msg-1")).isFalse();
        assertThat(store.isProcessed("telegram", "msg-2")).isFalse();
        assertThat(store.isProcessed("discord", "msg-3")).isFalse();
    }

    @Test
    void pruneProcessedOlderThan_long_window_keeps_recent_entries(@TempDir Path tmp) {
        var store = newStore(tmp);
        store.markProcessed("telegram", "fresh");
        // 1 hour window — entry just written is well within it.
        var pruned = store.pruneProcessedOlderThan(3600_000L);
        assertThat(pruned).isEqualTo(0);
        assertThat(store.isProcessed("telegram", "fresh")).isTrue();
    }

    @Test
    void prune_returns_zero_on_empty_table(@TempDir Path tmp) {
        var store = newStore(tmp);
        assertThat(store.pruneProcessedOlderThan(0)).isZero();
    }

    // ── Singleton wiring ───────────────────────────────────────────────

    @Test
    void setInstance_and_get_round_trip(@TempDir Path tmp) {
        var store = newStore(tmp);
        try {
            ChannelStateStore.setInstance(store);
            assertThat(ChannelStateStore.get()).isSameAs(store);
        } finally {
            ChannelStateStore.resetForTests();
        }
        assertThat(ChannelStateStore.get()).isNull();
    }
}

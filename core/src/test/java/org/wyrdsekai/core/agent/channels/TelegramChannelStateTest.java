package org.wyrdsekai.core.agent.channels;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the wiring for {@link TelegramChannel}.
 *
 * <p>These are NOT live HTTP tests — Telegram's API is mocked away. They
 * cover the local persistence-layer plumbing: offset resume on listener
 * start, dedup-ledger key-space alignment, and the contract that the
 * channel's name matches the store's keys.</p>
 */
class TelegramChannelStateTest {

    @AfterEach
    void cleanup() {
        ChannelStateStore.resetForTests();
    }

    @Test
    void startListener_with_no_persisted_offset_starts_at_zero(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        ChannelStateStore.setInstance(new ChannelStateStore(jdbc));

        var ch = new TelegramChannel("fake-token", "chat-1");
        ch.startListener("Wyrd");
        try {
            assertThat(ch.peekLastUpdateId()).isZero();
        } finally {
            ch.stopListener();
        }
    }

    @Test
    void startListener_resumes_from_persisted_offset(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        // Simulate a prior run that ack'd through update_id 12345.
        store.writeOffset("telegram", "chat-1", "12345");

        var ch = new TelegramChannel("fake-token", "chat-1");
        ch.startListener("Wyrd");
        try {
            // Without the maturation wiring, this would be 0 → replay storm
            // or message loss depending on Telegram's server-side trim.
            assertThat(ch.peekLastUpdateId()).isEqualTo(12345L);
        } finally {
            ch.stopListener();
        }
    }

    @Test
    void startListener_handles_malformed_persisted_offset_gracefully(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        // Write a non-numeric offset (could happen from a future channel
        // sharing the table, or a corrupted row).
        store.writeOffset("telegram", "chat-1", "not-a-number");

        var ch = new TelegramChannel("fake-token", "chat-1");
        ch.startListener("Wyrd");
        try {
            // Fail-open to 0 rather than crash. WARN logged.
            assertThat(ch.peekLastUpdateId()).isZero();
        } finally {
            ch.stopListener();
        }
    }

    @Test
    void startListener_works_when_store_singleton_unset(@TempDir Path tmp) {
        // Defensive: if the persistence bootstrap hasn't run (test config,
        // misconfigured deploy), the channel should still start without NPE.
        ChannelStateStore.resetForTests();

        var ch = new TelegramChannel("fake-token", "chat-1");
        ch.startListener("Wyrd");
        try {
            assertThat(ch.isListening()).isTrue();
            assertThat(ch.peekLastUpdateId()).isZero();
        } finally {
            ch.stopListener();
        }
    }

    @Test
    void channel_name_matches_store_key_namespace() {
        // Contract: channel.name() must match the namespace used in
        // ChannelStateStore calls. The store uses the lowercase-no-spaces
        // form; if a channel ever drifts (e.g. "Telegram" vs "telegram")
        // the offset/dedup state would silently desynchronize.
        var ch = new TelegramChannel("fake-token", "chat-1");
        assertThat(ch.name()).isEqualTo("telegram");
    }
}

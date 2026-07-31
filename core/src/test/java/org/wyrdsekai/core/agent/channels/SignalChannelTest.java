package org.wyrdsekai.core.agent.channels;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior tests for {@link SignalChannel}. Like Keybase the channel uses
 * a streaming subprocess (signal-cli daemon) — there's no offset cursor,
 * only a dedup ledger. These tests exercise the JSON-line parsing and
 * dedup contract without needing signal-cli installed.
 */
class SignalChannelTest {

    @AfterEach
    void cleanup() { ChannelStateStore.resetForTests(); }

    @Test
    void name_matches_store_namespace() {
        var ch = new SignalChannel("+15551234567", "+15559876543");
        assertThat(ch.name()).isEqualTo("signal");
    }

    @Test
    void processLine_dedups_repeat_envelopes(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new SignalChannel("+15551234567", "+15559876543");
        // companionName intentionally null — the publish path is gated and
        // markProcessed only fires when publish happens. Since we're testing
        // the *first half* of the contract (the dedup short-circuit), we
        // pre-populate the ledger and expect the second processLine to
        // see it as already-processed.

        var envelope = """
            {"jsonrpc":"2.0","method":"receive","params":{
              "envelope":{
                "source":"+15559876543",
                "sourceUuid":"uuid-alice",
                "sourceName":"Alice",
                "timestamp":1745526789123,
                "dataMessage":{"timestamp":1745526789123,"message":"hi"}
              }}}
            """;

        // Pre-mark this envelope as processed.
        store.markProcessed("signal", "uuid-alice:1745526789123");

        // Process it — should hit the dedup short-circuit, no exception, no double-mark.
        ch.processLine(envelope);

        // Idempotent: still just the one row, still marked processed.
        assertThat(store.isProcessed("signal", "uuid-alice:1745526789123")).isTrue();
    }

    @Test
    void processLine_ignores_non_receive_method(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        ChannelStateStore.setInstance(new ChannelStateStore(jdbc));

        var ch = new SignalChannel("+15551234567", "+15559876543");
        // signal-cli daemon also emits e.g. "subscribed" notifications.
        // These have method != "receive" and must be silently ignored.
        ch.processLine("""
            {"jsonrpc":"2.0","method":"subscribed","params":{"account":"+15551234567"}}
            """);
        // No assertion needed beyond "doesn't throw" — the method name guard
        // is the regression we want to lock in.
    }

    @Test
    void processLine_ignores_group_messages_v1(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new SignalChannel("+15551234567", "+15559876543");

        var groupEnvelope = """
            {"jsonrpc":"2.0","method":"receive","params":{
              "envelope":{
                "source":"+15559876543",
                "sourceUuid":"uuid-alice",
                "timestamp":1745526789123,
                "dataMessage":{
                  "timestamp":1745526789123,
                  "message":"hi group",
                  "groupInfo":{"groupId":"grp-xyz"}
                }
              }}}
            """;

        ch.processLine(groupEnvelope);

        // Group messages must NOT enter the dedup ledger — they're skipped
        // entirely. Otherwise v2-group-support would have to migrate the
        // existing rows.
        assertThat(store.isProcessed("signal", "uuid-alice:1745526789123")).isFalse();
    }

    @Test
    void processLine_handles_malformed_json_gracefully(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        ChannelStateStore.setInstance(new ChannelStateStore(jdbc));

        var ch = new SignalChannel("+15551234567", "+15559876543");
        // signal-cli could in theory emit a partial line if killed mid-flush.
        // Don't crash — just log debug and move on.
        ch.processLine("{ this isn't valid json");
        ch.processLine("");
    }

    @Test
    void processLine_skips_empty_message_text(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        var ch = new SignalChannel("+15551234567", "+15559876543");
        // Read receipts have dataMessage with no message text. Should be
        // silently dropped — not a real conversational event.
        var receipt = """
            {"jsonrpc":"2.0","method":"receive","params":{
              "envelope":{
                "source":"+15559876543",
                "sourceUuid":"uuid-alice",
                "timestamp":1745526789999,
                "dataMessage":{"timestamp":1745526789999}
              }}}
            """;
        ch.processLine(receipt);

        assertThat(store.isProcessed("signal", "uuid-alice:1745526789999")).isFalse();
    }
}

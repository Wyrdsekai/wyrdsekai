package org.wyrdsekai.core.agent.channels;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies wiring on {@link SlackChannel}. Slack's
 * cursor is a string Slack-ts (e.g. {@code "1745526789.123456"}) rather
 * than a long, so we exercise the TEXT-column path of the store.
 */
class SlackChannelStateTest {

    @AfterEach
    void cleanup() { ChannelStateStore.resetForTests(); }

    @Test
    void startListener_with_no_persisted_offset_keeps_constructor_default(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        ChannelStateStore.setInstance(new ChannelStateStore(jdbc));

        var ch = new SlackChannel("xoxb-fake", "C09ABCD");
        // Capture the construction-time default (current epoch second as string).
        var beforeStart = ch.peekOldestTs();
        assertThat(beforeStart).isNotEmpty();

        ch.startListener("Wyrd");
        try {
            // No persisted offset → the constructor's "now" default stays.
            // (Otherwise restart would replay arbitrarily-old history.)
            assertThat(ch.peekOldestTs()).isEqualTo(beforeStart);
        } finally {
            ch.stopListener();
        }
    }

    @Test
    void startListener_resumes_from_persisted_slack_ts(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        // Slack timestamps look like "<seconds>.<microseconds>". TEXT column
        // round-trip is the whole point — preserves the exact lexicographic
        // form Slack expects in conversations.history?oldest=...
        store.writeOffset("slack", "C09ABCD", "1745526789.123456");

        var ch = new SlackChannel("xoxb-fake", "C09ABCD");
        ch.startListener("Wyrd");
        try {
            assertThat(ch.peekOldestTs()).isEqualTo("1745526789.123456");
        } finally {
            ch.stopListener();
        }
    }

    @Test
    void slack_offset_does_not_collide_with_telegram_offset(@TempDir Path tmp) {
        // Same thread_key string ("C09ABCD") could theoretically collide if
        // the store wasn't channel-scoped. Lock that in: writes to "telegram"
        // channel are invisible to the slack channel's resume.
        var jdbc = SchemaInitializer.initialize(tmp.resolve("c.db"));
        var store = new ChannelStateStore(jdbc);
        ChannelStateStore.setInstance(store);

        store.writeOffset("telegram", "C09ABCD", "999");  // wrong channel, same key

        var ch = new SlackChannel("xoxb-fake", "C09ABCD");
        var beforeStart = ch.peekOldestTs();
        ch.startListener("Wyrd");
        try {
            // Slack channel must NOT pick up the Telegram-channel offset.
            assertThat(ch.peekOldestTs()).isEqualTo(beforeStart);
        } finally {
            ch.stopListener();
        }
    }
}

package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * in-world mailbox service tests.
 */
class MailboxServiceTest {

    private MailboxService svc;

    @BeforeEach
    void reset() {
        MailboxService.resetForTests();
        svc = MailboxService.getOrCreate();
    }

    @AfterEach
    void cleanup() {
        MailboxService.resetForTests();
    }

    @Test
    void send_then_inbox() {
        var send = svc.send("did:wyrd:a", "did:wyrd:b", "hello", "first message", null);
        assertThat(send.get("ok")).isEqualTo(true);
        assertThat(send.get("id")).isNotNull();

        var inbox = svc.inbox("did:wyrd:b", null);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.getFirst().get("from")).isEqualTo("did:wyrd:a");
        assertThat(inbox.getFirst().get("body")).isEqualTo("first message");
        assertThat(inbox.getFirst().get("read")).isEqualTo(false);
    }

    @Test
    void send_rejects_missing_recipient() {
        var send = svc.send("did:wyrd:a", null, "hi", "body", null);
        assertThat(send.get("ok")).isEqualTo(false);
        assertThat(send.get("error")).isEqualTo("missing_recipient");
    }

    @Test
    void send_rejects_missing_sender() {
        var send = svc.send(null, "did:wyrd:b", "hi", "body", null);
        assertThat(send.get("ok")).isEqualTo(false);
        assertThat(send.get("error")).isEqualTo("missing_sender");
    }

    @Test
    void send_rejects_oversize_body() {
        var huge = "x".repeat(MailboxService.MAX_BODY_BYTES + 100);
        var send = svc.send("did:wyrd:a", "did:wyrd:b", "h", huge, null);
        assertThat(send.get("ok")).isEqualTo(false);
        assertThat(send.get("error")).isEqualTo("body_too_large");
    }

    @Test
    void mark_read_changes_state() {
        var s = svc.send("did:wyrd:a", "did:wyrd:b", "h", "x", null);
        var id = (String) s.get("id");
        var mr = svc.markRead("did:wyrd:b", id);
        assertThat(mr.get("ok")).isEqualTo(true);
        var inbox = svc.inbox("did:wyrd:b", null);
        assertThat(inbox.getFirst().get("read")).isEqualTo(true);
    }

    @Test
    void archive_hides_from_inbox_by_default() {
        var s = svc.send("did:wyrd:a", "did:wyrd:b", "h", "x", null);
        var id = (String) s.get("id");
        svc.archive("did:wyrd:b", id);
        var inbox = svc.inbox("did:wyrd:b", null);
        assertThat(inbox).isEmpty();
        // But still visible with archived filter
        var withArch = svc.inbox("did:wyrd:b", Map.of("archived", true));
        assertThat(withArch).hasSize(1);
    }

    @Test
    void inbox_unread_filter() {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            ids.add((String) svc.send("did:wyrd:a", "did:wyrd:b", "h", "msg" + i, null).get("id"));
        }
        svc.markRead("did:wyrd:b", ids.get(0));
        var unread = svc.inbox("did:wyrd:b", Map.of("unread", true));
        assertThat(unread).hasSize(2);
    }

    @Test
    void inbox_from_filter() {
        svc.send("did:wyrd:a", "did:wyrd:b", "h", "x", null);
        svc.send("did:wyrd:c", "did:wyrd:b", "h", "y", null);
        var fromA = svc.inbox("did:wyrd:b", Map.of("from", "did:wyrd:a"));
        assertThat(fromA).hasSize(1);
        assertThat(fromA.getFirst().get("from")).isEqualTo("did:wyrd:a");
    }

    @Test
    void read_returns_message_by_id() {
        var s = svc.send("did:wyrd:a", "did:wyrd:b", "subj", "body", null);
        var msg = svc.read("did:wyrd:b", (String) s.get("id"));
        assertThat(msg.get("subject")).isEqualTo("subj");
        assertThat(msg.get("body")).isEqualTo("body");
    }

    @Test
    void read_missing_id_returns_error() {
        var msg = svc.read("did:wyrd:b", "nope");
        assertThat(msg.get("error")).isEqualTo("not_found");
    }

    @Test
    void inbox_sorted_newest_first() throws InterruptedException {
        svc.send("did:wyrd:a", "did:wyrd:b", "h", "first", null);
        Thread.sleep(2);
        svc.send("did:wyrd:a", "did:wyrd:b", "h", "second", null);
        var inbox = svc.inbox("did:wyrd:b", null);
        assertThat(inbox.getFirst().get("body")).isEqualTo("second");
    }

    @Test
    void send_propagates_priority_and_expires() {
        var s = svc.send("did:wyrd:a", "did:wyrd:b", "h", "x",
            Map.of("priority", "high", "expires", 999999L));
        assertThat(s.get("ok")).isEqualTo(true);
        var msg = svc.read("did:wyrd:b", (String) s.get("id"));
        assertThat(msg.get("priority")).isEqualTo("high");
        assertThat(msg.get("expiresAt")).isEqualTo(999999L);
    }
}

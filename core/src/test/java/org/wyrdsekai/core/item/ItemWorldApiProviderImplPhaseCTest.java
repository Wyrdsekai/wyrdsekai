package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * provider-level wiring tests for
 * fs/mailbox/drive.mark. Web + MCP exercise their gating in scripting
 * tests; this module pins the core-side service round-trips.
 */
class ItemWorldApiProviderImplPhaseCTest {

    private ItemWorldApiProviderImpl provider;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        MailboxService.resetForTests();
        provider = new ItemWorldApiProviderImpl(
            null, null, null, null,
            "did:wyrd:test-agent", "Tester",
            t -> {}, t -> {}, (a, b) -> {},
            null, null);
        // Point fs at the JUnit temp dir.
        provider.setSandboxedFs(new SandboxedFs(tmp, "did:wyrd:test-agent"));
    }

    @AfterEach
    void tearDown() {
        MailboxService.resetForTests();
    }

    // ─── Filesystem ────────────────────────────────────────────

    @Test
    void fs_write_then_read() {
        var w = provider.fsWrite("hello.txt", "world");
        assertThat(w.get("ok")).isEqualTo(true);
        assertThat(w.get("size")).isEqualTo(5L);
        assertThat(provider.fsRead("hello.txt")).isEqualTo("world");
    }

    @Test
    void fs_read_missing_returns_error() {
        var content = provider.fsRead("missing.txt");
        assertThat(content).startsWith("[error]");
    }

    @Test
    void fs_list_returns_entries() {
        provider.fsWrite("a.txt", "a");
        provider.fsWrite("b.txt", "b");
        var listing = provider.fsList(null);
        assertThat(listing).hasSize(2);
    }

    @Test
    void fs_delete_then_exists_false() {
        provider.fsWrite("toremove.txt", "x");
        assertThat(provider.fsExists("toremove.txt")).isTrue();
        var del = provider.fsDelete("toremove.txt");
        assertThat(del.get("ok")).isEqualTo(true);
        assertThat(provider.fsExists("toremove.txt")).isFalse();
    }

    @Test
    void fs_path_traversal_rejected() {
        var w = provider.fsWrite("../escape.txt", "x");
        assertThat(w.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(w.get("error"))).contains("..");
    }

    @Test
    void fs_absolute_path_rejected() {
        var w = provider.fsWrite("/etc/passwd", "x");
        assertThat(w.get("ok")).isEqualTo(false);
    }

    @Test
    void fs_stat_returns_file_metadata() {
        provider.fsWrite("file.txt", "content");
        var s = provider.fsStat("file.txt");
        assertThat(s.get("name")).isEqualTo("file.txt");
        assertThat(s.get("size")).isEqualTo(7L);
        assertThat(s.get("isDir")).isEqualTo(false);
    }

    @Test
    void fs_mkdir_creates_directory() {
        var m = provider.fsMkdir("subdir");
        assertThat(m.get("ok")).isEqualTo(true);
        assertThat(provider.fsExists("subdir")).isTrue();
    }

    // ─── Mailbox ───────────────────────────────────────────────

    @Test
    void mailbox_send_and_read_round_trip() {
        // Provider sends from agentId="did:wyrd:test-agent" to a recipient,
        // and reads its own inbox by agentId.
        var send = provider.mailboxSend("did:wyrd:other", "subj", "hello", null);
        assertThat(send.get("ok")).isEqualTo(true);

        // The provider's inbox is keyed by its own agentId — so we send TO
        // ourselves to test the provider's inbox round trip.
        var selfSend = provider.mailboxSend("did:wyrd:test-agent", "self", "echo", null);
        assertThat(selfSend.get("ok")).isEqualTo(true);

        var inbox = provider.mailboxInbox(null);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.getFirst().get("subject")).isEqualTo("self");
    }

    @Test
    void mailbox_archive_idempotent() {
        var s = provider.mailboxSend("did:wyrd:test-agent", "h", "body", null);
        var id = (String) s.get("id");
        var arch = provider.mailboxArchive(id);
        assertThat(arch.get("ok")).isEqualTo(true);
        var arch2 = provider.mailboxArchive(id);
        assertThat(arch2.get("ok")).isEqualTo(true);
        assertThat(arch2.get("already")).isEqualTo(true);
    }

    @Test
    void mailbox_mark_read_changes_flag() {
        var s = provider.mailboxSend("did:wyrd:test-agent", "h", "body", null);
        var id = (String) s.get("id");
        provider.mailboxMarkRead(id);
        var msg = provider.mailboxRead(id);
        assertThat(msg.get("read")).isEqualTo(true);
    }

    // ─── Drive.mark ────────────────────────────────────────────

    @Test
    void drive_mark_without_callback_returns_not_wired() {
        var res = provider.driveMark("seeking", 0.1, "test");
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("error")).isEqualTo("drive_mark_not_wired");
    }

    @Test
    void drive_mark_with_callback_invokes_it() {
        var captured = new AtomicReference<String>();
        provider.setDriveMarkCallback((name, delta) ->
            captured.set(name + "=" + delta));
        var res = provider.driveMark("seeking", 0.3, "found pattern");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("name")).isEqualTo("seeking");
        assertThat(captured.get()).isEqualTo("seeking=0.3");
    }

    @Test
    void drive_mark_clamps_delta_to_unit_range() {
        var captured = new AtomicReference<Double>();
        provider.setDriveMarkCallback((name, delta) -> captured.set(delta));
        provider.driveMark("seeking", 2.5, null);
        assertThat(captured.get()).isEqualTo(1.0);
        provider.driveMark("seeking", -2.0, null);
        assertThat(captured.get()).isEqualTo(-1.0);
    }

    @Test
    void drive_mark_rejects_blank_name() {
        var res = provider.driveMark("", 0.1, null);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("error")).isEqualTo("missing_name");
    }
}

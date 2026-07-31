package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * drive.mark — Phase C
 * scripting-side gating tests for the new mailbox/fs/drive surfaces.
 */
class MailboxFsDriveApiTest {

    // ─── Mailbox ───────────────────────────────────────────────

    @Test
    void mailbox_send_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.mailbox.send("did:wyrd:b", "hi", "body"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void mailbox_send_succeeds_with_capability() {
        var p = new StubProvider();
        var caps = ItemCapabilitySet.of(List.of("agent.mailbox.send"));
        var api = new ItemWorldApi(p, caps);
        var res = api.mailbox.send("did:wyrd:b", "hi", "body");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(p.lastSendTo).isEqualTo("did:wyrd:b");
    }

    @Test
    void mailbox_inbox_is_tier1() {
        var p = new StubProvider();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of()));
        var res = api.mailbox.inbox();
        assertThat(res).isNotNull();
    }

    @Test
    void mailbox_archive_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.mailbox.archive("msg-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void mailbox_mark_read_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.mailbox.mark_read("msg-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    // ─── Filesystem ────────────────────────────────────────────

    @Test
    void fs_read_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.fs.read("notes.txt"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void fs_write_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.fs.write("notes.txt", "hi"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void fs_delete_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.fs.delete("notes.txt"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void fs_list_exists_stat_are_tier1() {
        var p = new StubProvider();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of()));
        // No throw — implicit Tier 1.
        api.fs.list();
        api.fs.exists("anything");
        api.fs.stat("anything");
    }

    @Test
    void fs_write_succeeds_with_capability() {
        var p = new StubProvider();
        var caps = ItemCapabilitySet.of(List.of("fs.write"));
        var api = new ItemWorldApi(p, caps);
        var res = api.fs.write("a.txt", "hello");
        assertThat(res.get("ok")).isEqualTo(true);
    }

    // ─── Drive.mark ────────────────────────────────────────────

    @Test
    void drive_mark_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.drive.mark("seeking", 0.1))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void drive_mark_passes_args_to_provider() {
        var captured = new AtomicReference<String>();
        var p = new StubProvider();
        p.driveMarkImpl = (name, delta, reason) -> {
            captured.set(name + ":" + delta + ":" + reason);
            return Map.of("ok", true, "name", name, "delta", delta);
        };
        var caps = ItemCapabilitySet.of(List.of("drive.mark"));
        var api = new ItemWorldApi(p, caps);
        var res = api.drive.mark("seeking", 0.2, "found pattern");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(captured.get()).isEqualTo("seeking:0.2:found pattern");
    }

    private static final class StubProvider implements ItemWorldApiProvider {
        String lastSendTo;
        TriFn<String, Double, String, Map<String, Object>> driveMarkImpl;

        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }

        @Override public Map<String, Object> mailboxSend(String to, String subj, String body, Map<String, Object> opts) {
            lastSendTo = to;
            return Map.of("ok", true, "id", "msg-1");
        }
        @Override public Map<String, Object> fsWrite(String relPath, String content) {
            return Map.of("ok", true, "size", (long) content.length());
        }
        @Override public Map<String, Object> driveMark(String name, double delta, String reason) {
            return driveMarkImpl != null ? driveMarkImpl.apply(name, delta, reason)
                : ItemWorldApiProvider.super.driveMark(name, delta, reason);
        }
    }

    @FunctionalInterface
    private interface TriFn<A, B, C, R> { R apply(A a, B b, C c); }
}

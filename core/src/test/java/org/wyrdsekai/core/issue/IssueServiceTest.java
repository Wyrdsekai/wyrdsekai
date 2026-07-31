package org.wyrdsekai.core.issue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.ConversationTurnStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IssueService unit coverage: filing both kinds
 * prefix lookup, close-with-persistence, markdown export, and the two
 * capture paths that need real fixtures (conversation turns via a real
 * SQLite store; WARN/ERROR log tail from a real file).
 */
class IssueServiceTest {

    @TempDir
    Path tmp;

    @BeforeEach
    @AfterEach
    void resetSingleton() {
        IssueService.reset();
    }

    private IssueService freshService(String jdbcUrl, Path logFile) {
        IssueService.reset();
        IssueService.init(tmp.resolve("data"), jdbcUrl, logFile);
        return IssueService.get();
    }

    @Test
    void filesAndListsNewestFirst() {
        var svc = freshService(null, null);
        svc.file(Issue.KIND_ISSUE, "first", "operator", "ssh", null, null);
        var second = svc.file(Issue.KIND_FEEDBACK, "second", "operator", "telnet", null, null);

        var list = svc.list(true);
        assertEquals(2, list.size());
        assertEquals(second.id(), list.get(0).id(), "newest first");
        assertEquals(Issue.KIND_FEEDBACK, list.get(0).kind());
        assertNotNull(list.get(0).build(), "build version always captured");
    }

    @Test
    void feedbackSkipsContextCapture() throws Exception {
        // Even with a real log file wired, feedback must not capture it (§2).
        var logFile = tmp.resolve("wyrdsekai.log");
        Files.writeString(logFile, "12:00 ERROR boom\n");
        var svc = freshService(null, logFile);

        var fb = svc.file(Issue.KIND_FEEDBACK, "phrasing felt off", "operator", "ssh", null, null);
        assertNull(fb.logTail());
        assertNull(fb.recentTurns());
        assertNull(fb.driveSnapshot());
    }

    @Test
    void prefixFindAndAmbiguityAndClose() {
        var svc = freshService(null, null);
        var it = svc.file(Issue.KIND_ISSUE, "confabulated my birthday", "operator", "ssh", null, null);

        assertTrue(svc.find(it.id().substring(0, 4)).isPresent());
        assertTrue(svc.find("zzzz").isEmpty());

        var closed = svc.close(it.id().substring(0, 4)).orElseThrow();
        assertEquals(Issue.STATUS_CLOSED, closed.status());
        assertEquals(0, svc.list(true).size());
        assertEquals(1, svc.list(false).size());
    }

    @Test
    void persistsAcrossReload() {
        var svc = freshService(null, null);
        var it = svc.file(Issue.KIND_ISSUE, "survives restart", "operator", "ws", null, null);
        svc.close(it.id());

        // Re-init from the same JSONL — closed status must survive.
        IssueService.reset();
        IssueService.init(tmp.resolve("data"), null, null);
        var reloaded = IssueService.get().find(it.id()).orElseThrow();
        assertEquals(Issue.STATUS_CLOSED, reloaded.status());
        assertEquals("survives restart", reloaded.text());
    }

    @Test
    void capturesRecentTurnsFromRealSqlite() throws Exception {
        var jdbcUrl = "jdbc:sqlite:" + tmp.resolve("turns.db");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE conversation_turns ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, companion_did TEXT, "
                + "bondholder_did TEXT, turn_role TEXT, content TEXT, "
                + "ts_ms INTEGER, room_id TEXT)");
        }
        var store = new ConversationTurnStore(jdbcUrl);
        store.recordTurn("did:wyrd:ember", "did:wyrd:operator",
            ConversationTurnStore.ROLE_HEARD, "remember my birthday is in June", "study");
        store.recordTurn("did:wyrd:ember", "did:wyrd:operator",
            ConversationTurnStore.ROLE_SPOKEN, "I'll remember that.", "study");

        var svc = freshService(jdbcUrl, null);
        var it = svc.file(Issue.KIND_ISSUE, "it forgot my birthday", "operator", "ssh",
            "did:wyrd:ember", "did:wyrd:operator");

        assertNotNull(it.recentTurns(), "turns must be captured when jdbc + bondholder known");
        assertEquals(2, it.recentTurns().size());
        // Both roles captured, newest first.
        assertEquals(ConversationTurnStore.ROLE_SPOKEN, it.recentTurns().get(0).role());
    }

    @Test
    void capturesWarnErrorLogTailOnly() throws Exception {
        var logFile = tmp.resolve("wyrdsekai.log");
        Files.writeString(logFile, String.join("\n",
            "12:00:00.000 [main] INFO  ok line",
            "12:00:01.000 [main] WARN  something odd",
            "12:00:02.000 [main] DEBUG noise",
            "12:00:03.000 [main] ERROR something broke") + "\n");
        var svc = freshService(null, logFile);

        var it = svc.file(Issue.KIND_ISSUE, "saw an error", "operator", "telnet", null, null);
        assertNotNull(it.logTail());
        assertEquals(2, it.logTail().size(), "only WARN/ERROR lines");
        assertTrue(it.logTail().get(1).contains("something broke"));
    }

    @Test
    void exportMarkdownIsSelfContained() throws Exception {
        var logFile = tmp.resolve("wyrdsekai.log");
        Files.writeString(logFile, "12:00 ERROR kaboom\n");
        var svc = freshService(null, logFile);
        var it = svc.file(Issue.KIND_ISSUE, "the room narration looped", "operator", "ssh", null, null);

        var md = svc.exportMarkdown(it.id()).orElseThrow();
        assertTrue(md.contains("the room narration looped"));
        assertTrue(md.contains("**surface:** ssh"));
        assertTrue(md.contains("kaboom"), "log tail rendered into the bundle");
    }
}

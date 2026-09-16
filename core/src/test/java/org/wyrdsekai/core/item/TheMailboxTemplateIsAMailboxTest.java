package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.mail.MailDirectory;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code mailbox} template, run the way a crafted item runs: instantiated from the
 * library, executed through the sandbox with {@code inherit("std/mailbox")}, against the
 * real {@link MailboxService}. Until 2026-09-15 it inherited {@code std/container}, and no
 * test ran the script that replaced it.
 */
class TheMailboxTemplateIsAMailboxTest {

    private static final String ME = "did:key:z6MkAdaOwner";
    private static final String MIA = "companion-mia";

    private StandardItemLibrary library;
    private ItemScriptExecutor executor;
    private MailboxService mail;

    @BeforeEach
    void setUp() {
        var scripts = Path.of("scripts");
        if (!scripts.resolve("std/mailbox.js").toFile().exists()) scripts = Path.of("../scripts");
        library = new StandardItemLibrary(scripts);
        executor = new ItemScriptExecutor();
        executor.setScriptResolver(library::resolveBaseScript);
        MailboxService.resetForTests();
        mail = MailboxService.getOrCreate().install(null, MailDirectory.of(List.of(
            new MailDirectory.Recipient(ME, "Ada Lovelace", "person"),
            new MailDirectory.Recipient(MIA, "Mia", "companion"))), "neo");
    }

    @AfterEach
    void tearDown() {
        executor.close();
        MailboxService.resetForTests();
    }

    /** The owner's provider: mail verbs go to the service under the owner's identity. */
    private class OwnerProvider implements ItemWorldApiProvider {
        @Override public List<Map<String, Object>> mailboxInbox(Map<String, Object> f) { return mail.inbox(ME, f); }
        @Override public Map<String, Object> mailboxRead(String id) { return mail.read(ME, id); }
        @Override public Map<String, Object> mailboxMarkRead(String id) { return mail.markRead(ME, id); }
        @Override public Map<String, Object> mailboxArchive(String id) { return mail.archive(ME, id); }
        @Override public Map<String, Object> mailboxSend(String to, String subject, String body, Map<String, Object> o) {
            return mail.send(ME, to, subject, body, o);
        }
        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String text, String instruction) { return ""; }
        @Override public String llmAnalyze(String text, String prompt) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String content) {}
        @Override public void agentTell(String target, String message) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    }

    private Map<String, Object> use(ToolItem item, String args) {
        return executor.execute(item.id(), item.script(), Map.of("args", args), new OwnerProvider());
    }

    @Test
    @DisplayName("empty, then a letter sent by name, then read and marked read, then put away")
    void theWholeRound() {
        var box = library.instantiate("mailbox", Map.of(), ME);
        assertEquals("std/mailbox", box.templateBase());

        var empty = use(box, "");
        assertNull(empty.get("error"), String.valueOf(empty));
        assertEquals(0, ((Number) empty.get("count")).intValue());

        var sent = use(box, "send mia the garden | The seeds came up.");
        assertNull(sent.get("error"), String.valueOf(sent));
        assertEquals("Sent to Mia@neo.", sent.get("text"));
        assertEquals(1, mail.inbox(MIA, Map.of()).size());

        // Mia writes back; the owner reads it from the box.
        mail.send(MIA, "ada lovelace", "re: the garden", "So they did.", Map.of());
        var listed = use(box, "");
        assertEquals(1, ((Number) listed.get("unread")).intValue(), String.valueOf(listed));
        var read = use(box, "read 1");
        assertNull(read.get("error"), String.valueOf(read));
        assertTrue(String.valueOf(read.get("text")).contains("So they did."), String.valueOf(read));
        assertEquals(0, mail.unreadFor(ME), "reading it marked it read");

        var away = use(box, "archive 1");
        assertEquals("Put away.", away.get("text"));
        assertTrue(mail.inbox(ME, Map.of()).isEmpty());
    }

    @Test
    @DisplayName("a name with a space goes in quotes; a stranger is refused with the names that exist")
    void quotedNamesAndStrangers() {
        var box = library.instantiate("mailbox", Map.of(), MIA);
        var sent = executor.execute(box.id(), box.script(), Map.of("args", "send \"ada lovelace\" tea | Four?"),
            new OwnerProvider() {
                @Override public Map<String, Object> mailboxSend(String to, String s, String b, Map<String, Object> o) {
                    return mail.send(MIA, to, s, b, o);
                }
            });
        assertNull(sent.get("error"), String.valueOf(sent));
        assertEquals(1, mail.inbox(ME, Map.of()).size());
        assertEquals("tea", mail.inbox(ME, Map.of()).get(0).get("subject"));

        var refused = use(box, "send nobody hello | hi");
        assertTrue(String.valueOf(refused.get("error")).contains("Nobody here is called 'nobody'"), String.valueOf(refused));
        assertTrue(String.valueOf(refused.get("error")).contains("Ada Lovelace"), String.valueOf(refused));
    }
}

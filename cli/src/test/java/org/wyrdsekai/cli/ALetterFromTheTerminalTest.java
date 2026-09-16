package org.wyrdsekai.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terminal CLI has no textarea. When the server asks it to compose, it collects lines
 * the way mail always has — subject, body, a single dot — and sends the letter whole.
 * Until this was built the CLI ignored the request and the person was left with a hint
 * to write the letter on one line (found in review, 2026-09-15).
 */
class ALetterFromTheTerminalTest {

    private static final class FakeSession implements WyrdSession {
        final List<C2SMessage> sent = new ArrayList<>();
        int ids;
        @Override public void connect() {}
        @Override public boolean awaitConnected(long timeoutMs) { return true; }
        @Override public void disconnect() {}
        @Override public void prepareClose() {}
        @Override public boolean awaitClosed(long timeoutMs) { return true; }
        @Override public void send(C2SMessage msg) { sent.add(msg); }
        @Override public String newId() { return "id-" + (++ids); }
        @Override public void setToken(String token) {}
        @Override public void reconnectWithToken() {}
    }

    @Test
    @DisplayName("subject, lines, a dot: one compose_send, and the lines were never commands")
    void aLetter() {
        var session = new FakeSession();
        var screen = new ByteArrayOutputStream();
        var handler = new InputHandler(session, new PrintStream(screen));

        handler.handle("mail mia");
        assertEquals(1, session.sent.size());
        assertTrue(session.sent.get(0) instanceof C2SMessage.Command c && c.command().equals("mail"));

        handler.onCompose(new S2CMessage.Compose(0, "mail", "mia", "", "Writing to mia."));
        assertTrue(handler.isComposing());
        handler.handle("the garden");
        handler.handle("look");                 // a line of the letter, not a command
        handler.handle("");                     // a paragraph break
        handler.handle("go north tomorrow?");
        handler.handle(".");

        assertFalse(handler.isComposing());
        assertEquals(2, session.sent.size(), "one command, then one letter — no Say, no Go");
        var letter = (C2SMessage.ComposeSend) session.sent.get(1);
        assertEquals("mia", letter.to());
        assertEquals("the garden", letter.subject());
        assertEquals("look\n\ngo north tomorrow?", letter.body());
        assertTrue(screen.toString().contains("Subject?"), screen.toString());
    }

    @Test
    @DisplayName("a subject given on the command line skips the question; ~q abandons")
    void subjectKnownAndAbandon() {
        var session = new FakeSession();
        var handler = new InputHandler(session, new PrintStream(new ByteArrayOutputStream()));

        handler.onCompose(new S2CMessage.Compose(0, "mail", "mia", "tea", null));
        handler.handle("Four o'clock?");
        handler.handle(".");
        var letter = (C2SMessage.ComposeSend) session.sent.get(0);
        assertEquals("tea", letter.subject());
        assertEquals("Four o'clock?", letter.body());

        handler.onCompose(new S2CMessage.Compose(0, "journal-private", null, null, null));
        handler.handle("a thought");
        handler.handle("~q");
        assertFalse(handler.isComposing());
        assertEquals(1, session.sent.size(), "nothing sent for the abandoned page");

        handler.onCompose(new S2CMessage.Compose(0, "journal", null, null, null));
        handler.handle("kept");
        handler.handle(".");
        var page = (C2SMessage.ComposeSend) session.sent.get(1);
        assertEquals("journal", page.kind());
        assertEquals("kept", page.body());
    }
}

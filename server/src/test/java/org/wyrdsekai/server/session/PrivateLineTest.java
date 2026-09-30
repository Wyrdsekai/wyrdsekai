package org.wyrdsekai.server.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line that tells a person their library research is in reaches every live session of
 * theirs, on every surface, and no one else's: not another person's, not an anonymous visitor's.
 */
class PrivateLineTest {

    static final class Session implements ClientConnection {
        final String sessionId, playerId;
        final List<String> got = new ArrayList<>();
        Session(String sessionId, String playerId) { this.sessionId = sessionId; this.playerId = playerId; }
        @Override public String sessionId() { return sessionId; }
        @Override public String playerId() { return playerId; }
        @Override public String playerName() { return playerId; }
        @Override public boolean startRemoteSession(String remoteZoneId, String transitToken) { return false; }
        @Override public void endRemoteSession() {}
        @Override public boolean isProxying() { return false; }
        @Override public String currentRemoteZoneId() { return null; }
        @Override public boolean deliverLine(String text) { got.add(text); return true; }
    }

    @Test
    void only_the_persons_own_sessions_get_the_line() {
        var registry = new ClientConnectionRegistry();
        var adaSsh = new Session("s-1", "did:person:ada");
        var adaWeb = new Session("s-2", "did:person:ada");
        var bo = new Session("s-3", "did:person:bo");
        var anon = new Session("s-4", "anon-did:person:ada");
        for (var s : List.of(adaSsh, adaWeb, bo, anon)) registry.register(s);

        assertThat(PrivateLine.toPerson(registry, null, "did:person:ada", "Your library research is ready.")).isTrue();

        assertThat(adaSsh.got).containsExactly("Your library research is ready.");
        assertThat(adaWeb.got).containsExactly("Your library research is ready.");
        assertThat(bo.got).isEmpty();
        assertThat(anon.got).isEmpty();
    }

    @Test
    void a_person_with_no_live_session_gets_nothing_and_it_says_so() {
        var registry = new ClientConnectionRegistry();
        var bo = new Session("s-3", "did:person:bo");
        registry.register(bo);

        assertThat(PrivateLine.toPerson(registry, null, "did:person:ada", "Your library research is ready.")).isFalse();
        assertThat(bo.got).isEmpty();
    }
}

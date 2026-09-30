package org.wyrdsekai.server.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.server.ws.WyrdWebSocket;

import java.util.List;

/**
 * A line for one person only: every live session of theirs, on every surface (ssh, telnet, the
 * browser, the phone), matched by person rather than by login id; never a room, never anyone
 * else's session. The text is not logged.
 */
public final class PrivateLine {

    private static final Logger log = LoggerFactory.getLogger(PrivateLine.class);

    private PrivateLine() {}

    /** @return true when at least one live session of theirs took the line */
    public static boolean toPerson(ClientConnectionRegistry registry, WyrdWebSocket ws, String personId, String text) {
        if (personId == null || personId.isBlank() || text == null || text.isBlank()) return false;
        boolean delivered = false;
        if (registry != null) {
            for (var c : registry.all()) {
                if (!isThem(personId, c.playerId())) continue;
                try {
                    delivered |= c.deliverLine(text);
                } catch (RuntimeException e) {
                    log.debug("A private line could not be given to session {}: {}", c.sessionId(), e.getMessage());
                }
            }
        }
        if (!delivered && ws != null) {
            delivered = ws.deliverToPlayer(personId, new S2CMessage.Prose(0L, "system", text, List.of(), null, "normal"));
        }
        return delivered;
    }

    /** The session's player is this person. Anonymous, device and tourist sessions never are. */
    static boolean isThem(String personId, String sessionPlayer) {
        if (sessionPlayer == null) return false;
        if (sessionPlayer.startsWith("anon-") || sessionPlayer.startsWith("device-")
                || sessionPlayer.startsWith("tourist-")) {
            return false;
        }
        return PersonIds.samePerson(personId, sessionPlayer);
    }
}

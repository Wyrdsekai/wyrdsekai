package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The actor's live bond map has one key per person, the canonical DID, whatever id the
 * person arrived under — and never holds a bond that is not hers.
 *
 * <p>Household node, 2026-09-13: room speech carries the login id, the bondholder
 * announcement carries the person DID; keyed raw, the map re-formed the bond under the
 * login id after every load-time merge, typed the bondholder MEMBER, and wrote the login
 * id back over the DID. On a two-companion node a bond held under the wrong soul credited
 * one companion's conversations to the other.</p>
 */
class OnePersonOneKeyInTheActorTest {

    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    @DisplayName("every door into activeBonds goes through personKey")
    void everyDoorIsCanonical() throws IOException {
        var src = Files.readString(ACTOR);
        assertTrue(src.contains("private String personKey(String id)"));
        var track = src.indexOf("private void trackBondInteraction(String speakerDid");
        assertTrue(track > 0);
        var body = src.substring(track, track + 2600);
        assertTrue(body.contains("speakerDid = personKey(speakerDid);"), "room speech is keyed by the person");
        assertTrue(body.contains("!existing.involves(myDid)"), "a bond that is not hers is dropped, not counted on");
        assertTrue(body.contains("existing.withOtherParty(myDid, speakerDid)"), "the row converges on the key");
        var announce = src.indexOf("private Behavior<Command> onBondholderAnnounced(");
        assertTrue(src.substring(announce, announce + 600).contains("personKey(msg.playerId())"),
            "the announced bondholder is keyed by the person");
        var restore = src.indexOf("private void restoreBondsAndCapacity()");
        var restoreBody = src.substring(restore, restore + 1600);
        assertTrue(restoreBody.contains("!bond.involves(myDid)"), "a manifest bond naming another soul is not restored");
        assertTrue(restoreBody.contains("personKey(bond.otherParty(myDid))"));
        assertTrue(src.contains("var key = personKey(b.otherParty(myDid));"), "store pickup is keyed by the person");
        assertTrue(src.contains("personKey(mourning.otherParty(severMyDid))"));
        assertTrue(src.contains("personKey(severed.otherParty(doneMyDid))"));
    }
}

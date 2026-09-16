package org.wyrdsekai.core.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a letter goes, decided by how it is addressed. */
class MailAddressTest {

    private static MailAddress at(String raw) {
        return MailAddress.parse(raw, "neo");
    }

    @Test
    @DisplayName("a bare name is someone in this household")
    void bareName() {
        var a = at("mia");
        assertEquals(MailAddress.Scope.LOCAL, a.scope());
        assertEquals("mia", a.name());
        assertEquals("mia@neo", a.display(), "stored mail always carries the full form");
    }

    @Test
    @DisplayName("naming your own zone is local delivery, not a federation hop")
    void ownZoneIsLocal() {
        var a = at("mia@neo");
        assertEquals(MailAddress.Scope.LOCAL, a.scope());
        assertEquals("mia", a.name());
        assertEquals("mia@neo", a.display());
        assertTrue(a.isLocal());
        assertEquals(MailAddress.Scope.LOCAL, at("mia@NEO").scope(), "case does not change the zone");
    }

    @Test
    @DisplayName("another zone id is federated; a dotted host is the internet")
    void zoneVersusExternal() {
        var zone = at("mia@alpha");
        assertEquals(MailAddress.Scope.ZONE, zone.scope());
        assertEquals("alpha", zone.zone());

        var out = at("bob@example.org");
        assertEquals(MailAddress.Scope.EXTERNAL, out.scope());
        assertEquals("bob@example.org", out.display());
        assertEquals(MailAddress.Scope.EXTERNAL, at("bob@mail.example.co.uk").scope());
    }

    @Test
    @DisplayName("the zone.name form tell already accepts is taken too")
    void tellForm() {
        assertEquals(MailAddress.Scope.ZONE, at("alpha.mia").scope());
        assertEquals("mia", at("alpha.mia").name());
        assertEquals("mia@alpha", at("alpha.mia").display());
        assertEquals(MailAddress.Scope.LOCAL, at("neo.mia").scope(), "own zone, written the other way");
    }

    @Test
    @DisplayName("names with spaces and quotes survive; nonsense is refused")
    void namesAndNonsense() {
        var a = at("\"ada lovelace\"");
        assertEquals(MailAddress.Scope.LOCAL, a.scope());
        assertEquals("ada lovelace", a.name());
        assertEquals("ada lovelace", a.key());
        assertEquals("ada  lovelace", at("ada  lovelace").name());
        assertEquals("ada lovelace", at("ada  lovelace").key(), "spacing does not change who is meant");
        assertNull(at("@neo"));
        assertNull(at("mia@"));
        assertNull(at("   "));
        assertNull(MailAddress.parse(null, "neo"));
    }

    @Test
    @DisplayName("with no zone configured the household is still addressable")
    void defaultZone() {
        var a = MailAddress.parse("mia@home", null);
        assertEquals(MailAddress.Scope.LOCAL, a.scope());
    }
}

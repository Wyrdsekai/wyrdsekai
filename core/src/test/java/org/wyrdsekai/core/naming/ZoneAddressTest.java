package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZoneAddressTest {

    // Sample fingerprint — valid base58btc multibase shape, doesn't need to
    // correspond to a real key for tests that exercise parsing/rendering.
    private static final String FP = "z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";

    @Test void construct_happyPath() {
        var addr = new ZoneAddress(FP, "kitchen");
        assertEquals(FP, addr.fingerprint());
        assertEquals("kitchen", addr.label());
    }

    @Test void construct_rejectsReservedLabel() {
        assertThrows(IllegalArgumentException.class,
            () -> new ZoneAddress(FP, "home"));
    }

    @Test void construct_rejectsMalformedLabel() {
        assertThrows(IllegalArgumentException.class,
            () -> new ZoneAddress(FP, "Kitchen"));
    }

    @Test void construct_rejectsEmptyFingerprint() {
        assertThrows(IllegalArgumentException.class,
            () -> new ZoneAddress("", "kitchen"));
        assertThrows(IllegalArgumentException.class,
            () -> new ZoneAddress(null, "kitchen"));
    }

    @Test void construct_rejectsFingerprintWithoutMultibasePrefix() {
        // Catches the "someone passed a DID string here" mistake.
        assertThrows(IllegalArgumentException.class,
            () -> new ZoneAddress("did:wyrd:z6Mkh…", "kitchen"));
        // Catches "raw base58 without the z prefix"
        assertThrows(IllegalArgumentException.class,
            () -> new ZoneAddress("6MkhaXg…", "kitchen"));
    }

    @Test void toCanonical_rendersWireForm() {
        var addr = new ZoneAddress(FP, "kitchen");
        assertEquals("did:wyrd:" + FP + ":kitchen", addr.toCanonical());
    }

    @Test void toWireSubject_rendersNatsSafeForm() {
        var addr = new ZoneAddress(FP, "kitchen");
        assertEquals(FP + ".kitchen", addr.toWireSubject());
    }

    @Test void toString_matchesCanonical() {
        var addr = new ZoneAddress(FP, "kitchen");
        assertEquals(addr.toCanonical(), addr.toString());
    }

    @Test void parseCanonical_roundTrip() {
        var original = new ZoneAddress(FP, "kitchen");
        var parsed = ZoneAddress.parseCanonical(original.toCanonical());
        assertTrue(parsed.isPresent());
        assertEquals(original, parsed.get());
    }

    @Test void parseCanonical_rejectsMissingScheme() {
        assertTrue(ZoneAddress.parseCanonical(FP + ":kitchen").isEmpty());
    }

    @Test void parseCanonical_rejectsMissingLabel() {
        assertTrue(ZoneAddress.parseCanonical("did:wyrd:" + FP).isEmpty());
        assertTrue(ZoneAddress.parseCanonical("did:wyrd:" + FP + ":").isEmpty());
    }

    @Test void parseCanonical_rejectsMalformedLabel() {
        assertTrue(ZoneAddress.parseCanonical("did:wyrd:" + FP + ":Kitchen").isEmpty());
        assertTrue(ZoneAddress.parseCanonical("did:wyrd:" + FP + ":home").isEmpty());
    }

    @Test void parseCanonical_nullSafe() {
        assertTrue(ZoneAddress.parseCanonical(null).isEmpty());
        assertTrue(ZoneAddress.parseCanonical("").isEmpty());
    }

    @Test void parseWireSubject_roundTrip() {
        var original = new ZoneAddress(FP, "kitchen");
        var parsed = ZoneAddress.parseWireSubject(original.toWireSubject());
        assertTrue(parsed.isPresent());
        assertEquals(original, parsed.get());
    }

    @Test void parseWireSubject_rejectsMissingSeparator() {
        assertTrue(ZoneAddress.parseWireSubject(FP).isEmpty());
    }

    @Test void parseWireSubject_splitsAtLastDot() {
        // Fingerprints are base58btc (no dots); labels reject dots; so the
        // last-dot rule produces an unambiguous split. Verify explicitly.
        var parsed = ZoneAddress.parseWireSubject(FP + ".kitchen");
        assertTrue(parsed.isPresent());
        assertEquals(FP, parsed.get().fingerprint());
        assertEquals("kitchen", parsed.get().label());
    }

    @Test void equality_byFingerprintAndLabel() {
        var a = new ZoneAddress(FP, "kitchen");
        var b = new ZoneAddress(FP, "kitchen");
        var c = new ZoneAddress(FP, "garage");
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}

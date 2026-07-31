package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeVerificationModeTest {

    @Test void fromString_canonicalNames() {
        assertEquals(EnvelopeVerificationMode.OFF,
            EnvelopeVerificationMode.fromString("off", EnvelopeVerificationMode.HARD));
        assertEquals(EnvelopeVerificationMode.SOFT,
            EnvelopeVerificationMode.fromString("soft", EnvelopeVerificationMode.OFF));
        assertEquals(EnvelopeVerificationMode.HARD,
            EnvelopeVerificationMode.fromString("hard", EnvelopeVerificationMode.SOFT));
    }

    @Test void fromString_aliases() {
        // Operators don't all say "soft" / "hard" — accept common synonyms.
        assertEquals(EnvelopeVerificationMode.OFF,
            EnvelopeVerificationMode.fromString("disabled", EnvelopeVerificationMode.HARD));
        assertEquals(EnvelopeVerificationMode.OFF,
            EnvelopeVerificationMode.fromString("none", EnvelopeVerificationMode.HARD));
        assertEquals(EnvelopeVerificationMode.SOFT,
            EnvelopeVerificationMode.fromString("warn", EnvelopeVerificationMode.OFF));
        assertEquals(EnvelopeVerificationMode.HARD,
            EnvelopeVerificationMode.fromString("strict", EnvelopeVerificationMode.SOFT));
        assertEquals(EnvelopeVerificationMode.HARD,
            EnvelopeVerificationMode.fromString("enforce", EnvelopeVerificationMode.SOFT));
    }

    @Test void fromString_caseInsensitive() {
        assertEquals(EnvelopeVerificationMode.HARD,
            EnvelopeVerificationMode.fromString("HARD", EnvelopeVerificationMode.SOFT));
        assertEquals(EnvelopeVerificationMode.SOFT,
            EnvelopeVerificationMode.fromString("Soft", EnvelopeVerificationMode.OFF));
    }

    @Test void fromString_trimsWhitespace() {
        assertEquals(EnvelopeVerificationMode.HARD,
            EnvelopeVerificationMode.fromString("  hard  ", EnvelopeVerificationMode.SOFT));
    }

    @Test void fromString_unknownFallsBack() {
        assertEquals(EnvelopeVerificationMode.HARD,
            EnvelopeVerificationMode.fromString("noperinos", EnvelopeVerificationMode.HARD));
        assertEquals(EnvelopeVerificationMode.SOFT,
            EnvelopeVerificationMode.fromString("maybe", EnvelopeVerificationMode.SOFT));
    }

    @Test void fromString_nullAndBlankFallBack() {
        assertEquals(EnvelopeVerificationMode.SOFT,
            EnvelopeVerificationMode.fromString(null, EnvelopeVerificationMode.SOFT));
        assertEquals(EnvelopeVerificationMode.HARD,
            EnvelopeVerificationMode.fromString("", EnvelopeVerificationMode.HARD));
        assertEquals(EnvelopeVerificationMode.HARD,
            EnvelopeVerificationMode.fromString("   ", EnvelopeVerificationMode.HARD));
    }

    @Test void fromEnv_defaultsToSoft() {
        // Phase-1 default per spec §7 — stays SOFT until operators flip.
        // Env isn't set in CI, so this should return the documented default.
        // (If WYRDSEKAI_ENVELOPE_VERIFY is set in a dev env, this test won't
        // pass — that's expected; no way to unset env from a Java test.)
        if (System.getenv("WYRDSEKAI_ENVELOPE_VERIFY") == null) {
            assertEquals(EnvelopeVerificationMode.SOFT, EnvelopeVerificationMode.fromEnv());
        }
    }
}

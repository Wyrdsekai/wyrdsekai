package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZoneLabelsTest {

    @Test void wellFormed_acceptsSimpleLowercase() {
        assertTrue(ZoneLabels.isWellFormed("kitchen"));
        assertTrue(ZoneLabels.isWellFormed("garage"));
        assertTrue(ZoneLabels.isWellFormed("a"));
    }

    @Test void wellFormed_acceptsInternalHyphen() {
        assertTrue(ZoneLabels.isWellFormed("bob-studio"));
        assertTrue(ZoneLabels.isWellFormed("tea-room-2"));
    }

    @Test void wellFormed_acceptsDigits() {
        assertTrue(ZoneLabels.isWellFormed("zone1"));
        assertTrue(ZoneLabels.isWellFormed("room42"));
    }

    @Test void wellFormed_rejectsLeadingHyphen() {
        assertFalse(ZoneLabels.isWellFormed("-foo"));
    }

    @Test void wellFormed_rejectsTrailingHyphen() {
        assertFalse(ZoneLabels.isWellFormed("foo-"));
    }

    @Test void wellFormed_rejectsUppercase() {
        assertFalse(ZoneLabels.isWellFormed("Kitchen"));
        assertFalse(ZoneLabels.isWellFormed("KITCHEN"));
    }

    @Test void wellFormed_rejectsUnderscore() {
        assertFalse(ZoneLabels.isWellFormed("bob_studio"));
    }

    @Test void wellFormed_rejectsDot() {
        // Dots would conflict with NATS subject separators.
        assertFalse(ZoneLabels.isWellFormed("kitchen.main"));
    }

    @Test void wellFormed_rejectsColon() {
        // Colons would conflict with the alias:label parsing grammar.
        assertFalse(ZoneLabels.isWellFormed("kitchen:main"));
    }

    @Test void wellFormed_rejectsEmpty() {
        assertFalse(ZoneLabels.isWellFormed(""));
        assertFalse(ZoneLabels.isWellFormed(null));
    }

    @Test void wellFormed_rejectsTooLong() {
        var tooLong = "a".repeat(33);
        assertFalse(ZoneLabels.isWellFormed(tooLong));
    }

    @Test void wellFormed_acceptsMaxLength() {
        var maxLen = "a".repeat(32);
        assertTrue(ZoneLabels.isWellFormed(maxLen));
    }

    @Test void reserved_all5Keywords() {
        assertTrue(ZoneLabels.isReserved("home"));
        assertTrue(ZoneLabels.isReserved("self"));
        assertTrue(ZoneLabels.isReserved("me"));
        assertTrue(ZoneLabels.isReserved("here"));
        assertTrue(ZoneLabels.isReserved("origin"));
    }

    @Test void reserved_caseInsensitive() {
        // Spec §2.4 says "home is reserved" — we enforce regardless of case.
        assertTrue(ZoneLabels.isReserved("Home"));
        assertTrue(ZoneLabels.isReserved("HOME"));
        assertTrue(ZoneLabels.isReserved("Self"));
    }

    @Test void reserved_doesNotFalsePositiveSubstrings() {
        assertFalse(ZoneLabels.isReserved("homepage"));
        assertFalse(ZoneLabels.isReserved("origins"));
        assertFalse(ZoneLabels.isReserved("myself"));
    }

    @Test void valid_combinesBoth() {
        assertTrue(ZoneLabels.isValid("kitchen"));
        assertFalse(ZoneLabels.isValid("home"));            // reserved
        assertFalse(ZoneLabels.isValid("Kitchen"));         // malformed
        assertFalse(ZoneLabels.isValid("-foo"));            // malformed
    }

    @Test void requireValid_rejectsReservedWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> ZoneLabels.requireValid("home", "zone label"));
        assertTrue(ex.getMessage().contains("reserved"));
        assertTrue(ex.getMessage().contains("home"));
    }

    @Test void requireValid_rejectsMalformedWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> ZoneLabels.requireValid("Kitchen", "zone label"));
        // Should NOT mention "reserved" — this is a charset issue.
        assertFalse(ex.getMessage().contains("reserved"));
        assertTrue(ex.getMessage().contains("valid zone label")
            || ex.getMessage().contains("lowercase"));
    }

    @Test void requireValid_rejectsEmpty() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> ZoneLabels.requireValid("", "alias"));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test void requireValid_acceptsValid() {
        assertDoesNotThrow(() -> ZoneLabels.requireValid("kitchen", "zone label"));
        assertDoesNotThrow(() -> ZoneLabels.requireValid("bob-studio", "alias"));
    }
}

package org.wyrdsekai.core.household;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quiet hours are a household rule the rooms keep, not manners a visitor may or may not have. */
class QuietHoursTest {

    @AfterEach
    void tearDown() { QuietHours.configure(null); }

    @Test
    @DisplayName("an overnight window wraps midnight; a daytime window does not")
    void windows() {
        QuietHours.configure("22:00-07:00");
        assertTrue(QuietHours.isQuiet(LocalTime.of(23, 30)));
        assertTrue(QuietHours.isQuiet(LocalTime.of(3, 0)));
        assertTrue(QuietHours.isQuiet(LocalTime.of(22, 0)), "the start minute is quiet");
        assertFalse(QuietHours.isQuiet(LocalTime.of(7, 0)), "the end minute is awake");
        assertFalse(QuietHours.isQuiet(LocalTime.of(12, 0)));
        QuietHours.configure("13:00-14:00");
        assertTrue(QuietHours.isQuiet(LocalTime.of(13, 30)));
        assertFalse(QuietHours.isQuiet(LocalTime.of(20, 0)));
        assertEquals("The household keeps quiet hours until 14:00.", QuietHours.until());
    }

    @Test
    @DisplayName("no window, a blank one, or a malformed one means never quiet")
    void offByDefault() {
        QuietHours.configure(null);
        assertFalse(QuietHours.isQuiet(LocalTime.of(3, 0)));
        QuietHours.configure("");
        assertFalse(QuietHours.isQuiet(LocalTime.of(3, 0)));
        QuietHours.configure("late-early");
        assertFalse(QuietHours.isQuiet(LocalTime.of(3, 0)));
        assertEquals("", QuietHours.until());
    }
}

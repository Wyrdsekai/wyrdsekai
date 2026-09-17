package org.wyrdsekai.core.household;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quiet hours were a boot-time setting; now the record has the say. A window the steward set
 * overrides the config, "off" clears it, and an empty record falls back to the config.
 */
class TheRecordSaysWhenTheHouseIsQuietTest {

    @AfterEach
    void tearDown() {
        QuietHours.install(null);
        QuietHours.configure("");
    }

    @Test
    @DisplayName("the record overrides the config, and off clears it")
    void theRecordHasTheSay() {
        QuietHours.configure("22:00-07:00");
        var record = new AtomicReference<String>(null);
        QuietHours.install(record::get);
        assertTrue(QuietHours.isQuiet(LocalTime.of(3, 0)), "config applies while the record is silent");

        record.set("01:00-02:00");
        QuietHours.refresh();
        assertEquals("01:00-02:00", QuietHours.spec());
        assertFalse(QuietHours.isQuiet(LocalTime.of(3, 0)));
        assertTrue(QuietHours.isQuiet(LocalTime.of(1, 30)));

        record.set("off");
        QuietHours.refresh();
        assertEquals("", QuietHours.spec());
        assertFalse(QuietHours.isQuiet(LocalTime.of(1, 30)));
        assertEquals("", QuietHours.until());
    }

    @Test
    @DisplayName("a record that cannot be read is the config, not an error")
    void aBrokenRecordIsTheConfig() {
        QuietHours.configure("22:00-07:00");
        QuietHours.install(() -> { throw new IllegalStateException("locked"); });
        assertTrue(QuietHours.isQuiet(LocalTime.of(23, 0)));
    }
}

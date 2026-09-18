package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the body acted against is remembered for a year from the last sighting: a second
 * sighting counts and moves the expiry, the steward can forget one entry, and an expired entry
 * is not recalled. The memory lives in the record and comes back after a restart.
 */
class TheBodyRemembersAnAttackerForAYearTest {

    @AfterEach
    void tearDown() {
        ImmuneMemory.resetForTests();
    }

    @Test
    @DisplayName("a year by default; a second sighting counts; forget and expiry work")
    void inMemory() {
        var m = ImmuneMemory.inMemory();
        var first = m.remember("visitor", "did:key:z6MkBad", "forged manifest", "dock", null);
        assertEquals(1, first.count());
        var year = Duration.between(Instant.now(), first.expiresAt());
        assertTrue(year.toDays() >= 364 && year.toDays() <= 365, "remembered for a year: " + year.toDays());

        var second = m.remember("visitor", "did:key:z6MkBad", null, null, null);
        assertEquals(first.id(), second.id());
        assertEquals(2, second.count());
        assertEquals("forged manifest", second.reason(), "a null reason keeps the old one");
        assertEquals(first.firstSeen(), second.firstSeen());

        assertTrue(m.recall("visitor", "did:key:z6MkBad").isPresent());
        assertTrue(m.recallAny("did:key:z6MkBad").isPresent());
        assertFalse(m.recall("door", "did:key:z6MkBad").isPresent(), "kinds are distinct");

        m.remember("reach", "curl /etc/shadow", "reached for the host's secrets", "hooks", Duration.ofMillis(1));
        try { Thread.sleep(5); } catch (InterruptedException ignored) { /* fine */ }
        assertFalse(m.recall("reach", "curl /etc/shadow").isPresent(), "expired is forgotten");
        assertEquals(1, m.expire());
        assertEquals(1, m.list().size());

        assertTrue(m.forget(first.id()));
        assertFalse(m.forget(first.id()));
        assertTrue(m.list().isEmpty());
    }

    @Test
    @DisplayName("the memory lives in the record and survives a restart")
    void onRecord() throws Exception {
        var db = Files.createTempFile("immune-memory", ".db");
        var url = "jdbc:sqlite:" + db.toAbsolutePath();
        try (var conn = DriverManager.getConnection(url)) {
            BodyStore.ensureTables(conn);
            BodyStore.ensureImmune(conn);
        }
        var m = ImmuneMemory.onRecord(url);
        var e = m.remember("capability", "shell", "a visitor asked for the shell", "library", null);
        m.remember("capability", "shell", null, null, null);

        var again = ImmuneMemory.onRecord(url);
        var back = again.recall("capability", "shell").orElseThrow();
        assertEquals(e.id(), back.id());
        assertEquals(2, back.count());
        assertEquals("library", back.source());
        assertEquals(1, again.list().size());
        assertTrue(again.forget(e.id()));
        assertTrue(again.list().isEmpty());
        Files.deleteIfExists(db);
    }
}

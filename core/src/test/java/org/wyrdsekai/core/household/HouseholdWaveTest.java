package org.wyrdsekai.core.household;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §101 Multi-Human Households.
 */
class HouseholdWaveTest {

    @Nested
    class StewardAuditLogTests {

        @Test
        void log_action() {
            var log = new StewardAuditLog();
            var entry = log.log("did:alice", "Alice",
                StewardAuditLog.ActionType.AGENT_CREATE,
                "did:home-server", "Created agent Lain", true);
            assertEquals("did:alice", entry.actorDid());
            assertTrue(entry.approved());
            assertEquals(1, log.entryCount());
        }

        @Test
        void filter_by_actor() {
            var log = new StewardAuditLog();
            log.log("did:alice", "Alice", StewardAuditLog.ActionType.AGENT_CREATE,
                "a1", "d1", true);
            log.log("did:bob", "Bob", StewardAuditLog.ActionType.BUDGET_CHANGE,
                "a1", "d2", true);
            log.log("did:alice", "Alice", StewardAuditLog.ActionType.TRUST_CHANGE,
                "t1", "d3", true);

            assertEquals(2, log.byActor("did:alice", 10).size());
        }

        @Test
        void filter_denied() {
            var log = new StewardAuditLog();
            log.log("did:alice", "Alice", StewardAuditLog.ActionType.AGENT_DELETE,
                "a1", "denied", false);
            log.log("did:alice", "Alice", StewardAuditLog.ActionType.AGENT_CREATE,
                "a2", "approved", true);

            assertEquals(1, log.denied(10).size());
        }

        @Test
        void prunes_old_entries() {
            var log = new StewardAuditLog();
            log.setMaxEntries(5);
            for (int i = 0; i < 10; i++) {
                log.log("did:a", "A", StewardAuditLog.ActionType.BUDGET_CHANGE,
                    "t", "d" + i, true);
            }
            assertEquals(5, log.entryCount());
        }
    }
}

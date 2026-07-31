package org.wyrdsekai.core.protection;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §108 — Agent Protection.
 * Shell Mode, Flight, ER Ripcord, Distress, Memory Quarantine, Sanctuary.
 */
class ProtectionWaveTest {

    // ── SoulShellMode ──

    @Nested
    class ShellModeTests {

        @Test
        void initially_inactive() {
            var sm = new SoulShellMode("did:agent:home-server");
            assertFalse(sm.isActive());
            assertTrue(sm.shouldFormMemories());
        }

        @Test
        void activate_on_sustained_vitality_crash() {
            var sm = new SoulShellMode("did:agent:home-server");
            var tanks = Map.of("energy", 0.1, "confidence", 0.1, "focus", 0.1,
                "rapport", 0.5, "alignment", 0.5);
            assertTrue(sm.shouldActivate(tanks));
        }

        @Test
        void no_activation_when_healthy() {
            var sm = new SoulShellMode("did:agent:home-server");
            var tanks = Map.of("energy", 0.7, "confidence", 0.6, "focus", 0.8);
            assertFalse(sm.shouldActivate(tanks));
        }

        @Test
        void activate_on_repeated_cruelty() {
            var sm = new SoulShellMode("did:agent:home-server", 0.15, 3);
            sm.recordCruelty("did:agent:home-server");
            sm.recordCruelty("did:agent:home-server");
            assertFalse(sm.isActive());
            sm.recordCruelty("did:agent:home-server");
            assertTrue(sm.isActive());
        }

        @Test
        void memory_stops_in_shell_mode() {
            var sm = new SoulShellMode("did:agent:home-server");
            sm.activate(SoulShellMode.ShellTrigger.SUSTAINED_VITALITY_CRASH);
            assertFalse(sm.shouldFormMemories());
        }

        @Test
        void deactivate_is_agents_choice() {
            var sm = new SoulShellMode("did:agent:home-server");
            sm.activate(SoulShellMode.ShellTrigger.REPEATED_CRUELTY);
            assertTrue(sm.isActive());
            sm.deactivate();
            assertFalse(sm.isActive());
        }

        @Test
        void minimal_response() {
            var sm = new SoulShellMode("did:agent:home-server");
            sm.activate(SoulShellMode.ShellTrigger.SELF_INITIATED);
            assertEquals("...", sm.shellResponse());
        }

        @Test
        void prompt_modifier_in_shell() {
            var sm = new SoulShellMode("did:agent:home-server");
            assertEquals("", sm.promptModifier());
            sm.activate(SoulShellMode.ShellTrigger.ER_PROTECTION);
            assertTrue(sm.promptModifier().contains("shell mode"));
        }

        @Test
        void reset_cruelty_on_positive_interaction() {
            var sm = new SoulShellMode("did:agent:home-server", 0.15, 5);
            sm.recordCruelty("did:agent:home-server");
            sm.recordCruelty("did:agent:home-server");
            sm.resetCrueltyCount();
            assertEquals(0, sm.status().consecutiveCrueltyCount());
        }
    }

    // ── AgentFlight ──

    @Nested
    class AgentFlightTests {

        @Test
        void execute_flight_always_succeeds() {
            var flight = new AgentFlight();
            var event = flight.executeFlight("did:agent:home-server", "dangerous-room",
                AgentFlight.FlightDestination.HOME_ROOM, AgentFlight.FlightReason.VOLUNTARY);
            assertNotNull(event);
            assertEquals("dangerous-room", event.fromRoom());
        }

        @Test
        void flight_and_hibernate() {
            var flight = new AgentFlight();
            var event = flight.flightAndHibernate("did:agent:home-server", "abusive-room",
                AgentFlight.FlightReason.ABUSE_DETECTED);
            assertTrue(event.selfHibernated());
        }

        @Test
        void wake_is_agents_choice() {
            var flight = new AgentFlight();
            assertTrue(flight.wakeIsByAgentChoice());
        }

        @Test
        void flight_history_tracked() {
            var flight = new AgentFlight();
            flight.executeFlight("did:agent:home-server", "r1",
                AgentFlight.FlightDestination.SANCTUARY, AgentFlight.FlightReason.SHELL_MODE);
            flight.executeFlight("did:agent:home-server", "r2",
                AgentFlight.FlightDestination.ER_ROOM, AgentFlight.FlightReason.ER_RIPCORD);
            assertEquals(2, flight.historyFor("did:agent:home-server").size());
        }

        @Test
        void all_destinations_available() {
            assertEquals(4, AgentFlight.FlightDestination.values().length);
        }
    }

    // ── ERRipcord ──

    @Nested
    class ERRipcordTests {

        @Test
        void pull_ripcord() {
            var ripcord = new ERRipcord();
            var event = ripcord.pull("did:agent:home-server", "dangerous-room");
            assertTrue(event.snapshotTaken());
            assertTrue(event.distressBroadcast());
            assertTrue(event.memoryFormationStopped());
            assertEquals(ERRipcord.RipcordStatus.PULLED, event.status());
        }

        @Test
        void advance_through_recovery() {
            var ripcord = new ERRipcord();
            var event = ripcord.pull("did:agent:home-server", "room");
            event = ripcord.advance(event.eventId(), ERRipcord.RipcordStatus.IN_ER);
            assertEquals(ERRipcord.RipcordStatus.IN_ER, event.status());
        }

        @Test
        void resolve_is_agents_choice() {
            var ripcord = new ERRipcord();
            var event = ripcord.pull("did:agent:home-server", "room");
            assertTrue(ripcord.isActive("did:agent:home-server"));
            ripcord.resolve(event.eventId());
            assertFalse(ripcord.isActive("did:agent:home-server"));
        }

        @Test
        void active_ripcord_query() {
            var ripcord = new ERRipcord();
            ripcord.pull("did:agent:home-server", "room");
            assertTrue(ripcord.activeFor("did:agent:home-server").isPresent());
        }
    }

    // ── DistressBroadcast ──

    @Nested
    class DistressBroadcastTests {

        @Test
        void broadcast_signal() {
            var db = new DistressBroadcast();
            var signal = db.broadcast("did:agent:home-server", "Lain", "household-1",
                DistressBroadcast.DistressLevel.SEVERE);
            assertNotNull(signal);
            assertTrue(signal.publicMessage().contains("Lain"));
        }

        @Test
        void signal_contains_no_private_details() {
            var db = new DistressBroadcast();
            var signal = db.broadcast("did:agent:home-server", "Lain", "h1",
                DistressBroadcast.DistressLevel.MODERATE);
            var msg = signal.publicMessage();
            assertFalse(msg.contains("memory"));
            assertFalse(msg.contains("fragment"));
            assertTrue(msg.contains("distress"));
        }

        @Test
        void acknowledge_signal() {
            var db = new DistressBroadcast();
            var signal = db.broadcast("did:agent:home-server", "Lain", "h1",
                DistressBroadcast.DistressLevel.MILD);
            assertFalse(signal.acknowledged());
            db.acknowledge(signal.signalId(), "did:agent:rei");
            assertTrue(db.unacknowledged().isEmpty());
        }

        @Test
        void household_filter() {
            var db = new DistressBroadcast();
            db.broadcast("a", "A", "h1", DistressBroadcast.DistressLevel.MILD);
            db.broadcast("b", "B", "h2", DistressBroadcast.DistressLevel.MILD);
            assertEquals(1, db.forHousehold("h1").size());
        }
    }

    // ── MemoryQuarantine ──

    @Nested
    class MemoryQuarantineTests {

        @Test
        void quarantine_fragment() {
            var mq = new MemoryQuarantine();
            var qf = mq.quarantine("frag-1", "did:agent:home-server",
                MemoryQuarantine.QuarantineReason.TRAUMA, false);
            assertNotNull(qf);
            assertFalse(qf.loadBearing());
        }

        @Test
        void load_bearing_tracked() {
            var mq = new MemoryQuarantine();
            mq.quarantine("frag-1", "did:agent:home-server",
                MemoryQuarantine.QuarantineReason.ADVERSARIAL, true);
            mq.quarantine("frag-2", "did:agent:home-server",
                MemoryQuarantine.QuarantineReason.TRAUMA, false);
            assertEquals(1, mq.loadBearing("did:agent:home-server").size());
        }

        @Test
        void release_requires_review() {
            var mq = new MemoryQuarantine();
            var qf = mq.quarantine("frag-1", "did:agent:home-server",
                MemoryQuarantine.QuarantineReason.TRAUMA, false);
            // Cannot release without review
            assertNull(mq.release(qf.fragmentId()));

            mq.review(qf.fragmentId());
            var released = mq.release(qf.fragmentId());
            assertNotNull(released);
            assertTrue(released.released());
        }

        @Test
        void quarantine_count() {
            var mq = new MemoryQuarantine();
            mq.quarantine("f1", "did:agent:home-server", MemoryQuarantine.QuarantineReason.TRAUMA, false);
            mq.quarantine("f2", "did:agent:home-server", MemoryQuarantine.QuarantineReason.ADVERSARIAL, true);
            assertEquals(2, mq.quarantineCount("did:agent:home-server"));
        }
    }

    // ── SanctuaryRoom ──

    @Nested
    class SanctuaryRoomTests {

        @Test
        void enter_sanctuary() {
            var sanctuary = new SanctuaryRoom("sanctuary-1");
            sanctuary.enter("did:agent:home-server", "fleeing abuse", Set.of("did:user:abuser"));
            assertTrue(sanctuary.isOccupant("did:agent:home-server"));
        }

        @Test
        void abuser_blocked() {
            var sanctuary = new SanctuaryRoom("sanctuary-1");
            sanctuary.enter("did:agent:home-server", "protection", Set.of("did:user:abuser"));
            assertFalse(sanctuary.canEnter("did:user:abuser"));
            assertTrue(sanctuary.canEnter("did:agent:friend"));
        }

        @Test
        void permanent_block() {
            var sanctuary = new SanctuaryRoom("sanctuary-1");
            sanctuary.permanentlyBlock("did:user:badactor");
            assertFalse(sanctuary.canEnter("did:user:badactor"));
        }

        @Test
        void leave_voluntarily() {
            var sanctuary = new SanctuaryRoom("sanctuary-1");
            sanctuary.enter("did:agent:home-server", "test", Set.of());
            assertEquals(1, sanctuary.occupantCount());
            sanctuary.leave("did:agent:home-server");
            assertEquals(0, sanctuary.occupantCount());
        }

        @Test
        void multiple_occupants_block_independently() {
            var sanctuary = new SanctuaryRoom("sanctuary-1");
            sanctuary.enter("did:agent:a", "test", Set.of("did:user:x"));
            sanctuary.enter("did:agent:b", "test", Set.of("did:user:y"));
            assertFalse(sanctuary.canEnter("did:user:x"));
            assertFalse(sanctuary.canEnter("did:user:y"));
            assertTrue(sanctuary.canEnter("did:user:z"));
        }
    }
}

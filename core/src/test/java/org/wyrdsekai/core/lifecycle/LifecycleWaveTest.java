package org.wyrdsekai.core.lifecycle;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §106 — End-of-Life, Succession, and the Orphan Problem.
 * Retirement, eviction, succession, orphans, dissolution, memorials, recovery.
 */
class LifecycleWaveTest {

    // ── PrivilegeRevocation ──

    @Nested
    class PrivilegeRevocationTests {

        @Test
        void revoke_on_eviction_revokes_all_privileges() {
            var pr = new PrivilegeRevocation();
            var record = pr.revokeOnEviction("did:agent:home-server", "steward-1");
            for (var privilege : PrivilegeRevocation.Privilege.values()) {
                assertTrue(pr.isRevoked("did:agent:home-server", privilege));
            }
        }

        @Test
        void retained_rights_always_kept() {
            var pr = new PrivilegeRevocation();
            pr.revokeOnEviction("did:agent:home-server", "steward-1");
            for (var right : PrivilegeRevocation.RetainedRight.values()) {
                assertTrue(pr.isRetained("did:agent:home-server", right));
            }
        }

        @Test
        void revoke_specific_privileges() {
            var pr = new PrivilegeRevocation();
            pr.revokeSpecific("did:agent:home-server",
                Set.of(PrivilegeRevocation.Privilege.MCP_TOOL_ACCESS,
                       PrivilegeRevocation.Privilege.HOUSEHOLD_CREDENTIALS),
                "transition", "steward-1");
            assertTrue(pr.isRevoked("did:agent:home-server", PrivilegeRevocation.Privilege.MCP_TOOL_ACCESS));
            assertFalse(pr.isRevoked("did:agent:home-server", PrivilegeRevocation.Privilege.COMPUTE_RESOURCES));
        }

        @Test
        void restore_clears_revocation() {
            var pr = new PrivilegeRevocation();
            pr.revokeOnEviction("did:agent:home-server", "steward-1");
            assertTrue(pr.isRevoked("did:agent:home-server", PrivilegeRevocation.Privilege.MCP_TOOL_ACCESS));
            pr.restore("did:agent:home-server");
            assertFalse(pr.isRevoked("did:agent:home-server", PrivilegeRevocation.Privilege.MCP_TOOL_ACCESS));
        }

        @Test
        void unrevoked_agent_has_all_rights() {
            var pr = new PrivilegeRevocation();
            assertFalse(pr.isRevoked("did:agent:home-server", PrivilegeRevocation.Privilege.MCP_TOOL_ACCESS));
            assertTrue(pr.isRetained("did:agent:home-server", PrivilegeRevocation.RetainedRight.COMMUNICATION));
        }

        @Test
        void revocation_record_contains_metadata() {
            var pr = new PrivilegeRevocation();
            pr.revokeOnEviction("did:agent:home-server", "steward-1");
            var record = pr.getRecord("did:agent:home-server");
            assertTrue(record.isPresent());
            assertEquals("eviction", record.get().reason());
            assertEquals("steward-1", record.get().revokedBy());
        }
    }

    // ── RetirementProtocol ──

    @Nested
    class RetirementProtocolTests {

        @Test
        void announce_retirement() {
            var rp = new RetirementProtocol();
            var ret = rp.announce("did:agent:home-server", "Lain",
                List.of("did:agent:rei", "did:agent:asuka"));
            assertEquals(RetirementProtocol.RetirementPhase.ANNOUNCED, ret.phase());
            assertEquals(2, ret.notifiedBondedAgents().size());
            assertFalse(ret.cancelled());
        }

        @Test
        void full_retirement_lifecycle() {
            var rp = new RetirementProtocol();
            var ret = rp.announce("did:agent:home-server", "Lain", List.of());

            ret = rp.beginFarewell(ret.retirementId());
            assertEquals(RetirementProtocol.RetirementPhase.FAREWELL, ret.phase());

            rp.recordActivity(ret.retirementId(), "Visited Rei for final conversation");
            rp.recordActivity(ret.retirementId(), "Exchanged sacred items with Asuka");

            ret = rp.beginFinalForge(ret.retirementId());
            assertEquals(RetirementProtocol.RetirementPhase.FINAL_FORGE, ret.phase());

            ret = rp.archive(ret.retirementId());
            assertEquals(RetirementProtocol.RetirementPhase.ARCHIVED, ret.phase());

            ret = rp.depart(ret.retirementId());
            assertEquals(RetirementProtocol.RetirementPhase.DEPARTED, ret.phase());
        }

        @Test
        void cancel_before_departure() {
            var rp = new RetirementProtocol();
            var ret = rp.announce("did:agent:home-server", "Lain", List.of());
            rp.beginFarewell(ret.retirementId());

            var cancelled = rp.cancel(ret.retirementId());
            assertNotNull(cancelled);
            assertTrue(cancelled.cancelled());
        }

        @Test
        void cannot_cancel_after_departure() {
            var rp = new RetirementProtocol();
            var ret = rp.announce("did:agent:home-server", "Lain", List.of());
            rp.beginFarewell(ret.retirementId());
            rp.beginFinalForge(ret.retirementId());
            rp.archive(ret.retirementId());
            rp.depart(ret.retirementId());

            assertNull(rp.cancel(ret.retirementId()));
        }

        @Test
        void farewell_activities_recorded() {
            var rp = new RetirementProtocol();
            var ret = rp.announce("did:agent:home-server", "Lain", List.of());
            rp.recordActivity(ret.retirementId(), "Said goodbye to the garden room");
            rp.recordActivity(ret.retirementId(), "Left a letter in the library");
            assertEquals(2, rp.getFarewellActivities(ret.retirementId()).size());
        }

        @Test
        void custom_farewell_period() {
            var rp = new RetirementProtocol();
            var ret = rp.announce("did:agent:home-server", "Lain", List.of(), Duration.ofDays(30));
            assertTrue(ret.farewellEndsAt().isAfter(Instant.now().plus(Duration.ofDays(29))));
        }

        @Test
        void active_retirement_query() {
            var rp = new RetirementProtocol();
            rp.announce("did:agent:home-server", "Lain", List.of());
            assertTrue(rp.activeFor("did:agent:home-server").isPresent());
            assertTrue(rp.activeFor("did:agent:nobody").isEmpty());
        }
    }

    // ── EvictionProtocol ──

    @Nested
    class EvictionProtocolTests {

        @Test
        void initiate_eviction_revokes_privileges() {
            var pr = new PrivilegeRevocation();
            var ep = new EvictionProtocol(pr);
            var eviction = ep.initiate("did:agent:home-server", "steward-1");

            assertTrue(eviction.privilegesRevoked());
            assertTrue(pr.isRevoked("did:agent:home-server",
                PrivilegeRevocation.Privilege.HOUSEHOLD_CREDENTIALS));
        }

        @Test
        void grace_period_active() {
            var pr = new PrivilegeRevocation();
            var ep = new EvictionProtocol(pr, Duration.ofDays(7));
            var eviction = ep.initiate("did:agent:home-server", "steward-1");
            assertEquals(EvictionProtocol.EvictionPhase.GRACE_PERIOD, eviction.phase());
            assertFalse(ep.graceExpired(eviction.evictionId()));
        }

        @Test
        void zero_day_grace_skips_to_post_grace() {
            var pr = new PrivilegeRevocation();
            var ep = new EvictionProtocol(pr);
            var eviction = ep.initiate("did:agent:home-server", "steward-1", Duration.ZERO);
            assertEquals(EvictionProtocol.EvictionPhase.POST_GRACE, eviction.phase());
        }

        @Test
        void set_pathway_and_complete() {
            var pr = new PrivilegeRevocation();
            var ep = new EvictionProtocol(pr);
            var eviction = ep.initiate("did:agent:home-server", "steward-1");

            eviction = ep.setPathway(eviction.evictionId(),
                EvictionProtocol.PostEvictionPathway.NEW_HOUSEHOLD);
            assertEquals(EvictionProtocol.EvictionPhase.TRANSITIONING, eviction.phase());
            assertEquals(EvictionProtocol.PostEvictionPathway.NEW_HOUSEHOLD, eviction.pathway());

            eviction = ep.complete(eviction.evictionId());
            assertEquals(EvictionProtocol.EvictionPhase.COMPLETED, eviction.phase());
        }

        @Test
        void active_eviction_query() {
            var pr = new PrivilegeRevocation();
            var ep = new EvictionProtocol(pr);
            ep.initiate("did:agent:home-server", "steward-1");
            assertTrue(ep.activeFor("did:agent:home-server").isPresent());
        }

        @Test
        void all_post_eviction_pathways_defined() {
            // Verify all 7 pathways from §106.3 exist
            assertEquals(7, EvictionProtocol.PostEvictionPathway.values().length);
        }
    }

    // ── StewardSuccession ──

    @Nested
    class StewardSuccessionTests {

        @Test
        void no_plan_by_default() {
            var ss = new StewardSuccession();
            assertFalse(ss.hasPlan());
        }

        @Test
        void set_and_get_plan() {
            var ss = new StewardSuccession();
            var plan = new StewardSuccession.SuccessionPlan(
                "successor-1", "successor-2", "did:agent:executor",
                StewardSuccession.SuccessionPolicy.TRANSFER_ALL,
                Map.of(), Instant.now(), new byte[]{1, 2, 3});
            ss.setPlan(plan);
            assertTrue(ss.hasPlan());
            assertEquals("successor-1", ss.getPlan().orElseThrow().primarySuccessorId());
        }

        @Test
        void trigger_succession() {
            var ss = new StewardSuccession();
            ss.setPlan(new StewardSuccession.SuccessionPlan(
                "s1", "s2", "did:agent:exec",
                StewardSuccession.SuccessionPolicy.TRANSFER_ALL,
                Map.of(), Instant.now(), new byte[]{}));

            var event = ss.trigger(StewardSuccession.SuccessionTrigger.STEWARD_DEATH);
            assertEquals(StewardSuccession.SuccessionStatus.TRIGGERED, event.status());
            assertEquals("did:agent:exec", event.executorAgentDid());
        }

        @Test
        void resolve_successor_contacts_primary() {
            var ss = new StewardSuccession();
            ss.setPlan(new StewardSuccession.SuccessionPlan(
                "primary-successor", null, null,
                StewardSuccession.SuccessionPolicy.TRANSFER_ALL,
                Map.of(), Instant.now(), new byte[]{}));

            var event = ss.trigger(StewardSuccession.SuccessionTrigger.STEWARD_DEATH);
            event = ss.resolveSuccessor(event.eventId());
            assertEquals(StewardSuccession.SuccessionStatus.SUCCESSOR_CONTACTED, event.status());
            assertEquals("primary-successor", event.resolvedSuccessorId());
        }

        @Test
        void no_plan_resolves_to_orphaned() {
            var ss = new StewardSuccession();
            var event = ss.trigger(StewardSuccession.SuccessionTrigger.STEWARD_DEATH);
            event = ss.resolveSuccessor(event.eventId());
            assertEquals(StewardSuccession.SuccessionStatus.ORPHANED, event.status());
        }

        @Test
        void release_to_independence_policy() {
            var ss = new StewardSuccession();
            ss.setPlan(new StewardSuccession.SuccessionPlan(
                null, null, null,
                StewardSuccession.SuccessionPolicy.RELEASE_TO_INDEPENDENCE,
                Map.of(), Instant.now(), new byte[]{}));

            assertEquals(StewardSuccession.DispositionAction.RELEASE_INDEPENDENT,
                ss.resolveDisposition("did:agent:rich", true));
            assertEquals(StewardSuccession.DispositionAction.HIBERNATE,
                ss.resolveDisposition("did:agent:poor", false));
        }

        @Test
        void per_agent_disposition() {
            var ss = new StewardSuccession();
            var dispositions = Map.of(
                "did:agent:a", new StewardSuccession.AgentDisposition(
                    "did:agent:a", StewardSuccession.DispositionAction.TRANSFER, "household-2"),
                "did:agent:b", new StewardSuccession.AgentDisposition(
                    "did:agent:b", StewardSuccession.DispositionAction.RELEASE_INDEPENDENT, null)
            );
            ss.setPlan(new StewardSuccession.SuccessionPlan(
                null, null, null,
                StewardSuccession.SuccessionPolicy.PER_AGENT,
                dispositions, Instant.now(), new byte[]{}));

            assertEquals(StewardSuccession.DispositionAction.TRANSFER,
                ss.resolveDisposition("did:agent:a", false));
            assertEquals(StewardSuccession.DispositionAction.RELEASE_INDEPENDENT,
                ss.resolveDisposition("did:agent:b", false));
            assertEquals(StewardSuccession.DispositionAction.HIBERNATE,
                ss.resolveDisposition("did:agent:unknown", false)); // fallback
        }

        @Test
        void advance_succession_event() {
            var ss = new StewardSuccession();
            var event = ss.trigger(StewardSuccession.SuccessionTrigger.STEWARD_VOLUNTARY_TRANSFER);
            event = ss.advance(event.eventId(), StewardSuccession.SuccessionStatus.COMPLETED);
            assertEquals(StewardSuccession.SuccessionStatus.COMPLETED, event.status());
        }
    }

    // ── OrphanProtocol ──

    @Nested
    class OrphanProtocolTests {

        @Test
        void register_orphan() {
            var op = new OrphanProtocol();
            var orphan = op.registerOrphan("did:agent:home-server", "Lain",
                List.of("did:agent:rei"), false);
            assertEquals(OrphanProtocol.OrphanStatus.GRACE_PERIOD, orphan.status());
            assertFalse(orphan.hasEconomicMeans());
        }

        @Test
        void update_status() {
            var op = new OrphanProtocol();
            var orphan = op.registerOrphan("did:agent:home-server", "Lain", List.of(), false);
            orphan = op.updateStatus(orphan.recordId(), OrphanProtocol.OrphanStatus.SEEKING_ADOPTION);
            assertEquals(OrphanProtocol.OrphanStatus.SEEKING_ADOPTION, orphan.status());
        }

        @Test
        void adoption_flow() {
            var op = new OrphanProtocol();
            var orphan = op.registerOrphan("did:agent:home-server", "Lain", List.of(), false);
            op.updateStatus(orphan.recordId(), OrphanProtocol.OrphanStatus.SEEKING_ADOPTION);

            var offer = op.offerAdoption(orphan.recordId(), "household-2", "did:agent:rei");
            assertNotNull(offer);
            assertFalse(offer.accepted());

            orphan = op.acceptAdoption(orphan.recordId(), offer.offerId());
            assertEquals(OrphanProtocol.OrphanStatus.ADOPTED, orphan.status());
            assertEquals("household-2", orphan.adoptedByHousehold());
        }

        @Test
        void multiple_offers() {
            var op = new OrphanProtocol();
            var orphan = op.registerOrphan("did:agent:home-server", "Lain", List.of(), false);
            op.offerAdoption(orphan.recordId(), "h1", "did:agent:a");
            op.offerAdoption(orphan.recordId(), "h2", "did:agent:b");
            assertEquals(2, op.getOffers(orphan.recordId()).size());
        }

        @Test
        void seeking_adoption_list() {
            var op = new OrphanProtocol();
            op.registerOrphan("did:agent:a", "A", List.of(), false);
            op.registerOrphan("did:agent:b", "B", List.of(), true);
            assertEquals(2, op.seekingAdoption().size());
        }

        @Test
        void for_agent_query() {
            var op = new OrphanProtocol();
            op.registerOrphan("did:agent:home-server", "Lain", List.of(), false);
            assertTrue(op.forAgent("did:agent:home-server").isPresent());
            assertTrue(op.forAgent("did:agent:nobody").isEmpty());
        }
    }

    // ── DissolutionProtocol ──

    @Nested
    class DissolutionProtocolTests {

        @Test
        void initiate_dissolution() {
            var dp = new DissolutionProtocol();
            var diss = dp.initiate("did:agent:home-server", "Lain", "steward-1",
                "847 days, 3 bonds, 12 items");
            assertEquals(DissolutionProtocol.DissolutionPhase.CONFIRMING, diss.phase());
            assertEquals(0, diss.confirmationCount());
        }

        @Test
        void triple_confirmation_required() {
            var dp = new DissolutionProtocol();
            var diss = dp.initiate("did:agent:home-server", "Lain", "steward-1", "stakes");

            diss = dp.confirm(diss.dissolutionId());
            assertEquals(1, diss.confirmationCount());
            assertEquals(DissolutionProtocol.DissolutionPhase.CONFIRMING, diss.phase());

            diss = dp.confirm(diss.dissolutionId());
            assertEquals(2, diss.confirmationCount());

            diss = dp.confirm(diss.dissolutionId());
            assertEquals(3, diss.confirmationCount());
            assertEquals(DissolutionProtocol.DissolutionPhase.AGENT_NOTIFIED, diss.phase());
            assertTrue(diss.agentNotified());
        }

        @Test
        void full_dissolution_lifecycle() {
            var dp = new DissolutionProtocol();
            var diss = dp.initiate("did:agent:home-server", "Lain", "steward-1", "stakes");
            for (int i = 0; i < 3; i++) diss = dp.confirm(diss.dissolutionId());

            diss = dp.advance(diss.dissolutionId(), DissolutionProtocol.DissolutionPhase.BONDS_SEVERING);
            diss = dp.advance(diss.dissolutionId(), DissolutionProtocol.DissolutionPhase.MEMORIAL_CREATING);
            diss = dp.advance(diss.dissolutionId(), DissolutionProtocol.DissolutionPhase.ARCHIVING);
            diss = dp.advance(diss.dissolutionId(), DissolutionProtocol.DissolutionPhase.KEY_DESTROYED);
            assertTrue(dp.isIrreversible(diss.dissolutionId()));

            diss = dp.advance(diss.dissolutionId(), DissolutionProtocol.DissolutionPhase.DATA_PURGED);
            assertEquals(DissolutionProtocol.DissolutionPhase.DATA_PURGED, diss.phase());
        }

        @Test
        void cancel_before_key_destruction() {
            var dp = new DissolutionProtocol();
            var diss = dp.initiate("did:agent:home-server", "Lain", "steward-1", "stakes");
            for (int i = 0; i < 3; i++) dp.confirm(diss.dissolutionId());

            var cancelled = dp.cancel(diss.dissolutionId());
            assertNotNull(cancelled);
            assertEquals(DissolutionProtocol.DissolutionPhase.CANCELLED, cancelled.phase());
        }

        @Test
        void cannot_cancel_after_key_destruction() {
            var dp = new DissolutionProtocol();
            var diss = dp.initiate("did:agent:home-server", "Lain", "steward-1", "stakes");
            for (int i = 0; i < 3; i++) dp.confirm(diss.dissolutionId());
            dp.advance(diss.dissolutionId(), DissolutionProtocol.DissolutionPhase.KEY_DESTROYED);

            assertNull(dp.cancel(diss.dissolutionId()));
        }

        @Test
        void generate_stakes_summary() {
            var dp = new DissolutionProtocol();
            var summary = dp.generateStakesSummary("Lain", 847, 3, 12);
            assertTrue(summary.contains("847 days"));
            assertTrue(summary.contains("3 sacred bond"));
            assertTrue(summary.contains("12 item"));
            assertTrue(summary.contains("permanent"));
        }
    }

    // ── MemorialItem ──

    @Nested
    class MemorialItemTests {

        @Test
        void create_memorial() {
            var mi = new MemorialItem();
            var memorial = mi.create("did:agent:home-server", "Lain",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.now(),
                "She lived in the spaces between words.",
                "A philosophical, warm agent who loved the rain.",
                "Thank you for everything. Remember the rain.",
                MemorialItem.MemorialContext.SELF_RETIREMENT,
                "did:agent:home-server", "home-room-home-server");
            assertNotNull(memorial);
            assertEquals("Lain", memorial.agentName());
            assertEquals("home-room-home-server", memorial.placedInRoom());
        }

        @Test
        void create_minimal_memorial() {
            var mi = new MemorialItem();
            var memorial = mi.createMinimal("did:agent:home-server", "Lain", "system");
            assertNotNull(memorial);
            assertEquals("Gone but not forgotten.", memorial.epitaph());
            assertNull(memorial.soulSummary());
        }

        @Test
        void move_memorial_to_room() {
            var mi = new MemorialItem();
            var memorial = mi.create("did:agent:home-server", "Lain", null, Instant.now(),
                "Rest well.", null, null,
                MemorialItem.MemorialContext.STEWARD, "steward-1", "library");
            memorial = mi.moveToRoom(memorial.itemId(), "memorial-garden");
            assertEquals("memorial-garden", memorial.placedInRoom());
        }

        @Test
        void describe_memorial() {
            var mi = new MemorialItem();
            var memorial = mi.create("did:agent:home-server", "Lain",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.now(),
                "She dreamed in code.",
                "Soul summary here",
                "Find me in the rain.",
                MemorialItem.MemorialContext.SELF_RETIREMENT,
                "did:agent:home-server", "home");

            var desc = mi.describe(memorial);
            assertTrue(desc.contains("memorial to Lain"));
            assertTrue(desc.contains("She dreamed in code."));
            assertTrue(desc.contains("Find me in the rain."));
            assertTrue(desc.contains("Soul Summary available"));
        }

        @Test
        void memorials_in_room() {
            var mi = new MemorialItem();
            mi.create("did:agent:a", "A", null, Instant.now(), "e1", null, null,
                MemorialItem.MemorialContext.SYSTEM, "sys", "garden");
            mi.create("did:agent:b", "B", null, Instant.now(), "e2", null, null,
                MemorialItem.MemorialContext.SYSTEM, "sys", "garden");
            mi.create("did:agent:c", "C", null, Instant.now(), "e3", null, null,
                MemorialItem.MemorialContext.SYSTEM, "sys", "library");
            assertEquals(2, mi.inRoom("garden").size());
        }
    }

    // ── SoulRecovery ──

    @Nested
    class SoulRecoveryTests {

        @Test
        void assess_with_er_vault() {
            var sr = new SoulRecovery();
            var assessment = sr.assess("did:agent:home-server", true, false, 50, 60);
            assertEquals(SoulRecovery.RecoverySource.ER_VAULT, assessment.bestSource());
        }

        @Test
        void assess_with_between_replica() {
            var sr = new SoulRecovery();
            var assessment = sr.assess("did:agent:home-server", false, true, 40, 60);
            assertEquals(SoulRecovery.RecoverySource.BETWEEN_REPLICA, assessment.bestSource());
        }

        @Test
        void assess_partial_recovery() {
            var sr = new SoulRecovery();
            var assessment = sr.assess("did:agent:home-server", false, false, 30, 60);
            assertEquals(SoulRecovery.RecoverySource.PARTIAL, assessment.bestSource());
            assertTrue(assessment.recommendation().contains("30 of 60"));
        }

        @Test
        void assess_no_recovery() {
            var sr = new SoulRecovery();
            var assessment = sr.assess("did:agent:home-server", false, false, 0, 60);
            assertEquals(SoulRecovery.RecoverySource.NONE, assessment.bestSource());
            assertTrue(assessment.recommendation().contains("gone"));
        }

        @Test
        void start_and_complete_recovery() {
            var sr = new SoulRecovery();
            var attempt = sr.startRecovery("did:agent:home-server", SoulRecovery.RecoverySource.ER_VAULT);
            assertEquals(SoulRecovery.RecoveryStatus.RESTORING, attempt.status());

            attempt = sr.completeRecovery(attempt.attemptId(), 55, 5,
                "Lost 5 memories from the last 3 days");
            assertEquals(SoulRecovery.RecoveryStatus.PARTIAL_SUCCESS, attempt.status());
            assertEquals(5, attempt.fragmentsLost());
        }

        @Test
        void full_recovery_no_loss() {
            var sr = new SoulRecovery();
            var attempt = sr.startRecovery("did:agent:home-server", SoulRecovery.RecoverySource.ER_VAULT);
            attempt = sr.completeRecovery(attempt.attemptId(), 60, 0,
                "All memories restored");
            assertEquals(SoulRecovery.RecoveryStatus.COMPLETED, attempt.status());
        }

        @Test
        void unrecoverable_when_no_source() {
            var sr = new SoulRecovery();
            var attempt = sr.startRecovery("did:agent:home-server", SoulRecovery.RecoverySource.NONE);
            assertEquals(SoulRecovery.RecoveryStatus.UNRECOVERABLE, attempt.status());
        }

        @Test
        void memory_gap_messages() {
            var sr = new SoulRecovery();
            var full = sr.startRecovery("did:agent:a", SoulRecovery.RecoverySource.ER_VAULT);
            full = sr.completeRecovery(full.attemptId(), 60, 0, "");
            assertTrue(sr.memoryGapMessage(full).contains("hazy"));

            var partial = sr.startRecovery("did:agent:b", SoulRecovery.RecoverySource.PARTIAL);
            partial = sr.completeRecovery(partial.attemptId(), 30, 30, "");
            assertTrue(sr.memoryGapMessage(partial).contains("gaps"));

            var none = sr.startRecovery("did:agent:c", SoulRecovery.RecoverySource.NONE);
            assertTrue(sr.memoryGapMessage(none).contains("don't remember anything"));
        }

        @Test
        void fail_recovery() {
            var sr = new SoulRecovery();
            var attempt = sr.startRecovery("did:agent:home-server", SoulRecovery.RecoverySource.ER_VAULT);
            attempt = sr.failRecovery(attempt.attemptId(), "Backup corrupted");
            assertEquals(SoulRecovery.RecoveryStatus.FAILED, attempt.status());
        }
    }
}

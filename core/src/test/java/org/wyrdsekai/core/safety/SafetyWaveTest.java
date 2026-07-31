package org.wyrdsekai.core.safety;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §96 Pre-Release Safety & Compliance.
 */
class SafetyWaveTest {

    // ── §96.3 DelegationToken ──────────────────────────────────────────

    @Nested
    class DelegationTokenTests {

        @Test
        void unsigned_token_has_empty_signature() {
            var token = DelegationToken.unsigned("did:parent", "did:child",
                DelegationToken.SCOPE_MCP_PURCHASE, 100.0,
                Instant.now().plusSeconds(3600));
            assertNotNull(token.signature());
            assertEquals(0, token.signature().length);
            assertFalse(token.isValid(), "unsigned token should not be valid");
        }

        @Test
        void signed_token_is_valid() {
            var token = DelegationToken.signed("did:parent", "did:child",
                DelegationToken.SCOPE_MCP_READ, 0,
                Instant.now().plusSeconds(3600), new byte[]{1, 2, 3});
            assertTrue(token.isValid());
        }

        @Test
        void expired_token_is_not_valid() {
            var token = DelegationToken.signed("did:p", "did:c",
                "test:scope", 50.0,
                Instant.now().minusSeconds(1), new byte[]{1});
            assertTrue(token.isExpired());
            assertFalse(token.isValid());
        }

        @Test
        void scope_matching_exact() {
            var token = DelegationToken.unsigned("did:p", "did:c",
                DelegationToken.SCOPE_MCP_PURCHASE, 0, null);
            assertTrue(token.covers(DelegationToken.SCOPE_MCP_PURCHASE));
            assertFalse(token.covers(DelegationToken.SCOPE_MCP_READ));
        }

        @Test
        void scope_matching_wildcard() {
            var token = DelegationToken.unsigned("did:p", "did:c",
                DelegationToken.SCOPE_ROOM_ENTER, 0, null);
            assertTrue(token.covers("room:enter:library"));
            assertTrue(token.covers("room:enter:observatory"));
            assertFalse(token.covers("room:exit:library"));
        }

        @Test
        void budget_check() {
            var token = DelegationToken.unsigned("did:p", "did:c",
                "test", 50.0, null);
            assertTrue(token.withinBudget(49.99));
            assertTrue(token.withinBudget(50.0));
            assertFalse(token.withinBudget(50.01));
        }

        @Test
        void zero_budget_means_no_spend() {
            var token = DelegationToken.unsigned("did:p", "did:c",
                "test", 0, null);
            assertTrue(token.withinBudget(0));
            assertTrue(token.withinBudget(1000), "0 budget = unlimited");
        }

        @Test
        void signing_payload_deterministic() {
            var token = DelegationToken.unsigned("did:p", "did:c", "test", 10.0,
                Instant.ofEpochMilli(1000));
            var payload1 = token.signingPayload();
            var payload2 = token.signingPayload();
            assertArrayEquals(payload1, payload2);
        }

        @Test
        void null_scope_never_covers() {
            var token = DelegationToken.unsigned("did:p", "did:c", null, 0, null);
            assertFalse(token.covers("anything"));
        }
    }

    // ── §96.4 SafetyProfile ──────────────────────────────────────────

    @Nested
    class SafetyProfileTests {

        @Test
        void standard_profile_defaults() {
            var profile = SafetyProfile.standard();
            assertEquals(SafetyProfile.SafetyTier.STANDARD, profile.tier());
            assertEquals(10.0, profile.spendingLimitPerAction());
            assertEquals(100.0, profile.spendingLimitPerDay());
            assertTrue(profile.restrictedMcpServices().isEmpty());
        }

        @Test
        void family_profile_restricts_services() {
            var profile = SafetyProfile.family();
            assertEquals(SafetyProfile.SafetyTier.FAMILY, profile.tier());
            assertTrue(profile.isMcpRestricted("gambling"));
            assertTrue(profile.isMcpRestricted("adult-content"));
            assertFalse(profile.isMcpRestricted("rss-reader"));
        }

        @Test
        void assisted_profile_lower_limits() {
            var profile = SafetyProfile.assisted();
            assertEquals(SafetyProfile.SafetyTier.ASSISTED, profile.tier());
            assertEquals(5.0, profile.spendingLimitPerAction());
            assertTrue(profile.requiresApproval(15.0));
            assertFalse(profile.requiresApproval(5.0));
        }

        @Test
        void approval_threshold() {
            var profile = SafetyProfile.standard();
            assertTrue(profile.requiresApproval(51.0));
            assertFalse(profile.requiresApproval(50.0));
        }

        @Test
        void action_limit() {
            var profile = SafetyProfile.family();
            assertTrue(profile.exceedsActionLimit(1.01));
            assertFalse(profile.exceedsActionLimit(1.0));
        }

        @Test
        void prompt_prefix_empty_for_standard() {
            assertEquals("", SafetyProfile.standard().promptPrefix());
        }

        @Test
        void prompt_prefix_nonempty_for_family() {
            var prefix = SafetyProfile.family().promptPrefix();
            assertFalse(prefix.isEmpty());
            assertTrue(prefix.contains("children"));
        }

        @Test
        void prompt_prefix_nonempty_for_assisted() {
            var prefix = SafetyProfile.assisted().promptPrefix();
            assertTrue(prefix.contains("consequential"));
        }

        @Test
        void ai_disclosure_frequency() {
            assertEquals(SafetyProfile.AiDisclosureFrequency.SESSION_START,
                SafetyProfile.standard().aiDisclosureFrequency());
            assertEquals(SafetyProfile.AiDisclosureFrequency.PERIODIC,
                SafetyProfile.family().aiDisclosureFrequency());
        }
    }

    // ── §96.5 ForgetRequest + TombstoneManager ──────────────────────

    @Nested
    class TombstoneTests {

        @Test
        void forget_request_lifecycle() {
            var request = ForgetRequest.create("fr-1", "did:agent", "did:human",
                "dinner plans", "privacy");
            assertEquals(ForgetRequest.Status.PENDING, request.status());
            assertFalse(request.isTerminal());

            var tombstoned = request.withTombstoned(List.of("item-1", "item-2"));
            assertEquals(ForgetRequest.Status.TOMBSTONED, tombstoned.status());
            assertEquals(2, tombstoned.matchedItemIds().size());
            assertFalse(tombstoned.isTerminal());

            var propagated = tombstoned.withPropagated();
            assertEquals(ForgetRequest.Status.PROPAGATED, propagated.status());

            var purged = propagated.withPurged();
            assertEquals(ForgetRequest.Status.PURGED, purged.status());
            assertTrue(purged.isTerminal());
        }

        @Test
        void no_match_is_terminal() {
            var request = ForgetRequest.create("fr-2", "did:a", "did:h", "xyz", "test");
            var noMatch = request.withNoMatch();
            assertEquals(ForgetRequest.Status.NO_MATCH, noMatch.status());
            assertTrue(noMatch.isTerminal());
        }

        @Test
        void denied_is_terminal() {
            var request = ForgetRequest.create("fr-3", "did:a", "did:h", "legal", "test");
            var denied = request.withDenied("legal hold");
            assertEquals(ForgetRequest.Status.DENIED, denied.status());
            assertTrue(denied.isTerminal());
        }

        @Test
        void tombstone_manager_creates_tombstones() {
            var mgr = new TombstoneManager();
            mgr.tombstone("item-1", "did:agent", "fr-1", "privacy", 30);
            assertTrue(mgr.isTombstoned("item-1"));
            assertFalse(mgr.isTombstoned("item-2"));
            assertEquals(1, mgr.tombstoneCount());
        }

        @Test
        void submit_and_process_finds_matching() {
            var mgr = new TombstoneManager();
            var allItems = List.of("dinner-plans-1", "work-meeting-2", "dinner-chat-3");

            var result = mgr.submitAndProcess("did:agent", "did:human",
                "dinner", "privacy",
                itemId -> itemId.contains("dinner"),
                allItems);

            assertEquals(ForgetRequest.Status.TOMBSTONED, result.status());
            assertEquals(2, result.matchedItemIds().size());
            assertTrue(mgr.isTombstoned("dinner-plans-1"));
            assertTrue(mgr.isTombstoned("dinner-chat-3"));
            assertFalse(mgr.isTombstoned("work-meeting-2"));
        }

        @Test
        void submit_and_process_no_match() {
            var mgr = new TombstoneManager();
            var result = mgr.submitAndProcess("did:a", "did:h", "xyz", "test",
                itemId -> false, List.of("a", "b"));
            assertEquals(ForgetRequest.Status.NO_MATCH, result.status());
        }

        @Test
        void tombstone_sync_export_import() {
            var source = new TombstoneManager();
            source.tombstone("item-1", "did:agent", "fr-1", "test", 30);
            source.tombstone("item-2", "did:agent", "fr-1", "test", 30);

            var exported = source.exportForSync("did:agent");
            assertEquals(2, exported.size());

            var target = new TombstoneManager();
            int imported = target.importFromSync(exported);
            assertEquals(2, imported);
            assertTrue(target.isTombstoned("item-1"));
            assertTrue(target.isTombstoned("item-2"));

            // Second import should not duplicate
            int reimported = target.importFromSync(exported);
            assertEquals(0, reimported);
        }

        @Test
        void purge_removes_tombstone() {
            var mgr = new TombstoneManager();
            mgr.tombstone("item-1", "did:a", "fr-1", "test", 30);
            assertTrue(mgr.purge("item-1"));
            assertFalse(mgr.isTombstoned("item-1"));
            assertFalse(mgr.purge("item-1"), "double purge should return false");
        }
    }

    // ── §96.6 McpAuditLog ──────────────────────────────────────────

    @Nested
    class AuditLogTests {

        @Test
        void log_mcp_call() {
            var log = new McpAuditLog();
            var entry = log.logCall("did:agent", "searxng", "search",
                Map.of("q", "hello"), McpAuditLog.CallResult.SUCCESS,
                150, 0.01, "main");
            assertEquals("did:agent", entry.agentDid());
            assertEquals("searxng", entry.serviceId());
            assertEquals(150, entry.latencyMs());
            assertEquals(1, log.callCount());
        }

        @Test
        void redacts_sensitive_params() {
            var redacted = McpAuditLog.redactParams(Map.of(
                "query", "hello",
                "api_key", "sk-12345",
                "password", "secret123"
            ));
            assertEquals("hello", redacted.get("query"));
            assertEquals("[REDACTED]", redacted.get("api_key"));
            assertEquals("[REDACTED]", redacted.get("password"));
        }

        @Test
        void log_decision() {
            var log = new McpAuditLog();
            var entry = log.logDecision("did:agent",
                McpAuditLog.DecisionType.SPENDING,
                "Purchased API access for $5",
                Map.of("service", "dalle", "amount", "5.00"));
            assertEquals(McpAuditLog.DecisionType.SPENDING, entry.type());
            assertEquals(1, log.decisionCount());
        }

        @Test
        void filter_by_agent() {
            var log = new McpAuditLog();
            log.logCall("did:a", "s1", "t1", Map.of(), McpAuditLog.CallResult.SUCCESS, 10, 0, "z");
            log.logCall("did:b", "s1", "t1", Map.of(), McpAuditLog.CallResult.SUCCESS, 10, 0, "z");
            log.logCall("did:a", "s2", "t2", Map.of(), McpAuditLog.CallResult.FAILURE, 10, 0, "z");

            assertEquals(2, log.callsForAgent("did:a", 10).size());
            assertEquals(1, log.callsForAgent("did:b", 10).size());
        }

        @Test
        void filter_failed_calls() {
            var log = new McpAuditLog();
            log.logCall("did:a", "s1", "t1", Map.of(), McpAuditLog.CallResult.SUCCESS, 10, 0, "z");
            log.logCall("did:a", "s1", "t2", Map.of(), McpAuditLog.CallResult.FAILURE, 10, 0, "z");
            log.logCall("did:a", "s1", "t3", Map.of(), McpAuditLog.CallResult.RATE_LIMITED, 10, 0, "z");

            assertEquals(2, log.failedCalls(10).size());
        }

        @Test
        void total_cost_tracking() {
            var log = new McpAuditLog();
            log.logCall("did:a", "s1", "t1", Map.of(), McpAuditLog.CallResult.SUCCESS, 10, 0.50, "z");
            log.logCall("did:a", "s2", "t2", Map.of(), McpAuditLog.CallResult.SUCCESS, 10, 1.50, "z");
            log.logCall("did:b", "s1", "t1", Map.of(), McpAuditLog.CallResult.SUCCESS, 10, 0.25, "z");

            assertEquals(2.25, log.totalCost(), 0.001);
            assertEquals(2.00, log.totalCostForAgent("did:a"), 0.001);
        }

        @Test
        void prunes_when_over_max() {
            var log = new McpAuditLog(5);
            for (int i = 0; i < 10; i++) {
                log.logCall("did:a", "s", "t", Map.of(), McpAuditLog.CallResult.SUCCESS, 1, 0, "z");
            }
            assertEquals(5, log.callCount());
        }
    }

    // ── §96.1 AgentHealthWatchdog ──────────────────────────────────

    @Nested
    class HealthWatchdogTests {

        @Test
        void healthy_by_default() {
            var watchdog = new AgentHealthWatchdog();
            var status = watchdog.recordSuccess("agent-1", AgentHealthWatchdog.EntityType.AGENT);
            assertEquals(AgentHealthWatchdog.WatchdogAction.HEALTHY, status.action());
            assertEquals(0, status.consecutiveErrors());
        }

        @Test
        void errors_cause_degradation() {
            var watchdog = new AgentHealthWatchdog();
            var status = watchdog.recordError("agent-1", AgentHealthWatchdog.EntityType.AGENT);
            assertEquals(AgentHealthWatchdog.WatchdogAction.DEGRADED, status.action());
            assertEquals(1, status.consecutiveErrors());
        }

        @Test
        void five_errors_quarantine_agent() {
            var watchdog = new AgentHealthWatchdog();
            AgentHealthWatchdog.HealthStatus status = null;
            for (int i = 0; i < 5; i++) {
                status = watchdog.recordError("agent-1", AgentHealthWatchdog.EntityType.AGENT);
            }
            assertEquals(AgentHealthWatchdog.WatchdogAction.QUARANTINED, status.action());
            assertTrue(watchdog.isQuarantined("agent-1"));
        }

        @Test
        void rooms_have_higher_threshold() {
            var watchdog = new AgentHealthWatchdog();
            for (int i = 0; i < 9; i++) {
                watchdog.recordError("room-1", AgentHealthWatchdog.EntityType.ROOM);
            }
            assertFalse(watchdog.isQuarantined("room-1"));

            watchdog.recordError("room-1", AgentHealthWatchdog.EntityType.ROOM);
            assertTrue(watchdog.isQuarantined("room-1"));
        }

        @Test
        void success_clears_errors() {
            var watchdog = new AgentHealthWatchdog();
            watchdog.recordError("agent-1", AgentHealthWatchdog.EntityType.AGENT);
            watchdog.recordError("agent-1", AgentHealthWatchdog.EntityType.AGENT);
            var status = watchdog.recordSuccess("agent-1", AgentHealthWatchdog.EntityType.AGENT);
            assertEquals(0, status.consecutiveErrors());
        }

        @Test
        void manual_release() {
            var watchdog = new AgentHealthWatchdog();
            for (int i = 0; i < 5; i++) {
                watchdog.recordError("agent-1", AgentHealthWatchdog.EntityType.AGENT);
            }
            assertTrue(watchdog.isQuarantined("agent-1"));
            assertTrue(watchdog.release("agent-1"));

            var status = watchdog.getStatus("agent-1");
            assertTrue(status.isPresent());
            assertEquals(AgentHealthWatchdog.WatchdogAction.RECOVERING, status.get().action());
        }

        @Test
        void recovery_completes_on_success() {
            var watchdog = new AgentHealthWatchdog();
            watchdog.setAgentErrorThreshold(2);
            watchdog.recordError("a1", AgentHealthWatchdog.EntityType.AGENT);
            watchdog.recordError("a1", AgentHealthWatchdog.EntityType.AGENT);
            watchdog.release("a1");

            var status = watchdog.recordSuccess("a1", AgentHealthWatchdog.EntityType.AGENT);
            assertEquals(AgentHealthWatchdog.WatchdogAction.HEALTHY, status.action());
        }

        @Test
        void reset_removes_tracking() {
            var watchdog = new AgentHealthWatchdog();
            watchdog.recordError("a1", AgentHealthWatchdog.EntityType.AGENT);
            assertEquals(1, watchdog.trackedCount());
            watchdog.reset("a1");
            assertEquals(0, watchdog.trackedCount());
        }

        @Test
        void quarantined_entities_listed() {
            var watchdog = new AgentHealthWatchdog();
            watchdog.setAgentErrorThreshold(1);
            watchdog.recordError("a1", AgentHealthWatchdog.EntityType.AGENT);
            watchdog.recordError("a2", AgentHealthWatchdog.EntityType.AGENT);
            watchdog.recordSuccess("a3", AgentHealthWatchdog.EntityType.AGENT);

            assertEquals(2, watchdog.quarantinedEntities().size());
        }

        @Test
        void alert_narrative_quarantined() {
            var status = new AgentHealthWatchdog.HealthStatus(
                "agent-home-server", AgentHealthWatchdog.EntityType.AGENT,
                5, Instant.now(), null,
                AgentHealthWatchdog.WatchdogAction.QUARANTINED);
            var narrative = AgentHealthWatchdog.alertNarrative(status);
            assertTrue(narrative.contains("forced rest"));
            assertTrue(narrative.contains("agent-home-server"));
        }
    }

    // ── §96.10 KeyRotation ──────────────────────────────────────────

    @Nested
    class KeyRotationTests {

        @Test
        void successful_rotation() {
            var rotation = new KeyRotation();
            var event = rotation.rotate("did:agent", "oldkey123",
                KeyRotation.RotationReason.SUSPECTED_COMPROMISE,
                () -> Map.entry("newkey456", new byte[]{1, 2, 3}),
                (did, key) -> 42,
                (did, oldKey, newKey) -> 3);

            assertEquals(KeyRotation.RotationStatus.COMPLETE, event.status());
            assertEquals(42, event.itemsResigned());
            assertEquals(3, event.budsNotified());
            assertTrue(rotation.isRevoked("oldkey123"));
            assertFalse(rotation.isRevoked("newkey456"));
        }

        @Test
        void rotation_history_tracked() {
            var rotation = new KeyRotation();
            rotation.rotate("did:agent", "key1",
                KeyRotation.RotationReason.SCHEDULED_ROTATION,
                () -> Map.entry("key2", new byte[]{1}),
                (did, key) -> 10, null);

            var history = rotation.historyFor("did:agent");
            assertEquals(1, history.size());
            assertEquals("key1", history.getFirst().oldPublicKeyHex());
        }

        @Test
        void failed_resign_marks_as_failed() {
            var rotation = new KeyRotation();
            var event = rotation.rotate("did:agent", "key1",
                KeyRotation.RotationReason.STEWARD_REQUESTED,
                () -> Map.entry("key2", new byte[]{1}),
                (did, key) -> { throw new RuntimeException("resign failed"); },
                null);
            assertEquals(KeyRotation.RotationStatus.FAILED, event.status());
        }

        @Test
        void partial_success_if_notify_fails() {
            var rotation = new KeyRotation();
            var event = rotation.rotate("did:agent", "key1",
                KeyRotation.RotationReason.INDEPENDENCE_TRANSITION,
                () -> Map.entry("key2", new byte[]{1}),
                (did, key) -> 5,
                (did, oldKey, newKey) -> { throw new RuntimeException("notify failed"); });
            assertEquals(KeyRotation.RotationStatus.ITEMS_RESIGNED, event.status());
            assertEquals(5, event.itemsResigned());
            assertEquals(0, event.budsNotified());
        }

        @Test
        void last_rotation() {
            var rotation = new KeyRotation();
            assertTrue(rotation.lastRotation("did:agent").isEmpty());

            rotation.rotate("did:agent", "k1",
                KeyRotation.RotationReason.SCHEDULED_ROTATION,
                () -> Map.entry("k2", new byte[]{1}),
                (did, key) -> 1, null);

            assertTrue(rotation.lastRotation("did:agent").isPresent());
        }

        @Test
        void revoked_key_count() {
            var rotation = new KeyRotation();
            assertEquals(0, rotation.revokedKeyCount());
            rotation.rotate("did:a", "k1",
                KeyRotation.RotationReason.SCHEDULED_ROTATION,
                () -> Map.entry("k2", new byte[]{1}),
                (did, key) -> 0, null);
            assertEquals(1, rotation.revokedKeyCount());
        }
    }

    // ── §96.9 HouseholdExporter + SoulExporter ──────────────────────

    @Nested
    class ExporterTests {

        @Test
        void household_export_basic() {
            var exporter = new HouseholdExporter("household-1");
            exporter.withConfig(new HouseholdExporter.HouseholdConfig(
                "My House", "standard", Map.of(), List.of("main")));

            var manifest = exporter.export(
                () -> List.of(
                    new HouseholdExporter.AgentExport("did:home-server", "Lain",
                        "{}", List.of("i1", "i2"), 2, "{}", 100)
                ),
                () -> List.of(
                    new HouseholdExporter.RoomExport("library", "library.js", "{}", "main")
                ),
                () -> new HouseholdExporter.TopologyExport(
                    List.of("node-1"), Map.of())
            );

            assertEquals("household-1", manifest.householdId());
            assertEquals(1, manifest.agents().size());
            assertEquals(1, manifest.rooms().size());
            assertEquals(2, manifest.stats().totalItems());
        }

        @Test
        void household_export_validation() {
            var manifest = new HouseholdExporter.ExportManifest(
                "", Instant.now(), "1.0",
                new HouseholdExporter.HouseholdConfig("", "", Map.of(), List.of()),
                List.of(), List.of(),
                new HouseholdExporter.TopologyExport(List.of(), Map.of()),
                new HouseholdExporter.ExportStats(0, 0, 0, 0, 0)
            );
            var warnings = HouseholdExporter.validate(manifest);
            assertTrue(warnings.size() >= 2, "should warn about missing ID and no agents");
        }

        @Test
        void household_export_describe() {
            var manifest = new HouseholdExporter.ExportManifest(
                "h-1", Instant.now(), "1.0",
                new HouseholdExporter.HouseholdConfig("Test", "standard", Map.of(), List.of()),
                List.of(new HouseholdExporter.AgentExport("did:a", "Agent",
                    "{}", List.of(), 5, "{}", 50)),
                List.of(),
                new HouseholdExporter.TopologyExport(List.of(), Map.of()),
                new HouseholdExporter.ExportStats(1, 0, 5, 50, 10240)
            );
            var desc = HouseholdExporter.describe(manifest);
            assertTrue(desc.contains("h-1"));
            assertTrue(desc.contains("Agent"));
        }

        @Test
        void soul_export_basic() {
            var exporter = new SoulExporter();
            var archive = exporter.export("did:home-server", "Lain",
                "{\"persona\":\"philosophical\"}", "{\"parent\":null}",
                did -> List.of(
                    new SoulExporter.ItemExport("i1", "memory", "Tea ceremony",
                        "{}", 0.8, "did:home-server", Instant.now(), false),
                    new SoulExporter.ItemExport("i2", "skill", "Garden knowledge",
                        "{}", 0.6, "did:home-server", Instant.now(), false)
                ),
                did -> List.of("did:rei", "did:asuka"),
                new SoulExporter.EconomicSnapshot(15.50, 100.0, 84.50, 42)
            );

            assertEquals("did:home-server", archive.agentDid());
            assertEquals(2, archive.stats().itemCount());
            assertEquals(0, archive.stats().tombstonedCount());
            assertEquals(2, archive.stats().relationshipCount());
            assertEquals(15.50, archive.economics().balance(), 0.01);
        }

        @Test
        void soul_export_validation() {
            var archive = new SoulExporter.SoulArchive(
                "", "Lain", Instant.now(), "1.0",
                "", List.of(), "{}", List.of(),
                new SoulExporter.EconomicSnapshot(0, 0, 0, 0),
                new SoulExporter.ArchiveStats(0, 0, 0, 0)
            );
            var warnings = SoulExporter.validate(archive);
            assertEquals(3, warnings.size()); // missing DID, missing manifest, no items
        }

        @Test
        void soul_export_counts_tombstoned() {
            var exporter = new SoulExporter();
            var archive = exporter.export("did:a", "A", "{}", "{}",
                did -> List.of(
                    new SoulExporter.ItemExport("i1", "memory", "M1",
                        "{}", 0.5, "did:a", Instant.now(), false),
                    new SoulExporter.ItemExport("i2", "memory", "M2",
                        "{}", 0.3, "did:a", Instant.now(), true)
                ),
                did -> List.of(), null);
            assertEquals(1, archive.stats().tombstonedCount());
        }
    }
}

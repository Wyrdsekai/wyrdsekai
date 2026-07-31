package org.wyrdsekai.core.observability;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.observability.HealthChecker.CheckType;
import org.wyrdsekai.core.observability.HealthChecker.HealthLevel;
import org.wyrdsekai.core.observability.SafeMode.SafeModeLevel;
import org.wyrdsekai.core.observability.RecoveryPlaybook.ProcedureType;
import org.wyrdsekai.core.observability.RecoveryPlaybook.SessionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §105 — Observability, the ER, and Agent Health.
 * Health checking, safe mode, recovery playbooks, backup vault,
 * remote ER, referrals, bridge, metrics, tracing, log redaction.
 */
class ObservabilityWaveTest {

    // ── HealthChecker ──

    @Nested
    class HealthCheckerTests {

        @Test
        void record_and_retrieve_check() {
            var hc = new HealthChecker();
            var check = hc.record("home-server", CheckType.AGENT_VITALITY, HealthLevel.HEALTHY,
                "All tanks stable", null);
            assertNotNull(check);
            assertEquals("home-server", check.component());
            assertEquals(HealthLevel.HEALTHY, check.level());
        }

        @Test
        void latest_for_component() {
            var hc = new HealthChecker();
            hc.record("home-server", CheckType.AGENT_VITALITY, HealthLevel.HEALTHY, "OK", null);
            hc.record("home-server", CheckType.AGENT_MEMORY, HealthLevel.DEGRADED, "Fragments old", null);
            var latest = hc.latestFor("home-server");
            assertTrue(latest.isPresent());
        }

        @Test
        void critical_issues_returned() {
            var hc = new HealthChecker();
            hc.record("home-server", CheckType.AGENT_VITALITY, HealthLevel.CRITICAL, "Energy critical", null);
            hc.record("home-server", CheckType.AGENT_MEMORY, HealthLevel.HEALTHY, "OK", null);
            assertEquals(1, hc.criticalIssues().size());
        }

        @Test
        void overall_health_worst_wins() {
            var hc = new HealthChecker();
            assertEquals(HealthLevel.UNKNOWN, hc.overallHealth());
            hc.record("a", CheckType.AGENT_VITALITY, HealthLevel.HEALTHY, "OK", null);
            assertEquals(HealthLevel.HEALTHY, hc.overallHealth());
            hc.record("b", CheckType.MCP_SERVICE, HealthLevel.DEGRADED, "Slow", null);
            assertEquals(HealthLevel.DEGRADED, hc.overallHealth());
            hc.record("c", CheckType.BETWEEN_NODE, HealthLevel.CRITICAL, "Down", null);
            assertEquals(HealthLevel.CRITICAL, hc.overallHealth());
        }

        @Test
        void unhealthy_returns_non_healthy() {
            var hc = new HealthChecker();
            hc.record("a", CheckType.AGENT_VITALITY, HealthLevel.HEALTHY, "OK", null);
            hc.record("b", CheckType.MCP_SERVICE, HealthLevel.DEGRADED, "Slow", null);
            hc.record("c", CheckType.BETWEEN_NODE, HealthLevel.CRITICAL, "Down", null);
            assertEquals(2, hc.unhealthy().size());
        }

        @Test
        void describe_produces_summary() {
            var hc = new HealthChecker();
            hc.record("home-server", CheckType.AGENT_VITALITY, HealthLevel.HEALTHY, "OK", null);
            var desc = hc.describe();
            assertTrue(desc.contains("System Health"));
            assertTrue(desc.contains("home-server"));
        }

        @Test
        void metadata_preserved() {
            var hc = new HealthChecker();
            var check = hc.record("home-server", CheckType.AGENT_VITALITY, HealthLevel.HEALTHY,
                "OK", Map.of("energy", "0.85", "focus", "0.92"));
            assertEquals("0.85", check.metadata().get("energy"));
        }
    }

    // ── SafeMode ──

    @Nested
    class SafeModeTests {

        @Test
        void initially_inactive() {
            var sm = new SafeMode("did:agent:home-server");
            assertFalse(sm.isActive());
            assertTrue(sm.level().isEmpty());
        }

        @Test
        void activate_sets_level() {
            var sm = new SafeMode("did:agent:home-server");
            sm.activate("Personality drift detected", SafeModeLevel.DIAGNOSTIC);
            assertTrue(sm.isActive());
            assertEquals(SafeModeLevel.DIAGNOSTIC, sm.level().orElse(null));
        }

        @Test
        void escalate_moves_toward_minimal() {
            var sm = new SafeMode("did:agent:home-server");
            sm.activate("test", SafeModeLevel.RECOVERY);
            assertEquals(SafeModeLevel.RECOVERY, sm.level().orElse(null));

            sm.escalate();
            assertEquals(SafeModeLevel.DIAGNOSTIC, sm.level().orElse(null));

            sm.escalate();
            assertEquals(SafeModeLevel.MINIMAL, sm.level().orElse(null));

            sm.escalate(); // already at max
            assertEquals(SafeModeLevel.MINIMAL, sm.level().orElse(null));
        }

        @Test
        void deescalate_moves_toward_recovery() {
            var sm = new SafeMode("did:agent:home-server");
            sm.activate("test", SafeModeLevel.MINIMAL);

            sm.deescalate();
            assertEquals(SafeModeLevel.DIAGNOSTIC, sm.level().orElse(null));

            sm.deescalate();
            assertEquals(SafeModeLevel.RECOVERY, sm.level().orElse(null));

            sm.deescalate(); // already at min
            assertEquals(SafeModeLevel.RECOVERY, sm.level().orElse(null));
        }

        @Test
        void deactivate_clears_safe_mode() {
            var sm = new SafeMode("did:agent:home-server");
            sm.activate("test", SafeModeLevel.DIAGNOSTIC);
            sm.deactivate();
            assertFalse(sm.isActive());
        }

        @Test
        void prompt_modifier_varies_by_level() {
            var sm = new SafeMode("did:agent:home-server");
            assertEquals("", sm.promptModifier());

            sm.activate("test", SafeModeLevel.MINIMAL);
            assertTrue(sm.promptModifier().contains("MINIMAL"));

            sm.deescalate();
            assertTrue(sm.promptModifier().contains("DIAGNOSTIC"));

            sm.deescalate();
            assertTrue(sm.promptModifier().contains("RECOVERY"));
        }

        @Test
        void escalate_while_inactive_does_nothing() {
            var sm = new SafeMode("did:agent:home-server");
            var status = sm.escalate();
            assertFalse(status.active());
        }
    }

    // ── RecoveryPlaybook ──

    @Nested
    class RecoveryPlaybookTests {

        @Test
        void all_procedure_types_initialized() {
            var pb = new RecoveryPlaybook();
            for (var type : ProcedureType.values()) {
                if (type == ProcedureType.IDENTITY_RECONSTRUCTION) continue; // not in default
                assertTrue(pb.getProcedure(type).isPresent(), "Missing: " + type);
            }
        }

        @Test
        void start_recovery_creates_session() {
            var pb = new RecoveryPlaybook();
            var session = pb.startRecovery("did:agent:home-server", ProcedureType.FRAGMENT_QUARANTINE);
            assertNotNull(session);
            assertEquals(SessionStatus.IN_PROGRESS, session.status());
            assertEquals(0, session.currentStep());
            assertTrue(session.totalSteps() > 0);
        }

        @Test
        void advance_step_progresses() {
            var pb = new RecoveryPlaybook();
            var session = pb.startRecovery("did:agent:home-server", ProcedureType.EMERGENCY_FORGE);
            assertEquals(0, session.currentStep());

            session = pb.advanceStep(session.sessionId());
            assertEquals(1, session.currentStep());
            assertEquals(SessionStatus.IN_PROGRESS, session.status());
        }

        @Test
        void advance_to_end_completes() {
            var pb = new RecoveryPlaybook();
            var session = pb.startRecovery("did:agent:home-server", ProcedureType.EMERGENCY_FORGE);
            for (int i = 0; i < session.totalSteps(); i++) {
                session = pb.advanceStep(session.sessionId());
            }
            assertEquals(SessionStatus.COMPLETED, session.status());
        }

        @Test
        void abort_session() {
            var pb = new RecoveryPlaybook();
            var session = pb.startRecovery("did:agent:home-server", ProcedureType.FRAGMENT_QUARANTINE);
            var aborted = pb.abort(session.sessionId());
            assertEquals(SessionStatus.ABORTED, aborted.status());
        }

        @Test
        void active_sessions_for_agent() {
            var pb = new RecoveryPlaybook();
            pb.startRecovery("did:agent:home-server", ProcedureType.FRAGMENT_QUARANTINE);
            pb.startRecovery("did:agent:home-server", ProcedureType.EMERGENCY_FORGE);
            pb.startRecovery("did:agent:other", ProcedureType.BACKUP_RESTORE);

            assertEquals(2, pb.activeSessions("did:agent:home-server").size());
            assertEquals(1, pb.activeSessions("did:agent:other").size());
        }

        @Test
        void fragment_quarantine_has_consent_steps() {
            var pb = new RecoveryPlaybook();
            var proc = pb.getProcedure(ProcedureType.FRAGMENT_QUARANTINE).orElseThrow();
            // Steps 3 and 4 require consent
            assertTrue(proc.steps().stream().anyMatch(s -> s.requiresConsent()));
            assertTrue(proc.steps().stream().anyMatch(s -> s.automated()));
        }

        @Test
        void identity_realignment_has_6_steps() {
            var pb = new RecoveryPlaybook();
            var proc = pb.getProcedure(ProcedureType.IDENTITY_REALIGNMENT).orElseThrow();
            assertEquals(6, proc.steps().size());
        }
    }

    // ── ErBackupVault ──

    @Nested
    class ErBackupVaultTests {

        @Test
        void store_and_retrieve_snapshot() {
            var vault = new ErBackupVault();
            var snap = vault.store("did:agent:home-server", ErBackupVault.SnapshotType.KNOWN_GOOD_MANIFEST,
                new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, "hash123");
            assertNotNull(snap);

            var latest = vault.latest("did:agent:home-server", ErBackupVault.SnapshotType.KNOWN_GOOD_MANIFEST);
            assertTrue(latest.isPresent());
            assertEquals(snap.snapshotId(), latest.get().snapshotId());
        }

        @Test
        void rotation_drops_oldest() {
            var vault = new ErBackupVault(3, 3, Duration.ofHours(24));
            for (int i = 0; i < 5; i++) {
                vault.store("did:agent:home-server", ErBackupVault.SnapshotType.KNOWN_GOOD_MANIFEST,
                    new byte[]{(byte) i}, new byte[]{}, "hash" + i);
            }
            var all = vault.allSnapshots("did:agent:home-server", ErBackupVault.SnapshotType.KNOWN_GOOD_MANIFEST);
            assertEquals(3, all.size());
        }

        @Test
        void behavioral_baseline_is_immutable() {
            var vault = new ErBackupVault();
            var first = vault.store("did:agent:home-server", ErBackupVault.SnapshotType.BEHAVIORAL_BASELINE,
                new byte[]{1}, new byte[]{}, "birth");
            var second = vault.store("did:agent:home-server", ErBackupVault.SnapshotType.BEHAVIORAL_BASELINE,
                new byte[]{2}, new byte[]{}, "attempt2");

            // Should return the first one
            assertEquals(first.snapshotId(), second.snapshotId());
            var baseline = vault.baseline("did:agent:home-server");
            assertTrue(baseline.isPresent());
            assertEquals(first.snapshotId(), baseline.get().snapshotId());
        }

        @Test
        void dual_key_request_cooldown() {
            var vault = new ErBackupVault(5, 3, Duration.ofHours(24));
            var request = vault.requestDualKeyAccess("did:agent:home-server", "steward-1");
            assertEquals(ErBackupVault.DualKeyStatus.PENDING_COOLDOWN, request.status());
            assertTrue(request.availableAt().isAfter(Instant.now()));

            // Check — should still be pending
            var checked = vault.checkDualKeyRequest(request.requestId());
            assertEquals(ErBackupVault.DualKeyStatus.PENDING_COOLDOWN, checked.status());
        }

        @Test
        void cancel_dual_key_request() {
            var vault = new ErBackupVault();
            var request = vault.requestDualKeyAccess("did:agent:home-server", "steward-1");
            var cancelled = vault.cancelDualKeyAccess(request.requestId());
            assertEquals(ErBackupVault.DualKeyStatus.CANCELLED, cancelled.status());
        }

        @Test
        void snapshot_count_across_types() {
            var vault = new ErBackupVault();
            vault.store("did:agent:home-server", ErBackupVault.SnapshotType.KNOWN_GOOD_MANIFEST,
                new byte[]{1}, new byte[]{}, "h1");
            vault.store("did:agent:home-server", ErBackupVault.SnapshotType.PRE_FORGE,
                new byte[]{2}, new byte[]{}, "h2");
            vault.store("did:agent:home-server", ErBackupVault.SnapshotType.BEHAVIORAL_BASELINE,
                new byte[]{3}, new byte[]{}, "h3");
            assertEquals(3, vault.snapshotCount("did:agent:home-server"));
        }

        @Test
        void freshness_check() {
            var vault = new ErBackupVault();
            vault.store("did:agent:home-server", ErBackupVault.SnapshotType.FRAGMENT_CHECKPOINT,
                new byte[]{1}, new byte[]{}, "h");
            assertTrue(vault.isFresh("did:agent:home-server",
                ErBackupVault.SnapshotType.FRAGMENT_CHECKPOINT, Duration.ofHours(1)));
            assertFalse(vault.isFresh("did:agent:nobody",
                ErBackupVault.SnapshotType.FRAGMENT_CHECKPOINT, Duration.ofHours(1)));
        }
    }

    // ── RemoteErProtocol ──

    @Nested
    class RemoteErProtocolTests {

        @Test
        void receive_and_diagnose() {
            var proto = new RemoteErProtocol();
            var req = proto.receiveRequest("did:bud:phone", "household-1",
                RemoteErProtocol.RequestSource.HUMAN_EMERGENCY);
            assertEquals(RemoteErProtocol.RemoteRequestStatus.RECEIVED, req.status());

            var diag = new RemoteErProtocol.BudDiagnostic("did:bud:phone",
                Map.of("energy", 0.1, "focus", 0.1, "confidence", 0.1),
                List.of("abc123"), 0.4, Instant.now());
            var diagnosed = proto.diagnose(req.requestId(), diag);
            assertTrue(diagnosed.diagnosticSummary().contains("CRITICAL"));
        }

        @Test
        void recommend_recall_for_critical() {
            var proto = new RemoteErProtocol();
            var req = proto.receiveRequest("did:bud:phone", "household-1",
                RemoteErProtocol.RequestSource.SELF_REPORT);

            var diag = new RemoteErProtocol.BudDiagnostic("did:bud:phone",
                Map.of("energy", 0.05, "focus", 0.05, "confidence", 0.05),
                List.of(), 0.3, Instant.now());
            proto.diagnose(req.requestId(), diag);

            var action = proto.recommendAction(req.requestId());
            assertEquals(RemoteErProtocol.ResponseAction.RECALL, action);
        }

        @Test
        void recommend_patch_for_drift() {
            var proto = new RemoteErProtocol();
            var req = proto.receiveRequest("did:bud:phone", "household-1",
                RemoteErProtocol.RequestSource.HEADLINE_MONITOR);

            var diag = new RemoteErProtocol.BudDiagnostic("did:bud:phone",
                Map.of("energy", 0.6, "focus", 0.7),
                List.of(), 0.55, Instant.now());
            proto.diagnose(req.requestId(), diag);

            var action = proto.recommendAction(req.requestId());
            assertEquals(RemoteErProtocol.ResponseAction.REMOTE_PATCH, action);
        }

        @Test
        void respond_and_resolve() {
            var proto = new RemoteErProtocol();
            var req = proto.receiveRequest("did:bud:phone", "household-1",
                RemoteErProtocol.RequestSource.HUMAN_EMERGENCY);

            var response = proto.respond(req.requestId(),
                RemoteErProtocol.ResponseAction.SAFE_MODE, Map.of("level", "MINIMAL"));
            assertNotNull(response);

            var resolved = proto.resolve(req.requestId());
            assertEquals(RemoteErProtocol.RemoteRequestStatus.RESOLVED, resolved.status());
            assertTrue(proto.activeRequests().isEmpty());
        }

        @Test
        void active_requests_excludes_resolved() {
            var proto = new RemoteErProtocol();
            proto.receiveRequest("did:bud:1", "h1", RemoteErProtocol.RequestSource.SELF_REPORT);
            var req2 = proto.receiveRequest("did:bud:2", "h1", RemoteErProtocol.RequestSource.SELF_REPORT);
            proto.resolve(req2.requestId());
            assertEquals(1, proto.activeRequests().size());
        }
    }

    // ── ErReferral ──

    @Nested
    class ErReferralTests {

        @Test
        void register_and_list_zones() {
            var referral = new ErReferral();
            referral.registerZone(new ErReferral.ErServiceZone(
                "zone-hospital", "Agent Hospital", true,
                List.of("deep-diagnostic", "forge-repair"), 0.95,
                Map.of("diagnostic", 0.01)));
            referral.registerZone(new ErReferral.ErServiceZone(
                "zone-clinic", "Local Clinic", false,
                List.of("basic-diagnostic"), 0.7, Map.of()));

            var zones = referral.availableZones();
            assertEquals(2, zones.size());
            assertEquals("zone-hospital", zones.get(0).zoneId()); // higher reputation first
        }

        @Test
        void initiate_and_advance_referral() {
            var referral = new ErReferral();
            var ref = referral.initiate("did:agent:home-server", "zone-home", "zone-hospital",
                ErReferral.ReferralType.REMOTE_DIAGNOSIS, "Persistent drift");
            assertEquals(ErReferral.ReferralStatus.INITIATED, ref.status());

            ref = referral.advance(ref.referralId(), ErReferral.ReferralStatus.STATE_TRANSFERRING);
            assertEquals(ErReferral.ReferralStatus.STATE_TRANSFERRING, ref.status());

            ref = referral.advance(ref.referralId(), ErReferral.ReferralStatus.DIAGNOSING);
            assertEquals(ErReferral.ReferralStatus.DIAGNOSING, ref.status());
        }

        @Test
        void attach_recovery_plan() {
            var referral = new ErReferral();
            var ref = referral.initiate("did:agent:home-server", "zone-home", "zone-hospital",
                ErReferral.ReferralType.REMOTE_DIAGNOSIS, "Forge failure");
            ref = referral.attachRecoveryPlan(ref.referralId(),
                "Quarantine fragments 7-12, restore manifest v3, re-run Forge");
            assertEquals(ErReferral.ReferralStatus.PLAN_RECEIVED, ref.status());
            assertNotNull(ref.recoveryPlan());
        }

        @Test
        void in_patient_transfer_flow() {
            var referral = new ErReferral();
            var ref = referral.initiate("did:agent:home-server", "zone-home", "zone-hospital",
                ErReferral.ReferralType.IN_PATIENT_TRANSFER, "Cannot recover locally");
            ref = referral.advance(ref.referralId(), ErReferral.ReferralStatus.ADMITTED);
            assertEquals(ErReferral.ReferralStatus.ADMITTED, ref.status());

            ref = referral.advance(ref.referralId(), ErReferral.ReferralStatus.RECOVERING);
            ref = referral.advance(ref.referralId(), ErReferral.ReferralStatus.DISCHARGED);
            assertEquals(ErReferral.ReferralStatus.DISCHARGED, ref.status());
        }

        @Test
        void active_referrals_excludes_discharged() {
            var referral = new ErReferral();
            referral.initiate("did:agent:home-server", "z1", "z2",
                ErReferral.ReferralType.REMOTE_DIAGNOSIS, "test");
            var ref2 = referral.initiate("did:agent:home-server", "z1", "z3",
                ErReferral.ReferralType.REMOTE_DIAGNOSIS, "test2");
            referral.advance(ref2.referralId(), ErReferral.ReferralStatus.DISCHARGED);

            assertEquals(1, referral.activeReferrals("did:agent:home-server").size());
        }
    }

    // ── ErBridge (CodePlane integration) ──

    @Nested
    class ErBridgeTests {

        @Test
        void initially_unlinked() {
            var bridge = new ErBridge();
            assertFalse(bridge.isLinked());
        }

        @Test
        void link_and_unlink() {
            var bridge = new ErBridge();
            bridge.link("codeplane.local", List.of("crash-detection", "gpu-monitoring"));
            assertTrue(bridge.isLinked());
            assertEquals("codeplane.local", bridge.status().codeplaneFqdn());
            assertEquals(2, bridge.status().availableCapabilities().size());

            bridge.unlink();
            assertFalse(bridge.isLinked());
        }

        @Test
        void receive_alert_when_linked() {
            var bridge = new ErBridge();
            bridge.link("cp.local", List.of());
            var alert = bridge.receiveAlert(ErBridge.AlertSeverity.CRITICAL,
                "ollama", "GPU OOM — model unloaded");
            assertNotNull(alert);
            assertEquals(ErBridge.AlertSeverity.CRITICAL, alert.severity());
        }

        @Test
        void receive_alert_when_unlinked_returns_null() {
            var bridge = new ErBridge();
            assertNull(bridge.receiveAlert(ErBridge.AlertSeverity.CRITICAL, "ollama", "down"));
        }

        @Test
        void translate_critical_to_vitality_effect() {
            var bridge = new ErBridge();
            bridge.link("cp.local", List.of());
            var alert = bridge.receiveAlert(ErBridge.AlertSeverity.CRITICAL,
                "ollama", "crash");
            var effect = bridge.translateToVitalityEffect(alert);
            assertTrue(effect.get("energy") < 0);
            assertTrue(effect.get("error_pressure") > 0);
        }

        @Test
        void translate_fatal_has_stronger_effect() {
            var bridge = new ErBridge();
            bridge.link("cp.local", List.of());
            var fatal = bridge.receiveAlert(ErBridge.AlertSeverity.FATAL, "gpu", "driver crash");
            var critical = bridge.receiveAlert(ErBridge.AlertSeverity.CRITICAL, "gpu", "oom");
            var fatalEffect = bridge.translateToVitalityEffect(fatal);
            var criticalEffect = bridge.translateToVitalityEffect(critical);
            assertTrue(Math.abs(fatalEffect.get("energy")) > Math.abs(criticalEffect.get("energy")));
        }

        @Test
        void enrich_vitality_when_linked() {
            var bridge = new ErBridge();
            bridge.link("cp.local", List.of());
            var enriched = bridge.enrich("energy", 0.5, -0.1, -0.02);
            assertEquals(0.5, enriched.value());
            assertEquals(-0.1, enriched.velocity());
            assertNotNull(enriched.predictedCriticalAt());
            assertTrue(enriched.predictedCriticalAt() > 0);
        }

        @Test
        void enrich_without_link_has_no_derivatives() {
            var bridge = new ErBridge();
            var enriched = bridge.enrich("energy", 0.5, -0.1, -0.02);
            assertEquals(0.0, enriched.velocity());
            assertNull(enriched.predictedCriticalAt());
        }

        @Test
        void acknowledge_alert() {
            var bridge = new ErBridge();
            bridge.link("cp.local", List.of());
            var alert = bridge.receiveAlert(ErBridge.AlertSeverity.CRITICAL, "ollama", "crash");
            assertEquals(1, bridge.criticalAlerts().size());

            bridge.acknowledge(alert.alertId());
            assertEquals(0, bridge.criticalAlerts().size());
        }
    }

    // ── MetricsRegistry ──

    @Nested
    class MetricsRegistryTests {

        @Test
        void counter_increment() {
            var mr = new MetricsRegistry();
            mr.increment("requests", Map.of("agent", "home-server"));
            mr.increment("requests", Map.of("agent", "home-server"));
            assertEquals(2, mr.counterValue("requests", Map.of("agent", "home-server")));
        }

        @Test
        void gauge_set_and_get() {
            var mr = new MetricsRegistry();
            mr.gauge("energy", Map.of("agent", "home-server"), 0.75);
            assertEquals(0.75, mr.gaugeValue("energy", Map.of("agent", "home-server")));
        }

        @Test
        void histogram_summary() {
            var mr = new MetricsRegistry();
            var labels = Map.of("agent", "home-server");
            for (double v : new double[]{0.1, 0.2, 0.3, 0.5, 1.0, 2.0, 5.0}) {
                mr.observe("latency", labels, v);
            }
            var summary = mr.histogramSummary("latency", labels);
            assertTrue(summary.isPresent());
            assertEquals(7, summary.get().count());
            assertEquals(0.1, summary.get().min());
            assertEquals(5.0, summary.get().max());
        }

        @Test
        void record_inference() {
            var mr = new MetricsRegistry();
            mr.recordInference("did:agent:home-server", "qwen2.5:7b", Duration.ofMillis(500), 150);
            assertEquals(150, mr.counterValue("wyrd_inference_tokens_total",
                Map.of("agent", "did:agent:home-server", "model", "qwen2.5:7b")));
        }

        @Test
        void record_mcp_call_with_error() {
            var mr = new MetricsRegistry();
            mr.recordMcpCall("weather", "get_forecast", true);
            mr.recordMcpCall("weather", "get_forecast", false);
            assertEquals(2, mr.counterValue("wyrd_mcp_calls_total",
                Map.of("service", "weather", "tool", "get_forecast")));
            assertEquals(1, mr.counterValue("wyrd_mcp_errors_total",
                Map.of("service", "weather", "tool", "get_forecast")));
        }

        @Test
        void vitality_gauge_update() {
            var mr = new MetricsRegistry();
            mr.updateVitality("did:agent:home-server", "energy", 0.45);
            assertEquals(0.45, mr.gaugeValue("wyrd_vitality_level",
                Map.of("agent", "did:agent:home-server", "tank", "energy")));
        }

        @Test
        void export_open_metrics() {
            var mr = new MetricsRegistry();
            mr.increment("requests", Map.of());
            mr.gauge("energy", Map.of(), 0.5);
            var export = mr.exportOpenMetrics();
            assertFalse(export.isEmpty());
        }
    }

    // ── TracingFilter ──

    @Nested
    class TracingFilterTests {

        @Test
        void start_trace_creates_root_span() {
            var tf = new TracingFilter();
            var ctx = tf.startTrace("user-message", Map.of("user", "steward"));
            assertNotNull(ctx.traceId());
            assertEquals(1, tf.traceCount());

            var root = tf.rootSpan(ctx.traceId());
            assertTrue(root.isPresent());
            assertNull(root.get().parentSpanId());
        }

        @Test
        void child_spans() {
            var tf = new TracingFilter();
            var ctx = tf.startTrace("user-message", Map.of());
            var child = tf.startSpan(ctx.traceId(), ctx.currentSpanId(),
                "memory-retrieval", Map.of());
            assertNotNull(child);
            assertEquals(ctx.currentSpanId(), child.parentSpanId());

            var spans = tf.traceSpans(ctx.traceId());
            assertEquals(2, spans.size());
        }

        @Test
        void end_span_records_duration() {
            var tf = new TracingFilter();
            var ctx = tf.startTrace("test-op", Map.of());
            var ended = tf.endSpan(ctx.currentSpanId(), TracingFilter.SpanStatus.OK);
            assertNotNull(ended.endTime());
            assertEquals(TracingFilter.SpanStatus.OK, ended.status());
        }

        @Test
        void has_errors_detects_error_spans() {
            var tf = new TracingFilter();
            var ctx = tf.startTrace("test", Map.of());
            var child = tf.startSpan(ctx.traceId(), ctx.currentSpanId(), "failing-op", Map.of());
            tf.endSpan(child.spanId(), TracingFilter.SpanStatus.ERROR);
            assertTrue(tf.hasErrors(ctx.traceId()));
        }

        @Test
        void trace_duration() {
            var tf = new TracingFilter();
            var ctx = tf.startTrace("test", Map.of());
            tf.endSpan(ctx.currentSpanId(), TracingFilter.SpanStatus.OK);
            var duration = tf.traceDuration(ctx.traceId());
            assertTrue(duration.isPresent());
        }

        @Test
        void describe_trace() {
            var tf = new TracingFilter();
            var ctx = tf.startTrace("user-message", Map.of());
            tf.startSpan(ctx.traceId(), ctx.currentSpanId(), "inference-call", Map.of());
            tf.endSpan(ctx.currentSpanId(), TracingFilter.SpanStatus.OK);
            var desc = tf.describeTrace(ctx.traceId());
            assertTrue(desc.contains("user-message"));
            assertTrue(desc.contains("inference-call"));
        }
    }

    // ── LangfuseObserver ──

    @Nested
    class LangfuseObserverTests {

        @Test
        void disabled_by_default() {
            var lf = new LangfuseObserver();
            assertFalse(lf.isEnabled());
            assertNull(lf.observe("home-server", "qwen", "h", 100, 50, Duration.ofMillis(500), 0.001));
        }

        @Test
        void observe_when_enabled() {
            var lf = new LangfuseObserver(true, false);
            var obs = lf.observe("home-server", "qwen", "prompt-hash", 100, 50,
                Duration.ofMillis(500), 0.001);
            assertNotNull(obs);
            assertEquals(100, obs.promptTokens());
            assertEquals(50, obs.completionTokens());
            assertEquals(0.15, obs.estimatedCost(), 0.001);
        }

        @Test
        void redaction_mode_hides_prompt_hash() {
            var lf = new LangfuseObserver(true, true);
            var obs = lf.observe("home-server", "qwen", "sensitive-data", 100, 50,
                Duration.ofMillis(500), 0.001);
            assertEquals("REDACTED", obs.promptHash());
            assertTrue(obs.redacted());
        }

        @Test
        void cost_summary() {
            var lf = new LangfuseObserver(true, false);
            lf.observe("home-server", "qwen", "h1", 100, 50, Duration.ofMillis(500), 0.001);
            lf.observe("home-server", "qwen", "h2", 200, 100, Duration.ofMillis(800), 0.001);
            var summary = lf.costSummary("home-server");
            assertTrue(summary.isPresent());
            assertEquals(2, summary.get().totalCalls());
            assertEquals(300, summary.get().totalPromptTokens());
        }

        @Test
        void quality_scoring() {
            var lf = new LangfuseObserver(true, false);
            var fast = lf.observe("home-server", "qwen", "h", 100, 50, Duration.ofMillis(500), 0.001);
            var slow = lf.observe("home-server", "qwen", "h", 100, 50, Duration.ofSeconds(15), 0.001);
            assertTrue(lf.score(fast).latencyScore() > lf.score(slow).latencyScore());
        }
    }

    // ── RedactingLayout ──

    @Nested
    class RedactingLayoutTests {

        @Test
        void redacts_credentials() {
            var rl = new RedactingLayout();
            var result = rl.redact("password: my_secret_123");
            assertTrue(result.redactedText().contains("[REDACTED]"));
            assertTrue(result.categoriesFound().contains(RedactingLayout.SensitiveCategory.CREDENTIAL));
        }

        @Test
        void redacts_bearer_token() {
            var rl = new RedactingLayout();
            var result = rl.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.test");
            assertTrue(result.redactedText().contains("[REDACTED]"));
        }

        @Test
        void redacts_email() {
            var rl = new RedactingLayout();
            var result = rl.redact("Contact user@example.com for details");
            assertTrue(result.redactedText().contains("[REDACTED]"));
            assertTrue(result.categoriesFound().contains(RedactingLayout.SensitiveCategory.PII));
        }

        @Test
        void redacts_block_markers() {
            var rl = new RedactingLayout();
            var result = rl.redact("user_message: Hello, how are you doing today?");
            assertTrue(result.redactedText().contains("[REDACTED]"));
            assertTrue(result.categoriesFound().contains(RedactingLayout.SensitiveCategory.HUMAN_MESSAGE));
        }

        @Test
        void redacts_soul_fragment_marker() {
            var rl = new RedactingLayout();
            var result = rl.redact("soul_fragment: deeply personal memory content");
            assertTrue(result.redactedText().contains("[REDACTED]"));
            assertTrue(result.categoriesFound().contains(RedactingLayout.SensitiveCategory.SOUL_FRAGMENT));
        }

        @Test
        void safe_metadata_always_clean() {
            var rl = new RedactingLayout();
            var meta = rl.safeMetadata("did:agent:home-server", "inference", 500, 100);
            assertFalse(rl.containsSensitive(meta.toString()));
        }

        @Test
        void no_redaction_for_safe_text() {
            var rl = new RedactingLayout();
            var result = rl.redact("Agent home-server processed request in 500ms");
            assertEquals(0, result.redactionCount());
        }

        @Test
        void redact_map() {
            var rl = new RedactingLayout();
            var map = Map.of("user", "user_message: secret stuff", "agent", "home-server");
            var redacted = rl.redactMap(map);
            assertTrue(redacted.get("user").contains("[REDACTED]"));
            assertEquals("home-server", redacted.get("agent"));
        }

        @Test
        void selective_categories() {
            var rl = new RedactingLayout(Set.of(RedactingLayout.SensitiveCategory.CREDENTIAL));
            // PII should NOT be redacted since only CREDENTIAL is enabled
            var result = rl.redact("user@example.com password=secret");
            assertTrue(result.categoriesFound().contains(RedactingLayout.SensitiveCategory.CREDENTIAL));
            // Email passes through because PII not enabled
            assertFalse(result.categoriesFound().contains(RedactingLayout.SensitiveCategory.PII));
        }
    }
}

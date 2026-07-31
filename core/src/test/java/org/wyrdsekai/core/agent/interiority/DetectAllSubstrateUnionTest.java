package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.soul.ResilienceSession;
import org.wyrdsekai.core.soul.ResilienceTruthMonitor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a-Steward: verify that ChronicleService.detectAll's resilience
 * overload folds SustainedSubstratePatternDetector.Findings into the
 * unified list returned to the steward furnishing.
 */
class DetectAllSubstrateUnionTest {

    private ResilienceSession sessionWith(
            ResilienceTruthMonitor.Result.Classification cls, int times) {
        var session = new ResilienceSession(24);
        for (int i = 0; i < times; i++) {
            session.injectLogEntryForTests(new ResilienceTruthMonitor.Result(
                cls, 0.9, "test", 0, 0, 0));
        }
        return session;
    }

    private ChronicleService freshService(Path tmp) throws Exception {
        var logFile = tmp.resolve("activity.jsonl");
        Files.write(logFile, List.<String>of());
        return new ChronicleService(new TickLogReader(logFile));
    }

    @Test
    void backwards_compat_overload_returns_doom_and_psych_only(@TempDir Path tmp) throws Exception {
        var service = freshService(tmp);
        var findings = service.detectAll("did:agent:x", "x", "steward", Set.of());
        // No ticks, no testimony → empty findings.
        assertThat(findings).isEmpty();
    }

    @Test
    void resilience_overload_with_null_session_degrades_cleanly(@TempDir Path tmp) throws Exception {
        var service = freshService(tmp);
        var findings = service.detectAll("did:agent:x", "x", "steward",
            Set.of(), null);
        assertThat(findings).isEmpty();
    }

    @Test
    void sustained_suppression_surfaces_in_detectAll(@TempDir Path tmp) throws Exception {
        var service = freshService(tmp);
        var session = sessionWith(
            ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED, 3);
        var findings = service.detectAll("did:agent:x", "x", "steward",
            Set.of(), session);
        assertThat(findings)
            .as("substrate findings must ride the same return path as DoomLoop findings")
            .anyMatch(f -> "sustained_suppression".equals(f.key())
                && f.severity() == DoomLoopDetector.Severity.CRITICAL);
    }

    @Test
    void sustained_integrating_surfaces_as_INFO(@TempDir Path tmp) throws Exception {
        var service = freshService(tmp);
        var session = sessionWith(
            ResilienceTruthMonitor.Result.Classification.INTEGRATING, 3);
        var findings = service.detectAll("did:agent:x", "x", "steward",
            Set.of(), session);
        assertThat(findings)
            .anyMatch(f -> "sustained_integrating".equals(f.key())
                && f.severity() == DoomLoopDetector.Severity.INFO);
    }

    @Test
    void healthy_session_emits_no_substrate_findings(@TempDir Path tmp) throws Exception {
        var service = freshService(tmp);
        var session = sessionWith(
            ResilienceTruthMonitor.Result.Classification.HEALTHY_ENDURANCE, 5);
        var findings = service.detectAll("did:agent:x", "x", "steward",
            Set.of(), session);
        assertThat(findings)
            .as("healthy classifications produce no findings")
            .noneMatch(f -> f.key().startsWith("sustained_")
                || "high_suppression_ratio".equals(f.key()));
    }
}

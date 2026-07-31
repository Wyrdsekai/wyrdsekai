package org.wyrdsekai.core.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationServiceTest {

    private ModerationService service;

    @BeforeEach void setUp() {
        service = new ModerationService();
    }

    @Test void fileReport_creates_open_report() {
        var report = service.fileReport("alice", "bob", "spam", "nexus");
        assertThat(report.status()).isEqualTo(ModerationService.ReportStatus.OPEN);
        assertThat(service.reportCount()).isEqualTo(1);
    }

    @Test void resolveReport_updates_status() {
        var report = service.fileReport("alice", "bob", "spam", "nexus");
        var resolved = service.resolveReport(report.id(), ModerationService.ReportStatus.RESOLVED, "warned");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().status()).isEqualTo(ModerationService.ReportStatus.RESOLVED);
    }

    @Test void reportsFor_filters_by_target() {
        service.fileReport("alice", "bob", "spam", "nexus");
        service.fileReport("carol", "dave", "abuse", "nexus");

        assertThat(service.reportsFor("bob")).hasSize(1);
    }

    @Test void escalate_applies_graduated_sanctions() {
        // NONE → WARNING
        var s1 = service.escalate("bob", "first offense");
        assertThat(s1.level()).isEqualTo(ModerationService.SanctionLevel.WARNING);

        // WARNING → PROBATION
        var s2 = service.escalate("bob", "second offense");
        assertThat(s2.level()).isEqualTo(ModerationService.SanctionLevel.PROBATION);

        // PROBATION → SUSPENSION
        var s3 = service.escalate("bob", "third offense");
        assertThat(s3.level()).isEqualTo(ModerationService.SanctionLevel.SUSPENSION);

        // SUSPENSION → BAN
        var s4 = service.escalate("bob", "final offense");
        assertThat(s4.level()).isEqualTo(ModerationService.SanctionLevel.BAN);
    }

    @Test void isBanned_checks_ban_status() {
        assertThat(service.isBanned("bob")).isFalse();
        service.applySanction("bob", ModerationService.SanctionLevel.BAN, "bad", null);
        assertThat(service.isBanned("bob")).isTrue();
    }

    @Test void liftSanction_removes_sanction() {
        service.applySanction("bob", ModerationService.SanctionLevel.SUSPENSION, "temp", null);
        assertThat(service.isRestricted("bob")).isTrue();

        service.liftSanction("bob");
        assertThat(service.isRestricted("bob")).isFalse();
    }
}

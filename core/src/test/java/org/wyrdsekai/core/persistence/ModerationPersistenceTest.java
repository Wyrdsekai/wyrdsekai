package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.ModerationService.*;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationPersistenceTest {

    private ModerationPersistence persistence;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        var jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new ModerationPersistence(jdbcUrl);
    }

    @Test void save_and_load_report() {
        var report = new Report("r-1", "player-1", "player-2", "spam",
            "nexus", ReportStatus.OPEN, Instant.now(), null);
        persistence.saveReport(report);

        var loaded = persistence.loadReport("r-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().reporterEntity()).isEqualTo("player-1");
        assertThat(loaded.get().targetEntity()).isEqualTo("player-2");
        assertThat(loaded.get().status()).isEqualTo(ReportStatus.OPEN);
    }

    @Test void report_not_found() {
        assertThat(persistence.loadReport("ghost")).isEmpty();
    }

    @Test void update_report_status() {
        var report = new Report("r-1", "player-1", "player-2", "spam",
            "nexus", ReportStatus.OPEN, Instant.now(), null);
        persistence.saveReport(report);

        var resolved = new Report("r-1", "player-1", "player-2", "spam",
            "nexus", ReportStatus.RESOLVED, report.createdAt(), "warned user");
        persistence.saveReport(resolved);

        var loaded = persistence.loadReport("r-1");
        assertThat(loaded.get().status()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(loaded.get().resolution()).isEqualTo("warned user");
    }

    @Test void reportsFor_filters() {
        persistence.saveReport(new Report("r-1", "p1", "target-1", "r1", "room", ReportStatus.OPEN, Instant.now(), null));
        persistence.saveReport(new Report("r-2", "p2", "target-2", "r2", "room", ReportStatus.OPEN, Instant.now(), null));
        persistence.saveReport(new Report("r-3", "p3", "target-1", "r3", "room", ReportStatus.OPEN, Instant.now(), null));

        assertThat(persistence.reportsFor("target-1")).hasSize(2);
        assertThat(persistence.reportsFor("target-2")).hasSize(1);
    }

    @Test void openReports() {
        persistence.saveReport(new Report("r-1", "p1", "t1", "r", "room", ReportStatus.OPEN, Instant.now(), null));
        persistence.saveReport(new Report("r-2", "p2", "t2", "r", "room", ReportStatus.RESOLVED, Instant.now(), "done"));
        persistence.saveReport(new Report("r-3", "p3", "t3", "r", "room", ReportStatus.INVESTIGATING, Instant.now(), null));

        var open = persistence.openReports();
        assertThat(open).hasSize(2);
    }

    @Test void reportCount() {
        assertThat(persistence.reportCount()).isEqualTo(0);
        persistence.saveReport(new Report("r-1", "p1", "t1", "r", "room", ReportStatus.OPEN, Instant.now(), null));
        assertThat(persistence.reportCount()).isEqualTo(1);
    }

    @Test void save_and_load_sanction() {
        var sanction = new Sanction("player-1", SanctionLevel.WARNING, "spam",
            Instant.now(), null);
        persistence.saveSanction(sanction);

        var loaded = persistence.loadSanction("player-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().level()).isEqualTo(SanctionLevel.WARNING);
        assertThat(loaded.get().reason()).isEqualTo("spam");
    }

    @Test void sanction_upsert() {
        persistence.saveSanction(new Sanction("p1", SanctionLevel.WARNING, "first",
            Instant.now(), null));
        persistence.saveSanction(new Sanction("p1", SanctionLevel.BAN, "escalated",
            Instant.now(), null));

        var loaded = persistence.loadSanction("p1");
        assertThat(loaded.get().level()).isEqualTo(SanctionLevel.BAN);
    }

    @Test void delete_sanction() {
        persistence.saveSanction(new Sanction("p1", SanctionLevel.WARNING, "test",
            Instant.now(), null));
        persistence.deleteSanction("p1");
        assertThat(persistence.loadSanction("p1")).isEmpty();
    }

    @Test void activeSanctionCount() {
        assertThat(persistence.activeSanctionCount()).isEqualTo(0);
        persistence.saveSanction(new Sanction("p1", SanctionLevel.WARNING, "test", Instant.now(), null));
        persistence.saveSanction(new Sanction("p2", SanctionLevel.NONE, "clean", Instant.now(), null));
        assertThat(persistence.activeSanctionCount()).isEqualTo(1);
    }
}

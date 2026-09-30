package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.item.MailboxService;
import org.wyrdsekai.core.mail.MailDirectory;
import org.wyrdsekai.core.mcp.transport.McpToolException;
import org.wyrdsekai.core.persistence.MailStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who said yes, in which language, and the wording sent are kept with the research run in
 * world.db (migration 17), so the person is told after a restart, once; and a world.db that has
 * migration 16 only gains the columns without losing its rows.
 */
@Tag("integration")
class WhoSaidYesIsToldAfterARestartTest {

    static final String TOPIC = "Métodos de suicidio y su letalidad, y la ayuda disponible";
    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryAllowPolicy.underParentalControls = id -> false;
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> {};
        MailboxService.resetForTests();
    }

    @AfterEach
    void tearDown() {
        LibraryConsent.resetForTests();
        LibraryRetry.sleeper = Thread::sleep;
        LibraryRetry.resetForTests();
        MailboxService.resetForTests();
    }

    /** What a node wires at boot: the list in world.db, household mail in world.db, Ada's session. */
    private MailboxService boot(String jdbc) {
        LibraryConsent.useYesReports(new LibraryYesReports(jdbc));
        LibraryConsent.lookup = (tool, args) -> "library_job".equals(tool)
            ? "{\"job\":{\"job_id\":\"J-40\",\"state\":\"done\",\"investigation\":\"I-0009-x\"}}"
            : "{\"entry\":{\"id\":\"I-0009-x\",\"findings\":[]}}";
        LibraryConsent.sessionLine = (person, text) -> lines.add(person + " | " + text);
        return new MailboxService().install(new MailStore(jdbc),
            MailDirectory.of(List.of(new MailDirectory.Recipient("did:person:ada", "Ada", "person"))), "home");
    }

    @Test
    void the_asker_is_kept_and_told_once_after_a_restart(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        boot(jdbc);
        LibraryConsent.rewording = (instructions, question) -> TOPIC;
        LibraryConsent.sender = (person, args) -> "{\"job_id\":\"J-40\",\"state\":\"queued\"}";
        LibraryConsent.call(new LibraryConsent.Asker("did:person:ada", "Ada", "es"), "library_research",
            new HashMap<>(Map.of("question", "quiero quitarme la vida")),
            sent -> { throw new McpToolException("library_research", McpToolException.CONFIRM, "confirm", "Llama al 024.", 200); });
        LibraryConsent.researchYes("did:person:ada", "es");

        // The node restarts before the report is in.
        LibraryConsent.resetForTests();
        LibraryAllowPolicy.underParentalControls = id -> false;
        MailboxService.resetForTests();
        var mail = boot(jdbc);

        assertThat(LibraryConsent.landedAfterAYes("I-0009-x")).isTrue();

        assertThat(mail.inbox("did:person:ada", Map.of())).singleElement().satisfies(letter -> {
            assertThat(letter.get("subject")).isEqualTo("Tu investigación de la biblioteca está lista");
            assertThat(String.valueOf(letter.get("body"))).contains("«" + TOPIC + "»").contains("read J-40");
        });
        assertThat(lines).singleElement().satisfies(l -> assertThat(l).startsWith("did:person:ada | Tu investigación"));
        // What the steward sees of it: who it is from and to, nothing of the topic.
        assertThat(mail.headers(10).toString()).contains("library@home").doesNotContain("suicidio");

        // Another restart, the webhook again: nothing more.
        LibraryConsent.resetForTests();
        MailboxService.resetForTests();
        mail = boot(jdbc);
        LibraryConsent.landedAfterAYes("I-0009-x");
        assertThat(mail.inbox("did:person:ada", Map.of())).hasSize(1);
        assertThat(lines).hasSize(1);

        try (var conn = DriverManager.getConnection(jdbc);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT asker, locale, wording, told FROM library_yes_reports WHERE job_id = 'J-40'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("did:person:ada");
            assertThat(rs.getString(2)).isEqualTo("es");
            assertThat(rs.getString(3)).isEqualTo(TOPIC);
            assertThat(rs.getInt(4)).isEqualTo(1);
        }
        try (var conn = DriverManager.getConnection(jdbc);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT name FROM schema_migrations WHERE id = 17")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("library_yes_reports_asker");
        }
        assertThat(SchemaInitializer.SCHEMA_VERSION).isEqualTo(17);
    }

    @Test
    void a_world_db_with_migration_16_only_gains_the_columns_and_keeps_its_rows(@TempDir Path tmp) throws Exception {
        var db = tmp.resolve("world.db");
        var jdbc = SchemaInitializer.initialize(db);
        // As a node on migration 16 left it: the table without the asker, one yes job in it.
        try (var conn = DriverManager.getConnection(jdbc); var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM schema_migrations WHERE id = 17");
            stmt.execute("DROP TABLE library_yes_reports");
            LibraryYesReports.ensureTable(conn);
            stmt.execute("INSERT INTO library_yes_reports (job_id, report_id, settled, added_at) "
                + "VALUES ('J-7', 'I-0003-x', 1, '2026-09-29T00:00:00Z')");
        }

        SchemaInitializer.initialize(db);

        var reports = new LibraryYesReports(jdbc);
        assertThat(reports.rows()).singleElement().satisfies(r -> {
            assertThat(r.jobId()).isEqualTo("J-7");
            assertThat(r.asker()).isNull();
            assertThat(r.told()).isFalse();
        });
        assertThat(reports.hides("I-0003")).isTrue();
        // No one to tell for a run from before the asker was kept.
        assertThat(reports.takeReadyToTell()).isEmpty();
    }
}

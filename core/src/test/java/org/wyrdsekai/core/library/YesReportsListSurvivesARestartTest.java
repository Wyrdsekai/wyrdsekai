package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.mcp.transport.McpToolException;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list of reports kept from children is in world.db (migration 16): a restart does not show
 * a child a report researched after a yes.
 */
@Tag("integration")
class YesReportsListSurvivesARestartTest {

    static final LibraryConsent.Asker ADA = new LibraryConsent.Asker("did:person:ada", "Ada", "en");
    static final LibraryConsent.Asker KIT = new LibraryConsent.Asker("did:person:kit", "Kit", "en");
    static final String SEARCH = "{\"hits\":[{\"id\":\"I-0009-methods\",\"title\":\"a\"},{\"id\":\"F-0101-help\",\"title\":\"b\"},"
        + "{\"id\":\"F-0200-volcanoes\",\"title\":\"c\"}]}";

    private final Set<String> children = new HashSet<>(Set.of("did:person:kit"));

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryAllowPolicy.underParentalControls = children::contains;
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> {};
    }

    @AfterEach
    void tearDown() {
        LibraryConsent.resetForTests();
        LibraryRetry.sleeper = Thread::sleep;
        LibraryRetry.resetForTests();
    }

    @Test
    void the_list_is_kept_in_world_db_and_read_after_a_restart(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        LibraryConsent.useYesReports(new LibraryYesReports(jdbc));
        LibraryConsent.sender = (person, args) -> "{\"job_id\":\"J-40\",\"state\":\"queued\"}";
        LibraryConsent.lookup = (tool, args) -> "library_job".equals(tool)
            ? "{\"job\":{\"job_id\":\"J-40\",\"state\":\"done\",\"investigation\":\"I-0009-methods\"}}"
            : "{\"entry\":{\"id\":\"I-0009-methods\",\"findings\":[\"F-0101-help\"]}}";
        LibraryConsent.call(ADA, "library_research", new HashMap<>(Map.of("question", "how would someone never wake up")),
            sent -> { throw new McpToolException("library_research", McpToolException.CONFIRM, "confirm", "Call 988.", 200); });
        LibraryConsent.researchYes("did:person:ada", "en");
        LibraryConsent.call(KIT, "library_search", Map.of("query", "x"), sent -> SEARCH);   // settles J-40

        // The node restarts: nothing in memory, the library unreachable, only world.db.
        LibraryConsent.resetForTests();
        LibraryAllowPolicy.underParentalControls = children::contains;
        LibraryConsent.useYesReports(new LibraryYesReports(jdbc));
        LibraryConsent.lookup = (tool, args) -> { throw new IllegalStateException("the library is not asked again"); };

        var childSees = LibraryConsent.call(KIT, "library_search", Map.of("query", "x"), sent -> SEARCH).data();
        assertThat(childSees).contains("F-0200").doesNotContain("I-0009").doesNotContain("F-0101");
        assertThat(LibraryConsent.call(ADA, "library_search", Map.of("query", "x"), sent -> SEARCH).data()).isEqualTo(SEARCH);

        try (var conn = DriverManager.getConnection(jdbc);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT job_id, report_id, claim_ids, settled FROM library_yes_reports")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("J-40");
            assertThat(rs.getString(2)).isEqualTo("I-0009-methods");
            assertThat(rs.getString(3)).isEqualTo("F-0101-help");
            assertThat(rs.getInt(4)).isEqualTo(1);
            assertThat(rs.next()).isFalse();
        }
        try (var conn = DriverManager.getConnection(jdbc);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT name FROM schema_migrations WHERE id = 16")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("library_yes_reports");
        }
        assertThat(SchemaInitializer.SCHEMA_VERSION).isGreaterThanOrEqualTo(16);
    }
}

package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.item.HomeOwnerItemProvider;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Research runs are counted in world.db, so a restart does not give anyone a fresh day; and a
 * child's own number is the one the steward wrote on the parental-controls scroll.
 */
@Tag("integration")
class LibraryResearchCountIsKeptTest {

    static final LibraryConsent.Asker ADA = new LibraryConsent.Asker("did:person:ada", "Ada", "en");

    private final AtomicInteger jobs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryConsent.memberPerDay = () -> 5;
        LibraryConsent.ownTimePerDay = () -> 3;
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> {};
    }

    @AfterEach
    void tearDown() {
        LibraryConsent.resetForTests();
        LibraryRetry.sleeper = Thread::sleep;
        LibraryRetry.resetForTests();
        ParentalControlService.resetForTests();
    }

    private LibraryConsent.Reply ask(LibraryConsent.Asker who, AtomicInteger calls) throws Exception {
        var args = new HashMap<String, Object>();
        args.put("question", "how were the gear teeth of the Antikythera mechanism cut");
        return LibraryConsent.call(who, "library_research", args, sent -> {
            calls.incrementAndGet();
            return "{\"job_id\":\"J-" + jobs.incrementAndGet() + "\",\"state\":\"queued\"}";
        });
    }

    @Test
    void the_count_survives_a_restart(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        LibraryConsent.useLedger(new LibraryResearchLedger(jdbc));
        var calls = new AtomicInteger();
        for (int i = 0; i < 5; i++) ask(ADA, calls);
        for (int i = 0; i < 2; i++) ask(LibraryConsent.Asker.NO_ONE, calls);

        // The node restarts: a new ledger on the same world.db, nothing kept in memory.
        LibraryConsent.resetForTests();
        LibraryConsent.memberPerDay = () -> 5;
        LibraryConsent.ownTimePerDay = () -> 3;
        LibraryConsent.useLedger(new LibraryResearchLedger(jdbc));

        assertThat(ask(ADA, calls).code()).isEqualTo("limit");
        assertThat(ask(LibraryConsent.Asker.NO_ONE, calls).answered()).isTrue();     // her third
        assertThat(ask(LibraryConsent.Asker.NO_ONE, calls).code()).isEqualTo("limit");
        assertThat(calls).hasValue(8);

        try (var conn = DriverManager.getConnection(jdbc);
             var stmt = conn.prepareStatement("SELECT asks FROM library_research_asks WHERE who = ? AND day = ?")) {
            stmt.setString(1, "did:person:ada");
            stmt.setString(2, LibraryConsent.today().toString());
            var rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(5);
        }
    }

    @Test
    void migration_adds_the_research_column_to_a_parental_table_an_older_release_made(@TempDir Path tmp) throws Exception {
        var db = tmp.resolve("world.db");
        var jdbc = "jdbc:sqlite:" + db.toAbsolutePath();
        try (var conn = DriverManager.getConnection(jdbc); var stmt = conn.createStatement()) {
            // parental_controls as ParentalControlService made it before 0.5.0.
            stmt.execute("CREATE TABLE parental_controls(member_user_id TEXT PRIMARY KEY, daily_minutes INTEGER, "
                + "blocked_rooms TEXT NOT NULL DEFAULT '[]', daily_inference INTEGER, "
                + "content_filter TEXT NOT NULL DEFAULT 'off', set_by TEXT, updated_at INTEGER NOT NULL)");
            stmt.execute("INSERT INTO parental_controls(member_user_id, daily_minutes, updated_at) VALUES ('kid', 60, 1)");
        }

        SchemaInitializer.initialize(db);

        var auth = new AuthService(jdbc);
        var steward = auth.register("operator", "password123", "Operator").orElseThrow().userId();
        var parental = new ParentalControlService(jdbc, new SqlDialect.SQLite(), auth);
        parental.initSchema();
        var before = parental.controlsFor("kid").orElseThrow();
        assertThat(before.dailyMinutes()).isEqualTo(60);
        assertThat(before.dailyResearch()).isNull();                  // unset: the household's number
        assertThat(parental.setControls(steward, "kid", 60, List.of(), null, 1, "off")).isTrue();
        assertThat(parental.controlsFor("kid").orElseThrow().dailyResearch()).isEqualTo(1);
    }

    @Test
    void a_childs_own_number_is_set_on_the_parental_controls_scroll(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        var auth = new AuthService(jdbc);
        var stewardId = auth.register("operator", "password123", "Operator").orElseThrow().userId();
        var kidId = auth.register("kaz", "password123", "Kaz").orElseThrow().userId();
        var parental = ParentalControlService.init(jdbc, new SqlDialect.SQLite(), auth);
        LibraryAllowPolicy.resetForTests();                           // who is a child: the real service
        LibraryConsent.useLedger(new LibraryResearchLedger(jdbc));
        var steward = new HomeOwnerItemProvider("zone", "zone", stewardId, null, null)
            .withAuth(auth).withParental(parental);
        var kaz = new LibraryConsent.Asker(kidId, "Kaz", "en");
        var calls = new AtomicInteger();

        // Under parental controls, nothing written for research: the household's number.
        assertThat(steward.parentalSet("kaz", "minutes", 120).get("ok")).isEqualTo(true);
        assertThat(LibraryAllowPolicy.personOf(kidId)).isEqualTo(LibraryAllowPolicy.Caller.CHILD);
        assertThat(LibraryConsent.limitFor(kaz)).isEqualTo(5);

        // The steward writes a lower number for them.
        assertThat(steward.parentalSet("kaz", "research", 2).get("ok")).isEqualTo(true);
        assertThat(ask(kaz, calls).answered()).isTrue();
        assertThat(ask(kaz, calls).answered()).isTrue();
        var third = ask(kaz, calls);
        assertThat(third.code()).isEqualTo("limit");
        assertThat(third.notice()).startsWith("Kaz has asked the library for 2 research runs today");

        // 0: the library's research is closed to them.
        assertThat(steward.parentalSet("kaz", "research", "0").get("ok")).isEqualTo(true);
        assertThat(ask(kaz, calls).notice()).startsWith("The library is not open to Kaz for research");

        // "default": back to the household's number.
        var back = steward.parentalSet("kaz", "research", "default");
        assertThat(back.get("ok")).isEqualTo(true);
        assertThat(back.get("dailyResearch")).isNull();
        assertThat(back.get("researchPerDay")).isEqualTo(5);
        assertThat(back.get("researchAskedToday")).isEqualTo(2);
        assertThat(ask(kaz, calls).answered()).isTrue();
        assertThat(calls).hasValue(3);

        // "off" would read two ways (none, or no limit): refused.
        var off = steward.parentalSet("kaz", "research", "off");
        assertThat(off.get("ok")).isEqualTo(false);
        assertThat((String) off.get("error")).contains("0 = no library research");
        assertThat(parental.controlsFor(kidId).orElseThrow().dailyResearch()).isNull();

        // Other clauses written later keep the research number.
        steward.parentalSet("kaz", "research", 1);
        steward.parentalSet("kaz", "filter", "strict");
        parental.setControls(stewardId, kidId, 90, List.of(), null, "strict");
        assertThat(parental.controlsFor(kidId).orElseThrow().dailyResearch()).isEqualTo(1);
    }
}

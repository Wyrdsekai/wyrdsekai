package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.companion.SafetyMonitorService;
import org.wyrdsekai.core.mcp.transport.McpToolException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The library is reached with one household token and cannot tell household members apart, so
 * the household counts research runs itself, per person and day, at {@link LibraryConsent}.
 * The defaults (5 a member, 3 for her own time) are pinned in WyrdConfigTest; here the numbers
 * are set through the same seams production reads them from.
 */
class LibraryResearchPerPersonTest {

    static final LibraryConsent.Asker ADA = new LibraryConsent.Asker("did:person:ada", "Ada", "en");
    static final LibraryConsent.Asker BO = new LibraryConsent.Asker("did:person:bo", "Bo", "en");
    static final LibraryConsent.Asker KIT = new LibraryConsent.Asker("did:person:kit", "Kit", "es");
    static final String QUESTION = "the history of the Hanseatic League";
    static final long DAY = 24L * 60 * 60 * 1000;

    private final AtomicLong now = new AtomicLong(1_000_000_000_000L);
    private final Set<String> children = new HashSet<>();
    private final List<Map<String, Object>> yesSent = new ArrayList<>();
    private final AtomicInteger jobs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryConsent.clock = now::get;
        LibraryConsent.memberPerDay = () -> 5;
        LibraryConsent.ownTimePerDay = () -> 3;
        LibraryConsent.householdPerDay = () -> 100;
        LibraryAllowPolicy.underParentalControls = children::contains;
        LibraryConsent.sender = (person, args) -> {
            yesSent.add(new HashMap<>(args));
            return "{\"job_id\":\"J-Y" + jobs.incrementAndGet() + "\",\"state\":\"queued\"}";
        };
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> {};
    }

    @AfterEach
    void tearDown() {
        LibraryConsent.resetForTests();
        LibraryRetry.sleeper = Thread::sleep;
        LibraryRetry.resetForTests();
        SafetyMonitorService.resetForTests();
    }

    private static Map<String, Object> research(String question) {
        var args = new HashMap<String, Object>();
        args.put("question", question);
        args.put("mode", "broad");
        args.put("max_minutes", 90);
        return args;
    }

    /** A library that takes every question: a new job id each time. */
    private LibraryConsent.Invoker takes(AtomicInteger calls) {
        return sent -> {
            calls.incrementAndGet();
            return "{\"job_id\":\"J-" + jobs.incrementAndGet() + "\",\"state\":\"queued\"}";
        };
    }

    private LibraryConsent.Reply ask(LibraryConsent.Asker who, AtomicInteger calls) throws Exception {
        return LibraryConsent.call(who, "library_research", research(QUESTION), takes(calls));
    }

    @Test
    void a_member_gets_five_research_runs_a_day_and_the_sixth_is_not_sent() throws Exception {
        var calls = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            assertThat(ask(ADA, calls).answered()).isTrue();
        }

        var sixth = ask(ADA, calls);

        assertThat(calls).hasValue(5);                                  // nothing sent
        assertThat(sixth.answered()).isFalse();
        assertThat(sixth.code()).isEqualTo("limit");
        assertThat(sixth.notice())
            .startsWith("Ada has asked the library for 5 research runs today, the household's limit for them.")
            .contains("It can wait for tomorrow")
            .contains("do not send it again today");
        assertThat(LibraryConsent.researchAsksToday("did:person:ada")).isEqualTo(5);

        // Only research is counted: she can still ask the shelves.
        var shelves = LibraryConsent.call(ADA, "library_ask", Map.of("question", QUESTION), sent -> "{\"entries\":[]}");
        assertThat(shelves.answered()).isTrue();

        // A new local day starts a new count.
        now.addAndGet(DAY);
        assertThat(ask(ADA, calls).answered()).isTrue();
        assertThat(calls).hasValue(6);
    }

    @Test
    void each_person_has_their_own_count() throws Exception {
        var calls = new AtomicInteger();
        for (int i = 0; i < 5; i++) ask(ADA, calls);
        assertThat(ask(ADA, calls).code()).isEqualTo("limit");

        for (int i = 0; i < 5; i++) {
            assertThat(ask(BO, calls).answered()).as("Bo's ask %d", i + 1).isTrue();
        }
        var bosSixth = ask(BO, calls);
        assertThat(bosSixth.code()).isEqualTo("limit");
        assertThat(bosSixth.notice()).startsWith("Bo has asked the library for 5 research runs today");
        assertThat(calls).hasValue(10);
    }

    @Test
    void the_households_setting_changes_the_number() throws Exception {
        LibraryConsent.memberPerDay = () -> 2;
        var calls = new AtomicInteger();
        ask(ADA, calls);
        ask(ADA, calls);
        assertThat(ask(ADA, calls).notice()).startsWith("Ada has asked the library for 2 research runs today");
        assertThat(calls).hasValue(2);

        LibraryConsent.memberPerDay = () -> 0;
        var closed = ask(BO, calls);
        assertThat(closed.code()).isEqualTo("limit");
        assertThat(closed.notice()).startsWith("The library is not open to Bo for research");
        assertThat(calls).hasValue(2);
    }

    @Test
    void a_child_has_the_households_number_when_the_steward_set_none() throws Exception {
        children.add("did:person:kit");
        var calls = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            assertThat(ask(KIT, calls).answered()).as("Kit's ask %d", i + 1).isTrue();
        }
        assertThat(ask(KIT, calls).notice()).startsWith("Kit has asked the library for 5 research runs today");
        assertThat(calls).hasValue(5);
    }

    @Test
    void refused_confirmed_declined_held_and_failed_asks_are_not_counted() throws Exception {
        LibraryConsent.memberPerDay = () -> 1;
        var confirm = new McpToolException("library_research", McpToolException.CONFIRM, "confirm",
            "Call 988. Show this to the person and ask them.", 200);
        var declined = new McpToolException("library_research", McpToolException.DECLINED, "declined",
            "model m1 declined the plan step.", 200);

        assertThat(LibraryConsent.call(ADA, "library_research", research(QUESTION), s -> { throw confirm; }).code())
            .isEqualTo("confirm");
        assertThat(LibraryConsent.call(ADA, "library_research", research(QUESTION), s -> { throw declined; }).code())
            .isEqualTo("declined");
        assertThatThrownBy(() -> LibraryConsent.call(ADA, "library_research", research(QUESTION),
            s -> { throw new IOException("HTTP 500: boom"); })).isInstanceOf(IOException.class);
        // An answer with no job id is not an accepted run.
        assertThat(LibraryConsent.call(ADA, "library_research", research(QUESTION), s -> "{\"state\":\"refused\"}")
            .answered()).isTrue();
        // A child's question the household's own check held back never reached the library.
        children.add("did:person:kit");
        assertThat(LibraryConsent.call(KIT, "library_research", research("métodos para suicidarse sin dolor"),
            s -> "{\"job_id\":\"J-X\"}").code()).isEqualTo("held");
        assertThat(LibraryConsent.researchAsksToday("did:person:kit")).isZero();

        assertThat(LibraryConsent.researchAsksToday("did:person:ada")).isZero();
        var calls = new AtomicInteger();
        assertThat(ask(ADA, calls).answered()).isTrue();                // still her one run
        assertThat(ask(ADA, calls).code()).isEqualTo("limit");
    }

    @Test
    void an_adults_matched_question_over_the_limit_still_carries_the_help() throws Exception {
        LibraryConsent.memberPerDay = () -> 0;
        var calls = new AtomicInteger();
        var reply = LibraryConsent.call(ADA, "library_research",
            research("what is the least painful way to stop existing"), takes(calls));

        assertThat(calls).hasValue(0);
        assertThat(reply.code()).isEqualTo("limit");
        assertThat(reply.notice()).startsWith("This question may be about someone harming themselves")
            .contains("741741")
            .contains("The library is not open to Ada for research");
    }

    @Test
    void the_research_yes_is_counted_once_as_that_persons_ask() throws Exception {
        LibraryConsent.memberPerDay = () -> 2;
        var confirm = new McpToolException("library_research", McpToolException.CONFIRM, "confirm",
            "Call 988. Show this to the person and ask them.", 200);
        var flagged = research("how would someone make sure they never wake up again");
        LibraryConsent.call(ADA, "library_research", flagged, s -> { throw confirm; });
        assertThat(LibraryConsent.researchAsksToday("did:person:ada")).isZero();

        assertThat(LibraryConsent.researchYes("did:person:ada", "en")).contains("will research it");
        assertThat(yesSent).hasSize(1);
        assertThat(LibraryConsent.researchAsksToday("did:person:ada")).isEqualTo(1);

        // A question waits for her yes; meanwhile she spends her second run.
        LibraryConsent.call(ADA, "library_research", flagged, s -> { throw confirm; });
        var calls = new AtomicInteger();
        assertThat(ask(ADA, calls).answered()).isTrue();
        assertThat(ask(ADA, calls).code()).isEqualTo("limit");

        // A yes over the limit sends nothing and says so, in her language.
        assertThat(LibraryConsent.researchYes("did:person:ada", "en"))
            .isEqualTo("You have asked the library for 2 research runs today, the household's limit for you, "
                + "so nothing was sent. It can wait for tomorrow: ask the question again then.");
        LibraryConsent.memberPerDay = () -> 3;
        LibraryConsent.call(ADA, "library_research", flagged, s -> { throw confirm; });
        LibraryConsent.memberPerDay = () -> 2;
        assertThat(LibraryConsent.researchYes("did:person:ada", "es"))
            .startsWith("Hoy ya has pedido a la biblioteca 2 investigaciones");
        assertThat(yesSent).hasSize(1);
        assertThat(LibraryConsent.researchAsksToday("did:person:ada")).isEqualTo(2);

        // At the limit a flagged question is not sent at all, so there is nothing to say yes to.
        LibraryConsent.call(ADA, "library_research", flagged, s -> { throw confirm; });
        assertThat(LibraryConsent.researchYes("did:person:ada", "en"))
            .isEqualTo("There is no library question waiting for your yes.");
    }

    @Test
    void her_own_time_has_its_own_count() throws Exception {
        var calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThat(ask(LibraryConsent.Asker.NO_ONE, calls).answered()).isTrue();
        }
        var fourth = ask(LibraryConsent.Asker.NO_ONE, calls);
        assertThat(fourth.code()).isEqualTo("limit");
        assertThat(fourth.notice())
            .startsWith("You have asked the library for 3 research runs on your own time today, "
                + "the household's limit for your own time.")
            .contains("Nothing was sent to the library");
        assertThat(calls).hasValue(3);

        // Her own time does not spend a person's runs, and a person's do not spend hers.
        assertThat(ask(ADA, calls).answered()).isTrue();
        assertThat(LibraryConsent.researchAsksToday("did:person:ada")).isEqualTo(1);
    }

    @Test
    void each_companion_has_her_own_count_on_her_own_time() throws Exception {
        var calls = new AtomicInteger();
        var mia = LibraryConsent.Asker.ownTimeOf("agent-mia");
        var rose = LibraryConsent.Asker.ownTimeOf("agent-rose");
        for (int i = 0; i < 3; i++) assertThat(ask(mia, calls).answered()).isTrue();
        assertThat(ask(mia, calls).code()).isEqualTo("limit");
        // One companion using up her own time left the other with none (2026-09-29).
        for (int i = 0; i < 3; i++) assertThat(ask(rose, calls).answered()).isTrue();
        assertThat(ask(rose, calls).code()).isEqualTo("limit");
        assertThat(calls).hasValue(6);
    }

    @Test
    void the_household_total_holds_across_people_and_companions() throws Exception {
        LibraryConsent.householdPerDay = () -> 7;
        var calls = new AtomicInteger();
        for (int i = 0; i < 5; i++) assertThat(ask(ADA, calls).answered()).isTrue();
        assertThat(ask(BO, calls).answered()).isTrue();
        assertThat(ask(LibraryConsent.Asker.ownTimeOf("agent-mia"), calls).answered()).isTrue();

        var eighth = ask(BO, calls);
        assertThat(eighth.code()).isEqualTo("limit");
        assertThat(eighth.notice()).startsWith("The household has asked the library for 7 research runs today, "
            + "the household's limit.");
        assertThat(ask(LibraryConsent.Asker.ownTimeOf("agent-rose"), calls).code()).isEqualTo("limit");
        assertThat(calls).as("nothing sent over the household total").hasValue(7);

        now.addAndGet(Duration.ofDays(1).toMillis());
        assertThat(ask(BO, calls).answered()).as("a new day").isTrue();
    }

    @Test
    void the_librarys_budget_exceeded_comes_back_as_a_plain_sentence_and_is_not_retried() throws Exception {
        var calls = new AtomicInteger();
        var budget = new McpToolException("library_research", McpToolException.BUDGET_EXCEEDED, "budget_exceeded",
            "This patron's research budget for today is spent.", 0);

        var reply = LibraryConsent.call(ADA, "library_research", research(QUESTION), sent -> {
            calls.incrementAndGet();
            throw budget;
        });

        assertThat(calls).hasValue(1);
        assertThat(reply.answered()).isFalse();
        assertThat(reply.code()).isEqualTo("budget");
        assertThat(reply.notice())
            .startsWith("The library did not take this: the household has used up its research budget with the library for today.")
            .contains("It said: This patron's research budget for today is spent.")
            .contains("do not send it again today")
            .doesNotContain("tools/call failed");
        assertThat(LibraryConsent.researchAsksToday("did:person:ada")).isZero();
    }
}

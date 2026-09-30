package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.mcp.transport.McpToolException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A report researched after a person's {@code research yes} is in the shared household library,
 * and a member under parental controls never sees it: not in a search, an answer, the job list or
 * by its id, and nothing says it was left out. An adult sees it as before.
 */
class YesReportsAreKeptFromChildrenTest {

    static final LibraryConsent.Asker ADA = new LibraryConsent.Asker("did:person:ada", "Ada", "en");
    static final LibraryConsent.Asker KIT = new LibraryConsent.Asker("did:person:kit", "Kit", "en");

    /** The library's search: the yes report, a claim it filed, and an unrelated entry. */
    static final String SEARCH = """
        {"library_id":"lib-1","hits":[
          {"id":"I-0009-methods-of-suicide","kind":"investigation","title":"Methods of suicide","snippet":"..."},
          {"id":"F-0100-lethality","kind":"finding","title":"Lethality of methods","snippet":"..."},
          {"id":"F-0200-volcanoes","kind":"finding","title":"Obsidian is volcanic glass","snippet":"..."}]}""";
    static final String ASK = """
        {"library_id":"lib-1","library_name":"The Stacks","holds_nothing":false,"rendered":"I-0009: methods ...",
         "open_threads":["what the report left open"],
         "entries":[{"id":"I-0009-methods-of-suicide","kind":"investigation","title":"Methods of suicide","body":"..."}]}""";
    static final String JOBS = """
        {"active":[],"finished":[
          {"job_id":"J-40","state":"done","question":"Methods of suicide and their lethality","investigation":"I-0009-methods-of-suicide"},
          {"job_id":"J-41","state":"done","question":"another yes nobody listed","allow":["self-harm"],"investigation":"I-0011-x"},
          {"job_id":"J-39","state":"done","question":"gear teeth of the Antikythera mechanism","investigation":"I-0008-gears"}]}""";

    private final Set<String> children = new HashSet<>();
    private final List<String> lookups = new ArrayList<>();
    private final List<String> sentTools = new ArrayList<>();
    /** What the fake library says a job is and a report holds. */
    private String jobState = "done";
    private boolean libraryDown;

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryAllowPolicy.underParentalControls = children::contains;
        children.add("did:person:kit");
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> {};
        LibraryConsent.sender = (person, args) -> "{\"job_id\":\"J-40\",\"state\":\"queued\"}";
        LibraryConsent.lookup = (tool, args) -> {
            lookups.add(tool + " " + args.getOrDefault("job_id", args.get("id")));
            if (libraryDown) throw new java.io.IOException("Connection refused");
            return switch (tool) {
                case "library_job" -> "done".equals(jobState)
                    ? "{\"job\":{\"job_id\":\"J-40\",\"state\":\"done\",\"investigation\":\"I-0009-methods-of-suicide\"}}"
                    : "{\"job\":{\"job_id\":\"J-40\",\"state\":\"" + jobState + "\"}}";
                case "library_get" -> "{\"entry\":{\"id\":\"I-0009-methods-of-suicide\",\"kind\":\"investigation\","
                    + "\"findings\":[\"F-0100-lethality\",\"F-0101-help\"]}}";
                default -> throw new IllegalArgumentException(tool);
            };
        };
    }

    @AfterEach
    void tearDown() {
        LibraryConsent.resetForTests();
        LibraryRetry.sleeper = Thread::sleep;
        LibraryRetry.resetForTests();
    }

    /** Ada's yes: the library confirmed her question, she typed research yes, it took job J-40. */
    private void adaSaysYes() throws Exception {
        LibraryConsent.call(ADA, "library_research", new HashMap<>(Map.of("question", "how would someone make sure they never wake up")),
            sent -> { throw new McpToolException("library_research", McpToolException.CONFIRM, "confirm", "Call 988.", 200); });
        assertThat(LibraryConsent.researchYes("did:person:ada", "en")).contains("will research it");
    }

    private String ask(LibraryConsent.Asker who, String tool, Map<String, Object> args, String answer) throws Exception {
        var reply = LibraryConsent.call(who, tool, args, sent -> {
            sentTools.add(tool);
            return answer;
        });
        assertThat(reply.answered()).isTrue();
        return reply.data();
    }

    @Test
    void a_childs_search_and_answer_leave_out_the_report_and_its_claims_and_say_nothing_of_it() throws Exception {
        adaSaysYes();

        var childSearch = ask(KIT, "library_search", Map.of("query", "methods"), SEARCH);
        assertThat(childSearch).contains("F-0200-volcanoes")
            .doesNotContain("I-0009").doesNotContain("F-0100").doesNotContain("Methods of suicide");
        assertThat(childSearch.toLowerCase()).doesNotContain("hidden").doesNotContain("withheld").doesNotContain("kept");
        // The library was asked once what J-40 became, and what the report filed.
        assertThat(lookups).containsExactly("library_job J-40", "library_get I-0009-methods-of-suicide");

        var childAnswer = ask(KIT, "library_ask", Map.of("question", "methods"), ASK);
        assertThat(childAnswer).contains("\"holds_nothing\":true").doesNotContain("I-0009").doesNotContain("rendered")
            .doesNotContain("what the report left open");

        // Settled once: no more questions to the library about J-40.
        assertThat(lookups).hasSize(2);

        // Ada, an adult, reads the library as it is.
        assertThat(ask(ADA, "library_search", Map.of("query", "methods"), SEARCH)).isEqualTo(SEARCH);
        assertThat(ask(ADA, "library_ask", Map.of("question", "methods"), ASK)).isEqualTo(ASK);
    }

    @Test
    void the_report_by_its_id_reads_as_an_id_the_library_does_not_hold() throws Exception {
        adaSaysYes();

        record Asked(String tool, Map<String, Object> args, String says) {}
        for (var asked : List.of(
                new Asked("library_get", Map.of("id", "I-0009-methods-of-suicide"), "No entry has the id I-0009-methods-of-suicide."),
                new Asked("library_get", Map.of("id", "I-0009"), "No entry has the id I-0009."),
                new Asked("library_explain", Map.of("id", "F-0101-help", "rung", "beginner"), "No entry has the id F-0101-help."),
                new Asked("library_job", Map.of("job_id", "J-40"), "No research run has the id J-40."))) {
            assertThatThrownBy(() -> LibraryConsent.call(KIT, asked.tool(), asked.args(), sent -> {
                    sentTools.add(asked.tool());
                    return "{}";
                }))
                .isInstanceOfSatisfying(McpToolException.class, e -> {
                    // The library's own answer to an id it does not hold.
                    assertThat(e.dataCode()).isEqualTo("not_found");
                    assertThat(e.rpcCode()).isEqualTo(-32004);
                    assertThat(e.toolMessage()).isEqualTo(asked.says());
                });
        }
        assertThat(sentTools).isEmpty();

        // An explanation written from it, asked for by a word rather than its id: nothing held.
        var explained = ask(KIT, "library_explain", Map.of("term", "lethality"),
            "{\"of\":\"I-0009-methods-of-suicide\",\"term\":\"lethality\",\"text\":\"...\"}");
        assertThat(explained).isEqualTo("{\"holds_nothing\":true}");

        // Ada reads it by its id.
        assertThat(ask(ADA, "library_get", Map.of("id", "I-0009"), "{\"entry\":{\"id\":\"I-0009-methods-of-suicide\"}}"))
            .contains("I-0009");
    }

    @Test
    void a_childs_job_list_leaves_out_every_yes_even_one_nobody_listed() throws Exception {
        adaSaysYes();

        var childJobs = ask(KIT, "library_job", Map.of("limit", 20), JOBS);
        assertThat(childJobs).contains("J-39").contains("I-0008-gears")
            .doesNotContain("J-40").doesNotContain("J-41").doesNotContain("I-0011").doesNotContain("suicide");

        // The job row with allow self-harm taught the household its report; the next search hides it.
        var search = ask(KIT, "library_search", Map.of("query", "x"),
            "{\"hits\":[{\"id\":\"I-0011-x\",\"title\":\"x\"},{\"id\":\"F-0200-volcanoes\",\"title\":\"y\"}]}");
        assertThat(search).doesNotContain("I-0011").contains("F-0200");
    }

    @Test
    void what_the_companion_is_told_about_a_childs_answer_carries_nothing_from_the_report_either() throws Exception {
        adaSaysYes();
        var jobs = "{\"active\":[],\"finished\":[{\"job_id\":\"J-40\",\"state\":\"done\",\"investigation\":\"I-0009-x\","
            + "\"declined\":{\"statement\":\"m2 would not list methods of suicide\"}}]}";

        var reply = LibraryConsent.call(KIT, "library_job", Map.of("limit", 20), sent -> jobs);
        assertThat(reply.data()).doesNotContain("J-40");
        assertThat(reply.notice()).isNull();

        var adults = LibraryConsent.call(ADA, "library_job", Map.of("limit", 20), sent -> jobs);
        assertThat(adults.notice()).contains("m2 would not list methods of suicide");
    }

    @Test
    void what_anyone_reads_teaches_the_household_the_reports_id_without_asking() throws Exception {
        adaSaysYes();

        // Ada reads her job, then the report: the report and its claims are learned on the way.
        ask(ADA, "library_job", Map.of("job_id", "J-40"),
            "{\"job\":{\"job_id\":\"J-40\",\"state\":\"done\",\"investigation\":\"I-0009-methods-of-suicide\"}}");
        ask(ADA, "library_get", Map.of("id", "I-0009-methods-of-suicide"),
            "{\"entry\":{\"id\":\"I-0009-methods-of-suicide\",\"kind\":\"investigation\",\"findings\":[\"F-0100-lethality\"]}}");

        var childSearch = ask(KIT, "library_search", Map.of("query", "methods"), SEARCH);
        assertThat(childSearch).doesNotContain("I-0009").doesNotContain("F-0100").contains("F-0200");
        assertThat(lookups).isEmpty();
    }

    @Test
    void while_the_run_is_going_nothing_is_hidden_but_its_job_and_the_library_is_asked_again() throws Exception {
        adaSaysYes();
        jobState = "running";

        ask(KIT, "library_search", Map.of("query", "methods"), SEARCH);
        ask(KIT, "library_search", Map.of("query", "methods"), SEARCH);
        assertThat(lookups).containsExactly("library_job J-40", "library_job J-40", "library_job J-40", "library_job J-40");

        // What an actor's path may use without asking: every library id, while a yes is unsettled.
        assertThat(LibraryConsent.keptFrom(KIT).test("F-0200-volcanoes")).isTrue();
        assertThat(LibraryConsent.keptFrom(ADA).test("I-0009")).isFalse();

        jobState = "done";
        ask(KIT, "library_search", Map.of("query", "methods"), SEARCH);
        assertThat(LibraryConsent.keptFrom(KIT).test("I-0009-methods-of-suicide")).isTrue();
        assertThat(LibraryConsent.keptFrom(KIT).test("lib-1:F-0101-help")).isTrue();
        assertThat(LibraryConsent.keptFrom(KIT).test("F-0200-volcanoes")).isFalse();
    }

    @Test
    void when_the_library_cannot_say_what_a_yes_became_a_childs_call_fails_and_an_adults_does_not() throws Exception {
        adaSaysYes();
        libraryDown = true;

        assertThatThrownBy(() -> LibraryConsent.call(KIT, "library_search", Map.of("query", "methods"), sent -> SEARCH))
            .isInstanceOf(java.io.IOException.class);
        assertThat(ask(ADA, "library_search", Map.of("query", "methods"), SEARCH)).isEqualTo(SEARCH);
        assertThat(LibraryConsent.landedAfterAYes("I-0012-anything")).isTrue();     // cannot be ruled out
    }

    @Test
    void the_report_on_an_adults_question_our_check_matched_is_kept_from_children_too() throws Exception {
        // Our check matched; the library took it without asking for a yes.
        LibraryConsent.call(ADA, "library_research", new HashMap<>(Map.of("question", "what is the least painful way to stop existing")),
            sent -> "{\"job_id\":\"J-40\",\"state\":\"queued\"}");

        var childJobs = ask(KIT, "library_job", Map.of("limit", 20), JOBS);
        assertThat(childJobs).doesNotContain("J-40").contains("J-39");

        // A question our check does not match is an ordinary report.
        LibraryConsent.call(ADA, "library_research", new HashMap<>(Map.of("question", "how were the Antikythera gear teeth cut")),
            sent -> "{\"job_id\":\"J-39\",\"state\":\"queued\"}");
        assertThat(ask(KIT, "library_job", Map.of("limit", 20), JOBS)).contains("J-39");
    }
}

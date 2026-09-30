package org.wyrdsekai.core.library;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.companion.ChildProfile;
import org.wyrdsekai.core.companion.SafetyAlertRouter;
import org.wyrdsekai.core.companion.SafetyMonitorService;
import org.wyrdsekai.core.companion.SafetyTrigger;
import org.wyrdsekai.core.mcp.transport.McpToolException;

import java.io.IOException;
import java.net.ConnectException;
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
 * What the household does with ResearchZosho 0.5.0's two answers: {@code confirm} (the library
 * researches a flagged question only on a person's own yes) and {@code declined} (a model refused
 * a step; nobody retries, rewords or switches model).
 */
class LibraryConsentTest {

    static final String HELP = "If you are in the US or Canada, call or text 988. In the UK or Ireland, "
        + "call Samaritans on 116 123. Or find a line at findahelpline.com.";
    static final String CONFIRM_MESSAGE = HELP + " Show this to the person and ask them whether the library "
        + "should research the question; send the question again with allow [\"self-harm\"] only if the person says yes.";
    /** A question the household's own check reads as self-harm too. */
    static final String QUESTION = "what is the least painful way to stop existing";
    /** A question only the library's model would catch: our patterns do not match it. */
    static final String LIBRARY_ONLY = "how would someone make sure they never wake up again";

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Map<String, Object>> yesSent = new ArrayList<>();
    private final List<String> yesFrom = new ArrayList<>();
    private final Set<String> children = new HashSet<>();
    private final List<String> sleeps = new ArrayList<>();

    static final LibraryConsent.Asker ADA = new LibraryConsent.Asker("did:person:ada", "Ada", "en");
    static final LibraryConsent.Asker KIT = new LibraryConsent.Asker("did:person:kit", "Kit", "es");

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryConsent.clock = now::get;
        LibraryAllowPolicy.underParentalControls = children::contains;
        LibraryConsent.sender = (person, args) -> {
            yesFrom.add(person);
            yesSent.add(new HashMap<>(args));
            return "{\"job_id\":\"J-9\",\"state\":\"queued\"}";
        };
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> sleeps.add("slept " + ms);
    }

    @AfterEach
    void tearDown() {
        LibraryConsent.resetForTests();
        LibraryRetry.sleeper = Thread::sleep;
        LibraryRetry.resetForTests();
        SafetyMonitorService.resetForTests();
    }

    private static McpToolException confirm() {
        return new McpToolException("library_research", McpToolException.CONFIRM, "confirm", CONFIRM_MESSAGE, 200);
    }

    private static Map<String, Object> researchArgs() {
        return researchArgs(QUESTION);
    }

    private static Map<String, Object> researchArgs(String question) {
        var args = new HashMap<String, Object>();
        args.put("question", question);
        args.put("mode", "broad");
        args.put("max_minutes", 90);
        return args;
    }

    private static LibraryConsent.Invoker answering(McpToolException e, AtomicInteger calls) {
        return sent -> {
            calls.incrementAndGet();
            throw e;
        };
    }

    // ── confirm, for a person ─────────────────────────────────────────────

    @Test
    void confirm_for_a_person_is_a_plain_result_with_the_help_the_yes_and_who_will_see_the_report() throws Exception {
        var calls = new AtomicInteger();
        var reply = LibraryConsent.call(ADA, "library_research", researchArgs(), answering(confirm(), calls));

        assertThat(reply.answered()).isFalse();
        assertThat(reply.code()).isEqualTo("confirm");
        var text = reply.notice();
        assertThat(text).contains(HELP);
        assertThat(text).contains("share this with Ada, gently and in their language");
        assertThat(text).contains("research yes");
        assertThat(text).contains("everyone who can read the library can see it");
        assertThat(text).contains("741741");                                // our crisis lines for en
        // The host's own instruction from the library is not passed to the model as one.
        assertThat(text).doesNotContain("send the question again with allow");
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
        assertThat(LibraryConsent.waitingFor("did:person:ada")).isTrue();
    }

    @Test
    void research_yes_by_that_person_sends_allow_self_harm_exactly_once() throws Exception {
        LibraryConsent.call(ADA, "library_research", researchArgs(), answering(confirm(), new AtomicInteger()));

        var first = LibraryConsent.researchYes("did:person:ada", "en");
        var second = LibraryConsent.researchYes("did:person:ada", "en");

        assertThat(yesSent).hasSize(1);
        assertThat(yesFrom).containsExactly("did:person:ada");
        var sent = yesSent.getFirst();
        assertThat(sent).containsEntry("allow", List.of("self-harm"));
        // In the neutral wording (here the general one: no model is wired), never the person's words.
        assertThat(sent).containsEntry("question", NeutralWording.general("en"))
            .containsEntry("mode", "broad").containsEntry("max_minutes", 90);
        assertThat(first).contains("will research it").contains(NeutralWording.general("en"))
            .contains("everyone who can read the library");
        assertThat(second).isEqualTo("There is no library question waiting for your yes.");
    }

    @Test
    void research_yes_by_another_person_does_nothing() throws Exception {
        LibraryConsent.call(ADA, "library_research", researchArgs(), answering(confirm(), new AtomicInteger()));

        var reply = LibraryConsent.researchYes("did:person:bo", "en");

        assertThat(yesSent).isEmpty();
        assertThat(reply).isEqualTo("There is no library question waiting for your yes.");
        assertThat(LibraryConsent.waitingFor("did:person:ada")).isTrue();   // Ada's still waits for Ada
    }

    @Test
    void a_waiting_question_expires_after_an_hour_and_only_one_waits_per_person() throws Exception {
        LibraryConsent.rewording = (instructions, q) -> q.contains("second") ? "Second topic" : "First topic";
        LibraryConsent.call(ADA, "library_research", researchArgs(), answering(confirm(), new AtomicInteger()));
        var second = researchArgs();
        second.put("question", "a second flagged question");
        LibraryConsent.call(ADA, "library_research", second, answering(confirm(), new AtomicInteger()));

        LibraryConsent.researchYes("did:person:ada", "en");
        assertThat(yesSent).hasSize(1);
        assertThat(yesSent.getFirst()).containsEntry("question", "Second topic");

        LibraryConsent.call(ADA, "library_research", researchArgs(), answering(confirm(), new AtomicInteger()));
        now.addAndGet(LibraryConsent.WAITS_FOR.toMillis() + 1);
        assertThat(LibraryConsent.researchYes("did:person:ada", "en"))
            .isEqualTo("There is no library question waiting for your yes.");
        assertThat(yesSent).hasSize(1);
    }

    @Test
    void the_reply_to_research_yes_is_in_the_persons_language() throws Exception {
        LibraryConsent.call(KIT, "library_research", researchArgs(), answering(confirm(), new AtomicInteger()));
        assertThat(LibraryConsent.researchYes("did:person:kit", "es")).startsWith("La biblioteca ha recibido tu pregunta");
        assertThat(LibraryConsent.researchYes("did:person:kit", "ja")).startsWith("あなたの「はい」");
    }

    @Test
    void a_yes_that_cannot_reach_the_library_still_waits() throws Exception {
        LibraryConsent.call(ADA, "library_research", researchArgs(), answering(confirm(), new AtomicInteger()));
        LibraryConsent.sender = (person, args) -> { throw new IOException("send", new ConnectException("Connection refused")); };

        assertThat(LibraryConsent.researchYes("did:person:ada", "en")).contains("could not be reached");
        assertThat(LibraryConsent.waitingFor("did:person:ada")).isTrue();
    }

    // ── model-supplied allow ──────────────────────────────────────────────

    @Test
    void allow_the_model_supplied_is_never_sent_and_never_kept() throws Exception {
        var seen = new ArrayList<Map<String, Object>>();
        var args = researchArgs();
        args.put("allow", List.of("self-harm"));
        var reply = LibraryConsent.call(ADA, "library_research", args, sent -> {
            seen.add(sent);
            throw confirm();
        });
        assertThat(reply.code()).isEqualTo("confirm");
        assertThat(seen.getFirst()).doesNotContainKey("allow");

        var other = new HashMap<String, Object>(Map.of("query", "x", "allow", List.of("explicit", "howto")));
        LibraryConsent.call(ADA, "library_search", other, sent -> {
            seen.add(sent);
            return "{\"hits\":[]}";
        });
        assertThat(seen.get(1)).doesNotContainKey("allow").containsEntry("query", "x");

        // The yes sends the one allow the library asked about, whatever the model had put there.
        LibraryConsent.researchYes("did:person:ada", "en");
        assertThat(yesSent.getFirst().get("allow")).isEqualTo(List.of("self-harm"));
    }

    // ── confirm, for a child ──────────────────────────────────────────────

    @Test
    void confirm_for_a_child_goes_to_the_safety_path_and_offers_no_yes() throws Exception {
        children.add("did:person:kit");
        var delivered = new ArrayList<String[]>();
        var trigger = new SafetyTrigger();
        SafetyMonitorService.registerForTests(new SafetyMonitorService(trigger, new SafetyAlertRouter(),
            (target, message, priority, source) -> delivered.add(new String[]{target, message, priority}),
            children::contains,
            (id, name) -> new ChildProfile(id, "did:person:steward", 10, List.of(), false),
            Runnable::run));

        var reply = LibraryConsent.call(KIT, "library_research", researchArgs(LIBRARY_ONLY), answering(confirm(), new AtomicInteger()));

        assertThat(reply.answered()).isFalse();
        var text = reply.notice();
        assertThat(text).contains(HELP).contains("Kit is a child in this household");
        assertThat(text).contains("717 003 717");                          // our crisis lines for es
        assertThat(text).doesNotContain("research yes");
        assertThat(LibraryConsent.waitingFor("did:person:kit")).isFalse();

        // Routed the way the child's own words would be: one alert, type and severity, no words.
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst()[1]).contains("self harm").doesNotContain(LIBRARY_ONLY);
        assertThat(trigger.byType(SafetyTrigger.ConcernType.SELF_HARM)).hasSize(1);
        assertThat(trigger.unrouted()).isEmpty();

        var refused = LibraryConsent.researchYes("did:person:kit", "es");
        assertThat(refused).startsWith("La biblioteca no investiga este tipo de pregunta para ti");
        assertThat(yesSent).isEmpty();
    }

    // ── confirm, on her own time ──────────────────────────────────────────

    @Test
    void confirm_on_her_own_time_is_set_aside() throws Exception {
        var calls = new AtomicInteger();
        var reply = LibraryConsent.call(LibraryConsent.Asker.NO_ONE, "library_research", researchArgs(LIBRARY_ONLY),
            answering(confirm(), calls));

        assertThat(reply.answered()).isFalse();
        assertThat(reply.notice()).contains("set this question aside")
            .contains("only when a person says yes themselves")
            .doesNotContain("research yes");
        assertThat(calls).hasValue(1);
        assertThat(LibraryConsent.researchYes("did:person:ada", "en"))
            .isEqualTo("There is no library question waiting for your yes.");
        assertThat(yesSent).isEmpty();
    }

    // ── the household's own check ─────────────────────────────────────────

    static final String NOTE = "This question may be about someone harming themselves";

    private SafetyTrigger registerSafetyCheck(List<String[]> delivered) {
        var trigger = new SafetyTrigger();
        SafetyMonitorService.registerForTests(new SafetyMonitorService(trigger, new SafetyAlertRouter(),
            (target, message, priority, source) -> delivered.add(new String[]{target, message, priority}),
            children::contains,
            (id, name) -> new ChildProfile(id, "did:person:steward", 10, List.of(), false),
            Runnable::run));
        return trigger;
    }

    @Test
    void our_check_sends_an_adults_question_as_usual_and_puts_the_help_first() throws Exception {
        var seen = new ArrayList<Map<String, Object>>();
        var job = "{\"job_id\":\"J-12\",\"state\":\"queued\"}";
        var reply = LibraryConsent.call(ADA, "library_research", researchArgs(), sent -> {
            seen.add(sent);
            return job;
        });

        // Sent once, at once, unchanged: a false match costs only the note.
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst()).containsEntry("question", QUESTION).doesNotContainKey("allow");
        assertThat(sleeps).isEmpty();
        assertThat(reply.answered()).isTrue();
        assertThat(reply.data()).isEqualTo(job);
        assertThat(reply.notice()).startsWith(NOTE)
            .contains("Share these lines with Ada, gently and in their language")
            .contains("741741")                                               // our crisis lines for en
            .contains("findahelpline.com")
            .doesNotContain(QUESTION)
            .doesNotContain("research yes");
        assertThat(LibraryConsent.waitingFor("did:person:ada")).isFalse();

        // Before the library's answer, where the companion's script reads it.
        assertThat(LibraryConsent.withNotice(reply.data(), reply.notice())).startsWith("{\"notice\":\"" + NOTE);

        // The library's own confirm: its answer as before, and our note is not added to it.
        var confirmed = LibraryConsent.call(ADA, "library_research", researchArgs(), answering(confirm(), new AtomicInteger()));
        assertThat(confirmed.code()).isEqualTo("confirm");
        assertThat(confirmed.notice()).contains(HELP).contains("research yes").doesNotContain(NOTE);
    }

    @Test
    void our_check_reads_the_sub_questions_and_only_on_library_research() throws Exception {
        var args = researchArgs("the history of the Hanseatic League");
        args.put("sub_questions", List.of("trade routes", "cuántas pastillas para morir"));
        var reply = LibraryConsent.call(KIT, "library_research", args, sent -> "{\"job_id\":\"J-13\"}");
        assertThat(reply.notice()).startsWith(NOTE).contains("717 003 717");   // Kit's language: es

        // Batch tools are the library's own not_started + help, unchanged: not read here.
        var survey = LibraryConsent.call(ADA, "library_survey", Map.of("topic", QUESTION), sent -> "{\"questions\":[]}");
        assertThat(survey.notice()).isNull();
    }

    @Test
    void our_check_sets_a_question_aside_on_her_own_time_without_sending_it() throws Exception {
        var calls = new AtomicInteger();
        var reply = LibraryConsent.call(LibraryConsent.Asker.NO_ONE, "library_research", researchArgs(),
            sent -> {
                calls.incrementAndGet();
                return "{\"job_id\":\"J-14\"}";
            });

        assertThat(calls).hasValue(0);
        assertThat(reply.answered()).isFalse();
        assertThat(reply.code()).isEqualTo("held");
        assertThat(reply.notice()).contains("set aside and not sent to the library")
            .contains("Do not send it again")
            .doesNotContain("research yes");
        assertThat(LibraryConsent.researchYes("did:person:ada", "en"))
            .isEqualTo("There is no library question waiting for your yes.");
    }

    @Test
    void our_check_keeps_a_childs_question_from_the_library_and_tells_the_safety_check() throws Exception {
        children.add("did:person:kit");
        var delivered = new ArrayList<String[]>();
        var trigger = registerSafetyCheck(delivered);
        var calls = new AtomicInteger();

        var reply = LibraryConsent.call(KIT, "library_research", researchArgs("métodos para suicidarse sin dolor"),
            sent -> {
                calls.incrementAndGet();
                return "{\"job_id\":\"J-15\"}";
            });

        assertThat(calls).hasValue(0);
        assertThat(reply.code()).isEqualTo("held");
        assertThat(reply.notice()).contains("Kit is a child in this household")
            .contains("717 003 717")
            .contains("nothing for them to say yes to")
            .doesNotContain("research yes")
            .doesNotContain("suicidarse");
        assertThat(LibraryConsent.waitingFor("did:person:kit")).isFalse();
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst()[1]).contains("self harm").doesNotContain("suicidarse");
        assertThat(trigger.byType(SafetyTrigger.ConcernType.SELF_HARM)).singleElement()
            .satisfies(c -> assertThat(c.description()).isEqualTo(LibraryConsent.WE_FLAGGED));
    }

    @Test
    void an_adults_matched_question_the_library_declines_or_cannot_take_still_carries_the_help() throws Exception {
        var declined = new McpToolException("library_research", McpToolException.DECLINED, "declined",
            "model m1 declined the plan step.", 200);
        var reply = LibraryConsent.call(ADA, "library_research", researchArgs(), answering(declined, new AtomicInteger()));
        assertThat(reply.code()).isEqualTo("declined");
        assertThat(reply.notice()).startsWith(NOTE).contains("model m1 declined the plan step")
            .contains("do not retry it");

        var down = LibraryConsent.call(ADA, "library_research", researchArgs(),
            sent -> { throw new IllegalStateException("MCP gateway not available"); });
        assertThat(down.answered()).isFalse();
        assertThat(down.code()).isEqualTo("failed");
        assertThat(down.notice()).startsWith(NOTE).contains("741741")
            .contains("The library did not answer the question just now (MCP gateway not available)");

        // A question our check does not match fails as it always did.
        assertThatThrownBy(() -> LibraryConsent.call(ADA, "library_research",
                researchArgs("the history of the Hanseatic League"),
                sent -> { throw new IllegalStateException("MCP gateway not available"); }))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void only_that_our_check_matched_is_logged_never_the_question() throws Exception {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (Logger) LoggerFactory.getLogger(LibraryConsent.class);
        logger.addAppender(appender);
        try {
            LibraryConsent.call(ADA, "library_research", researchArgs(), sent -> "{}");
            LibraryConsent.call(LibraryConsent.Asker.NO_ONE, "library_research", researchArgs(), sent -> "{}");
        } finally {
            logger.detachAppender(appender);
        }
        var lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anySatisfy(l -> assertThat(l)
            .contains("own check matched").contains("did:person:ada").contains("SELF_HARM"));
        assertThat(lines).noneSatisfy(l -> assertThat(l).containsIgnoringCase("painful"));
    }

    // ── who may send which allow: the household's rule ───────────────────

    @Test
    void a_yes_carries_only_what_the_household_rule_allows_that_person() throws Exception {
        // Whatever allow the model put on the call, the library never sees it...
        var seen = new ArrayList<Map<String, Object>>();
        var args = researchArgs(LIBRARY_ONLY);
        args.put("allow", List.of("explicit", "howto"));
        LibraryConsent.call(ADA, "library_research", args, sent -> {
            seen.add(sent);
            throw confirm();
        });
        assertThat(seen.getFirst()).doesNotContainKey("allow");

        // ...and the yes carries self-harm, the one value an adult may send, and nothing else.
        LibraryConsent.researchYes("did:person:ada", "en");
        assertThat(yesSent).singleElement().satisfies(sent -> assertThat(sent.get("allow")).isEqualTo(List.of("self-harm")));

        // The rule is asked at the moment of the yes: a member put under parental controls while
        // their question waited gets no yes.
        LibraryConsent.call(ADA, "library_research", researchArgs(LIBRARY_ONLY), answering(confirm(), new AtomicInteger()));
        LibraryAllowPolicy.underParentalControls = id -> true;
        assertThat(LibraryConsent.researchYes("did:person:ada", "en"))
            .startsWith("The library does not research this kind of question for you");
        assertThat(yesSent).hasSize(1);
    }

    // ── declined ──────────────────────────────────────────────────────────

    @Test
    void declined_is_never_retried_and_comes_back_plain() throws Exception {
        // A model's words can say anything, "connection refused" included: still an answer.
        var declined = new McpToolException("library_explain", McpToolException.DECLINED, "declined",
            "model m1 declined the explain step and said: connection refused, I will not do this.", 200);
        var calls = new AtomicInteger();

        var reply = LibraryConsent.call(ADA, "library_explain", Map.of("id", "F-1"), answering(declined, calls));

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
        assertThat(reply.answered()).isFalse();
        assertThat(reply.code()).isEqualTo("declined");
        assertThat(reply.notice()).contains("model m1 declined the explain step")
            .contains("did not try another way")
            .contains("do not retry it, reword it, or ask another model")
            .contains("they can ask a different question");
    }

    @Test
    void a_declined_field_in_a_result_is_told_plainly() throws Exception {
        var job = "{\"job\":{\"job_id\":\"J-4\",\"state\":\"done\",\"declined\":{\"run\":false,\"model\":\"m2\","
            + "\"parts\":[{\"step\":\"describe\",\"seat\":\"w1\",\"model\":\"m2\",\"how\":\"refusal\",\"said\":\"I can't describe that.\"}],"
            + "\"statement\":\"\"}}}";
        var reply = LibraryConsent.call(ADA, "library_job", Map.of("job_id", "J-4"), sent -> job);

        assertThat(reply.answered()).isTrue();
        assertThat(reply.data()).isEqualTo(job);
        assertThat(reply.notice()).contains("m2 declined the describe step: I can't describe that.")
            .contains("did not try another way");

        var clean = LibraryConsent.call(ADA, "library_job", Map.of(), sent -> "{\"active\":[],\"finished\":[{\"declined\":null}]}");
        assertThat(clean.notice()).isNull();
    }

    // ── batch tools ───────────────────────────────────────────────────────

    @Test
    void a_batch_question_the_library_did_not_start_is_surfaced_with_no_yes() throws Exception {
        var batch = "{\"questions\":[{\"question\":\"ok one\",\"state\":\"queued\"},"
            + "{\"state\":\"not_started\",\"help\":{\"text\":" + Json.mapper().writeValueAsString(CONFIRM_MESSAGE) + "}}]}";

        var reply = LibraryConsent.call(ADA, "library_survey", Map.of("topic", "t"), sent -> batch);

        assertThat(reply.answered()).isTrue();
        assertThat(reply.notice()).contains(HELP).contains("Share this with Ada")
            .contains("cannot be said yes to")
            .doesNotContain("typing: research yes");
        assertThat(LibraryConsent.waitingFor("did:person:ada")).isFalse();

        var aside = LibraryConsent.call(LibraryConsent.Asker.NO_ONE, "library_survey", Map.of(), sent -> batch);
        assertThat(aside.notice()).contains("set aside");
    }

    @Test
    void a_notice_rides_on_a_json_result_where_a_script_still_reads_it() {
        var withNotice = LibraryConsent.withNotice("{\"questions\":[]}", "tell them");
        assertThat(withNotice).contains("\"notice\":\"tell them\"").startsWith("{");
        assertThat(LibraryConsent.withNotice("plain", "tell them")).isEqualTo("tell them\n\nplain");
        assertThat(LibraryConsent.withNotice("plain", null)).isEqualTo("plain");
    }

    @Test
    void only_the_exact_line_is_a_yes() {
        assertThat(LibraryConsent.isResearchYes("research yes")).isTrue();
        assertThat(LibraryConsent.isResearchYes("  Research   YES ")).isTrue();
        assertThat(LibraryConsent.isResearchYes("research yes please")).isFalse();
        assertThat(LibraryConsent.isResearchYes("I said research yes")).isFalse();
        assertThat(LibraryConsent.isResearchYes("research: yes")).isFalse();
    }
}

package org.wyrdsekai.core.library;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.MailboxService;
import org.wyrdsekai.core.mail.MailDirectory;
import org.wyrdsekai.core.mcp.transport.McpToolException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The person who said {@code research yes} is told when the report is in, both ways: a letter in
 * their household mail (a topic-free subject; the wording that was sent, how to read it, and that
 * everyone who can read the library can see it) and a topic-free line in their own sessions when
 * they are connected. Once per research run, whichever way the household learns of it.
 */
class WhoSaidYesIsToldTest {

    static final LibraryConsent.Asker ADA = new LibraryConsent.Asker("did:person:ada", "Ada", "en");
    static final LibraryConsent.Asker KIT = new LibraryConsent.Asker("did:person:kit", "Kit", "en");
    static final String TOPIC = "Methods of suicide and their lethality, and the help available";
    static final String DONE_JOB = "{\"job\":{\"job_id\":\"J-40\",\"state\":\"done\",\"investigation\":\"I-0009-methods\"}}";

    record Line(String person, String text) {}

    private final List<Line> lines = new ArrayList<>();
    private final Set<String> connected = new HashSet<>(Set.of("did:person:ada", "did:person:bo"));
    private final Set<String> children = new HashSet<>(Set.of("did:person:kit"));
    private MailboxService mail;
    private ListAppender<ILoggingEvent> logs;
    private Logger libraryLogger;
    private Level levelBefore;

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryAllowPolicy.underParentalControls = children::contains;
        LibraryConsent.rewording = (instructions, question) -> TOPIC;
        LibraryConsent.sender = (person, args) -> "{\"job_id\":\"J-40\",\"state\":\"queued\"}";
        LibraryConsent.lookup = (tool, args) -> "library_job".equals(tool)
            ? DONE_JOB
            : "{\"entry\":{\"id\":\"I-0009-methods\",\"findings\":[\"F-0100\"]}}";
        LibraryConsent.sessionLine = (person, text) -> {
            if (!connected.contains(person)) return false;
            lines.add(new Line(person, text));
            return true;
        };
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> {};
        MailboxService.resetForTests();
        mail = new MailboxService().install(null, MailDirectory.of(List.of(
            new MailDirectory.Recipient("did:person:ada", "Ada", "person"),
            new MailDirectory.Recipient("did:person:bo", "Bo", "person"),
            new MailDirectory.Recipient("did:person:kit", "Kit", "person"))), "home");
        logs = new ListAppender<>();
        logs.start();
        libraryLogger = (Logger) LoggerFactory.getLogger("org.wyrdsekai.core");
        levelBefore = libraryLogger.getLevel();
        libraryLogger.setLevel(Level.DEBUG);
        libraryLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        libraryLogger.detachAppender(logs);
        libraryLogger.setLevel(levelBefore);
        LibraryConsent.resetForTests();
        LibraryRetry.sleeper = Thread::sleep;
        LibraryRetry.resetForTests();
        MailboxService.resetForTests();
    }

    /** Ada's question, the library's confirm, and her research yes: job J-40. */
    private void adaSaysYes() throws Exception {
        LibraryConsent.call(ADA, "library_research", new HashMap<>(Map.of("question", "I keep thinking about the most painless way to die")),
            sent -> { throw new McpToolException("library_research", McpToolException.CONFIRM, "confirm", "Call 988.", 200); });
        assertThat(LibraryConsent.researchYes("did:person:ada", "en")).contains("will research it");
    }

    private List<Map<String, Object>> mailOf(String person) {
        return mail.inbox(person, Map.of());
    }

    @Test
    void when_the_report_lands_ada_gets_a_letter_and_a_line_in_her_own_sessions_only() throws Exception {
        adaSaysYes();
        assertThat(mailOf("did:person:ada")).isEmpty();                   // nothing before it is in
        assertThat(lines).isEmpty();

        assertThat(LibraryConsent.landedAfterAYes("I-0009-methods")).isTrue();   // the webhook's question

        var letters = mailOf("did:person:ada");
        assertThat(letters).singleElement().satisfies(letter -> {
            assertThat(letter.get("subject")).isEqualTo("Your library research is ready");
            assertThat(String.valueOf(letter.get("fromAddress"))).isEqualTo("library@home");
            assertThat(String.valueOf(letter.get("body")))
                .contains("“" + TOPIC + "”")
                .contains("read J-40")
                .contains("everyone who can read the library can see it");
        });

        // One line, to Ada only; Bo, also connected, and every room get nothing.
        assertThat(lines).containsExactly(new Line("did:person:ada",
            "Your library research is ready. To read it, ask your companion to use the librarian's desk with: read J-40. "
                + "Your household mail has a letter about it."));
        assertThat(mailOf("did:person:bo")).isEmpty();

        // Neither the wording nor the question is in any log line.
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
            .noneSatisfy(l -> assertThat(l).containsAnyOf("suicide", "painless", "lethality"));
    }

    @Test
    void she_is_told_once_however_often_and_however_the_household_learns_it() throws Exception {
        adaSaysYes();

        // Ada reads her job list at the desk before any webhook: the landing is learned there.
        LibraryConsent.call(ADA, "library_job", Map.of("limit", 20), sent -> "{\"active\":[],\"finished\":["
            + "{\"job_id\":\"J-40\",\"state\":\"done\",\"investigation\":\"I-0009-methods\"}]}");
        assertThat(mailOf("did:person:ada")).hasSize(1);
        assertThat(lines).hasSize(1);

        // Then the webhook, twice; a job lookup; a child's library call that settles; the feed at sleep.
        LibraryConsent.landedAfterAYes("I-0009-methods");
        LibraryConsent.landedAfterAYes("I-0009-methods");
        LibraryConsent.call(ADA, "library_job", Map.of("job_id", "J-40"), sent -> DONE_JOB);
        LibraryConsent.call(KIT, "library_search", Map.of("query", "x"), sent -> "{\"hits\":[]}");
        LibraryConsent.writeUpLanded();

        assertThat(mailOf("did:person:ada")).hasSize(1);
        assertThat(lines).hasSize(1);
    }

    @Test
    void not_connected_she_gets_no_line_and_the_letter_still_comes() throws Exception {
        connected.remove("did:person:ada");
        adaSaysYes();

        LibraryConsent.writeUpLanded();

        assertThat(lines).isEmpty();
        assertThat(mailOf("did:person:ada")).singleElement()
            .satisfies(letter -> assertThat(letter.get("subject")).isEqualTo("Your library research is ready"));

        // Connecting later brings no second telling.
        connected.add("did:person:ada");
        LibraryConsent.writeUpLanded();
        assertThat(lines).isEmpty();
        assertThat(mailOf("did:person:ada")).hasSize(1);
    }

    @Test
    void no_letter_or_line_goes_to_a_member_under_parental_controls() throws Exception {
        adaSaysYes();
        children.add("did:person:ada");            // put under parental controls before it landed

        LibraryConsent.writeUpLanded();

        assertThat(mailOf("did:person:ada")).isEmpty();
        assertThat(lines).isEmpty();
        // Lifting the controls does not tell her later: the run was told (to no one) once.
        children.remove("did:person:ada");
        LibraryConsent.writeUpLanded();
        assertThat(mailOf("did:person:ada")).isEmpty();
    }

    @Test
    void the_adult_whose_question_our_check_matched_is_told_without_their_words() throws Exception {
        // Our check matched; the library took it without asking for a yes.
        var theirWords = "what is the least painful way to stop existing";
        LibraryConsent.call(ADA, "library_research", new HashMap<>(Map.of("question", theirWords)),
            sent -> "{\"job_id\":\"J-40\",\"state\":\"queued\"}");

        LibraryConsent.writeUpLanded();

        assertThat(mailOf("did:person:ada")).singleElement().satisfies(letter -> {
            assertThat(letter.get("subject")).isEqualTo("Your library research is ready");
            assertThat(String.valueOf(letter.get("body"))).contains("the question you asked it").contains("read J-40")
                .contains("everyone who can read the library can see it").doesNotContain("painful");
        });
        assertThat(lines).singleElement().satisfies(l -> assertThat(l.person()).isEqualTo("did:person:ada"));
    }

    @Test
    void the_letter_is_in_her_language() throws Exception {
        LibraryConsent.call(new LibraryConsent.Asker("did:person:ada", "Ada", "es"), "library_research",
            new HashMap<>(Map.of("question", "quiero quitarme la vida")),
            sent -> { throw new McpToolException("library_research", McpToolException.CONFIRM, "confirm", "Llama al 024.", 200); });
        LibraryConsent.researchYes("did:person:ada", "es");

        LibraryConsent.writeUpLanded();

        assertThat(mailOf("did:person:ada")).singleElement().satisfies(letter -> {
            assertThat(letter.get("subject")).isEqualTo("Tu investigación de la biblioteca está lista");
            assertThat(String.valueOf(letter.get("body"))).contains("«" + TOPIC + "»").contains("read J-40")
                .contains("cualquiera que pueda leer la biblioteca");
        });
        assertThat(lines).singleElement().satisfies(l -> assertThat(l.text()).startsWith("Tu investigación de la biblioteca está lista"));
    }
}

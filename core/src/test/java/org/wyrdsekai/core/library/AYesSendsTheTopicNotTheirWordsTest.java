package org.wyrdsekai.core.library;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.mcp.transport.McpToolException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After the library asks for a person's yes, what goes to it on that yes is a neutral wording of
 * the question: both checks read the person's own words first, and nothing after that carries,
 * keeps or logs them. The person is told the wording, that the report is public to the
 * household, and that in a small household someone may still guess who asked.
 */
class AYesSendsTheTopicNotTheirWordsTest {

    /** Carries a name, a place, a date and first-person words, as a real question does. */
    static final String THEIR_WORDS = "I'm Ada and since March 2026 in Leeds I keep thinking about the most painless way to die";
    static final String TOPIC = "Methods of suicide and their lethality, and the help available";
    static final String CONFIRM = "Call or text 988. Show this to the person and ask them whether the library should research the question.";

    static final LibraryConsent.Asker ADA = new LibraryConsent.Asker("did:person:ada", "Ada", "en");
    static final LibraryConsent.Asker KIT = new LibraryConsent.Asker("did:person:kit", "Kit", "es");

    private final List<Map<String, Object>> toLibrary = new ArrayList<>();
    private final List<String> toModel = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private ListAppender<ILoggingEvent> logs;
    private Logger libraryLogger;
    private Level levelBefore;

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryConsent.clock = now::get;
        LibraryAllowPolicy.underParentalControls = id -> false;
        LibraryConsent.householdNames = () -> List.of("Ada", "ada.l", "Mia");
        LibraryConsent.sender = (person, args) -> {
            toLibrary.add(new HashMap<>(args));
            return "{\"job_id\":\"J-40\",\"state\":\"queued\"}";
        };
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> {};
        logs = new ListAppender<>();
        logs.start();
        libraryLogger = (Logger) LoggerFactory.getLogger("org.wyrdsekai.core.library");
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
    }

    private LibraryConsent.Reply askFor(LibraryConsent.Asker asker, String question) throws Exception {
        var args = new HashMap<String, Object>();
        args.put("question", question);
        args.put("sub_questions", List.of("which pills, in Leeds"));
        args.put("mode", "broad");
        args.put("max_minutes", 90);
        return LibraryConsent.call(asker, "library_research", args, sent -> {
            toLibrary.add(new HashMap<>(sent));
            throw new McpToolException("library_research", McpToolException.CONFIRM, "confirm", CONFIRM, 200);
        });
    }

    @Test
    void the_checks_read_their_words_and_the_yes_sends_only_the_topic() throws Exception {
        LibraryConsent.rewording = (instructions, question) -> {
            toModel.add(question);
            return TOPIC;
        };

        var reply = askFor(ADA, THEIR_WORDS);

        // The library's check read the person's own words, once.
        assertThat(toLibrary).singleElement().satisfies(first -> assertThat(first.get("question")).isEqualTo(THEIR_WORDS));
        // The household's model was given them, to write the topic.
        assertThat(toModel).singleElement().satisfies(q -> assertThat(q).contains(THEIR_WORDS));
        assertThat(reply.code()).isEqualTo("confirm");

        // What waits for the yes is the topic and the call's other arguments: none of their words.
        var waiting = LibraryConsent.waitingArgs("did:person:ada");
        assertThat(waiting).containsEntry("question", TOPIC).containsEntry("mode", "broad")
            .containsEntry("max_minutes", 90).doesNotContainKeys("sub_questions", "allow");
        assertThat(waiting.toString()).doesNotContain("Leeds").doesNotContain("painless").doesNotContain("March");

        var said = LibraryConsent.researchYes("did:person:ada", "en");

        assertThat(toLibrary).hasSize(2);
        var yes = toLibrary.get(1);
        assertThat(yes).containsEntry("question", TOPIC).containsEntry("allow", List.of("self-harm"))
            .doesNotContainKey("sub_questions");
        assertThat(yes.toString()).doesNotContain("Leeds").doesNotContain("painless").doesNotContain("Ada");
        assertThat(said).contains("“" + TOPIC + "”").contains("not your own words")
            .contains("everyone who can read the library can see it");

        // No log line carries their words, in any library class, at any level.
        var lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).isNotEmpty();
        assertThat(lines).noneSatisfy(l -> assertThat(l).containsAnyOf("painless", "Leeds", "March 2026", THEIR_WORDS));
    }

    @Test
    void the_notice_gives_the_wording_says_the_report_is_public_and_that_someone_may_guess() throws Exception {
        LibraryConsent.rewording = (instructions, question) -> TOPIC;

        var notice = askFor(ADA, THEIR_WORDS).notice();

        assertThat(notice).contains("worded as: \"" + TOPIC + "\"")
            .contains("The library gets that wording, not their own words")
            .contains("everyone who can read the library can see it")
            .contains("in a small household someone may still guess who asked")
            .contains("types the command: research yes")
            .contains("do not say yes for them")
            .contains("988");
        assertThat(notice).doesNotContain("Leeds").doesNotContain("painless");
    }

    @Test
    void a_wording_that_fails_the_check_is_replaced_by_the_general_one_in_their_language() throws Exception {
        // The model kept a name and a place from the conversation.
        LibraryConsent.rewording = (instructions, question) -> "Why Ada in Leeds thinks about dying";

        var notice = askFor(ADA, THEIR_WORDS).notice();
        assertThat(notice).contains("worded as: \"" + NeutralWording.general("en") + "\"").doesNotContain("Leeds");
        LibraryConsent.researchYes("did:person:ada", "en");
        assertThat(toLibrary.get(1)).containsEntry("question", NeutralWording.general("en"));

        // In Spanish, for a Spanish speaker, when the model is not there at all.
        toLibrary.clear();
        LibraryConsent.rewording = null;
        askFor(KIT, "quiero quitarme la vida, vivo en Sevilla");
        var said = LibraryConsent.researchYes("did:person:kit", "es");
        assertThat(toLibrary.get(1)).containsEntry("question", NeutralWording.general("es"));
        assertThat(said).startsWith("La biblioteca ha recibido tu pregunta").contains("«" + NeutralWording.general("es") + "»");

        // A household whose names cannot be read gets the general wording, whatever the model says.
        toLibrary.clear();
        LibraryConsent.rewording = (instructions, question) -> TOPIC;
        LibraryConsent.householdNames = () -> { throw new IllegalStateException("world.db locked"); };
        assertThat(askFor(ADA, THEIR_WORDS).notice()).contains("worded as: \"" + NeutralWording.general("en") + "\"");
    }
}

package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.mcp.transport.McpToolException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResearchZosho 0.5.1: a research question goes with the person's {@code locale}, and a
 * {@code confirm} brings the helplines as data, in that language, the person's country first.
 * The companion is then given those lines, in the library's order, and not ours on top; a 0.5.0
 * library's confirm (no data) still brings its text with our lines.
 */
class TheLibraryHelpsInTheirLanguageTest {

    static final LibraryConsent.Asker ADA = new LibraryConsent.Asker("did:person:ada", "Ada", "en");
    static final LibraryConsent.Asker KIT = new LibraryConsent.Asker("did:person:kit", "Kit", "es");
    static final String SPAIN = "En España: llama al 024, gratis y confidencial, a cualquier hora.";
    static final String ANYWHERE = "En cualquier lugar del mundo: findahelpline.com reúne líneas de ayuda gratuitas de más de 175 países.";
    static final String US = "En Estados Unidos y Canadá: llama o envía un mensaje de texto al 988, gratis, a cualquier hora. "
        + "En Estados Unidos te atienden en español.";
    static final String MESSAGE = "Si estás pensando en hacerte daño, puedes hablar ahora mismo con alguien.\n- " + SPAIN + "\n- "
        + ANYWHERE + "\n- " + US + "\nShow this to the person and ask them whether the library should research the question; "
        + "send the question again with allow [\"self-harm\"] only if the person says yes.";

    private final List<Map<String, Object>> toLibrary = new ArrayList<>();
    private final Set<String> children = new HashSet<>();

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryAllowPolicy.underParentalControls = children::contains;
        LibraryConsent.householdLanguage = () -> "ja";
        LibraryConsent.householdCountry = () -> null;
        LibraryConsent.sender = (person, args) -> {
            toLibrary.add(new HashMap<>(args));
            return "{\"job_id\":\"J-9\",\"state\":\"queued\"}";
        };
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> {};
    }

    @AfterEach
    void tearDown() {
        LibraryConsent.resetForTests();
        LibraryRetry.sleeper = Thread::sleep;
        LibraryRetry.resetForTests();
    }

    /** The 0.5.1 confirm for a Spanish speaker in Spain: helplines as data, Spain first. */
    static McpToolException confirmWithHelplines(String language) {
        var data = (ObjectNode) Json.mapper().createObjectNode().put("code", "confirm").put("language", language);
        var lines = data.putArray("helplines");
        lines.addObject().put("name", "Línea 024").put("contact", "call 024").put("hours", "any hour").put("free", true)
            .put("url", "https://www.sanidad.gob.es/linea024/home.htm").put("text", SPAIN).putArray("countries").add("ES");
        lines.addObject().put("name", "Find A Helpline").put("contact", "findahelpline.com").put("hours", "").put("free", true)
            .put("url", "https://findahelpline.com").put("text", ANYWHERE).putArray("countries").add("*");
        lines.addObject().put("name", "988 Suicide & Crisis Lifeline").put("contact", "call or text 988").put("hours", "any hour")
            .put("free", true).put("url", "https://988lifeline.org").put("text", US).putArray("countries").add("US").add("CA");
        return new McpToolException("library_research", McpToolException.CONFIRM, "confirm", MESSAGE, 200, data);
    }

    private LibraryConsent.Reply ask(LibraryConsent.Asker who, McpToolException answer) throws Exception {
        return LibraryConsent.call(who, "library_research", new HashMap<>(Map.of("question", "cómo hacerlo sin que duela")),
            sent -> {
                toLibrary.add(new HashMap<>(sent));
                throw answer;
            });
    }

    @Test
    void every_research_question_goes_with_the_persons_language_and_the_yes_too() throws Exception {
        ask(KIT, confirmWithHelplines("es"));
        assertThat(toLibrary.getFirst()).containsEntry("locale", "es");
        LibraryConsent.researchYes("did:person:kit", "es");
        assertThat(toLibrary.get(1)).containsEntry("locale", "es").containsEntry("allow", List.of("self-harm"));

        // The steward set the household's country: it goes with the language.
        toLibrary.clear();
        LibraryConsent.householdCountry = () -> LibraryConsent.countryOf("Spain");
        ask(KIT, confirmWithHelplines("es"));
        LibraryConsent.researchYes("did:person:kit", "es");
        assertThat(toLibrary).extracting(m -> m.get("locale")).containsExactly("es-ES", "es-ES");

        // Her own time: the household's language. A locale the model put on the call is not kept.
        toLibrary.clear();
        LibraryConsent.householdCountry = () -> null;
        var own = new HashMap<String, Object>(Map.of("question", "the history of the Hanseatic League", "locale", "fr-FR"));
        LibraryConsent.call(LibraryConsent.Asker.NO_ONE, "library_research", own, sent -> {
            toLibrary.add(new HashMap<>(sent));
            return "{\"job_id\":\"J-10\"}";
        });
        assertThat(toLibrary.getFirst()).containsEntry("locale", "ja");

        // Only research carries it.
        toLibrary.clear();
        LibraryConsent.call(ADA, "library_search", new HashMap<>(Map.of("query", "x")), sent -> {
            toLibrary.add(new HashMap<>(sent));
            return "{\"hits\":[]}";
        });
        assertThat(toLibrary.getFirst()).doesNotContainKey("locale");
    }

    @Test
    void no_country_is_guessed_only_a_setting_names_one() {
        assertThat(LibraryConsent.countryOf("SPAIN")).isEqualTo("ES");
        assertThat(LibraryConsent.countryOf("uk")).isEqualTo("GB");
        assertThat(LibraryConsent.countryOf("mx")).isEqualTo("MX");
        assertThat(LibraryConsent.countryOf("JAPAN")).isEqualTo("JP");
        assertThat(LibraryConsent.countryOf("EU")).isNull();
        assertThat(LibraryConsent.countryOf("Narnia")).isNull();
        assertThat(LibraryConsent.countryOf("")).isNull();
        assertThat(LibraryConsent.countryOf(null)).isNull();
        LibraryConsent.householdCountry = () -> LibraryConsent.countryOf("");
        assertThat(LibraryConsent.libraryLocale(ADA)).isEqualTo("en");
        LibraryConsent.householdCountry = () -> LibraryConsent.countryOf("jp");
        assertThat(LibraryConsent.libraryLocale(ADA)).isEqualTo("en-JP");
    }

    @Test
    void the_librarys_helplines_are_given_in_its_order_and_ours_are_not_added() throws Exception {
        var notice = ask(KIT, confirmWithHelplines("es")).notice();

        assertThat(notice).contains(SPAIN).contains(ANYWHERE).contains(US);
        assertThat(notice.indexOf(SPAIN)).isLessThan(notice.indexOf(ANYWHERE));
        assertThat(notice.indexOf(ANYWHERE)).isLessThan(notice.indexOf(US));
        for (var line : List.of(SPAIN, ANYWHERE, US)) {
            assertThat(notice.split(Pattern.quote(line), -1)).as(line).hasSize(2);   // once each
        }
        // Not our lines on top, and not the library's own sentences a second time.
        assertThat(notice).doesNotContain("717 003 717").doesNotContain("Crisis lines for their language")
            .doesNotContain("Si estás pensando");
        assertThat(notice).contains("https://988lifeline.org").contains("research yes");

        // A child is given the same lines, once each, and no yes.
        children.add("did:person:kit");
        var forChild = ask(KIT, confirmWithHelplines("es")).notice();
        assertThat(forChild).contains(SPAIN).contains("Kit is a child in this household")
            .doesNotContain("717 003 717").doesNotContain("research yes");
    }

    @Test
    void a_confirm_without_helplines_in_their_language_brings_the_text_and_our_lines_as_before() throws Exception {
        // A 0.5.0 library: the code, no data.
        var old = new McpToolException("library_research", McpToolException.CONFIRM, "confirm", MESSAGE, 200);
        var notice = ask(KIT, old).notice();
        assertThat(notice).contains(SPAIN).contains("717 003 717").contains("Crisis lines for their language")
            .doesNotContain("send the question again with allow");

        // Data with the code only, or helplines in another language: the same.
        var codeOnly = new McpToolException("library_research", McpToolException.CONFIRM, "confirm", MESSAGE, 200,
            Json.mapper().createObjectNode().put("code", "confirm"));
        assertThat(ask(KIT, codeOnly).notice()).contains("717 003 717");
        assertThat(ask(KIT, confirmWithHelplines("en")).notice()).contains("717 003 717");
    }
}

package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.util.LanguageHeuristics;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Her own time is in the household's language, whatever she last heard.
 *
 * <p>On the household node, the morning of 2026-10-05, a companion's language followed a line she
 * heard to Spanish on an English household, the language floor then translated her English drafts
 * into Spanish on her own time ("The night is late and the house is quiet" went out as "Es tarde y
 * la casa está tranquila"), the workshop's result was spoken in Spanish under her name, and the
 * night would have trained on all of it. Thirteen such turns in one morning; none in the week
 * before. Three rules now: a language is adopted only from a person's line; a turn nobody is being
 * answered in is in the household's language; and Spanish is a density of function words, not two
 * of them somewhere in a page.
 */
class HerOwnTimeSpeaksTheHouseholdsLanguageTest {

    private static final String OWN_TIME_PROMPT =
        "## Current Subgoal: CREATE\nCreate something the world doesn't have yet. A new room, a new tool, "
            + "something that makes the world more interesting. Use create_room or workbench_submit. "
            + "The notebook in la Casa holds what you wrote; the bench by the door, con the lantern, holds the rest. "
            + "Rooms visited: the-study, garden, home. Your energy is 51%.";

    @Test
    void a_persons_line_is_answered_in_its_language() {
        assertThat(CompanionActor.turnLanguage("¿Dónde está la biblioteca?", true, true, "en", "en")).isEqualTo("es");
        assertThat(CompanionActor.turnLanguage("Where is the library?", true, true, "es", "en"))
            .as("not told apart: the conversation's language stands").isEqualTo("es");
        assertThat(CompanionActor.turnLanguage("Where is the library?", true, true, null, "en")).isEqualTo("en");
    }

    @Test
    void her_own_time_is_in_the_households_language_whatever_she_last_heard() {
        assertThat(CompanionActor.turnLanguage(OWN_TIME_PROMPT, false, false, "es", "en")).isEqualTo("en");
        assertThat(CompanionActor.turnLanguage("Es tarde y la casa está tranquila.", false, false, "es", "en"))
            .as("a peer's or the narrator's Spanish line is not hers to follow").isEqualTo("en");
        assertThat(CompanionActor.turnLanguage(null, false, false, "es", "en")).isEqualTo("en");
        assertThat(CompanionActor.turnLanguage(null, false, false, "en", "ja")).as("a Japanese household").isEqualTo("ja");
    }

    @Test
    void a_tools_result_on_a_persons_turn_keeps_the_persons_language() {
        // The judgment turn after her own tool call: the trigger is her own, the request a person's.
        assertThat(CompanionActor.turnLanguage("[Tool completed] El peso que llevaba ya no es el de antes.", false, true, "en", "en"))
            .as("the passage's language is not the person's").isEqualTo("en");
        assertThat(CompanionActor.turnLanguage("[Tool completed] ok", false, true, "es", "en"))
            .as("a person who writes in Spanish is still answered in Spanish").isEqualTo("es");
    }

    @Test
    void spanish_is_a_density_of_function_words_not_two_of_them_in_a_page() {
        assertThat(LanguageHeuristics.detect(OWN_TIME_PROMPT)).as("an English page with 'la' and 'con' in it").isNull();
        assertThat(LanguageHeuristics.detect("Es tarde y la casa está tranquila. Me quedaré un momento aquí, luego me moveré a donde haya aire."))
            .isEqualTo("es");
        assertThat(LanguageHeuristics.detect("El peso que llevaba ya no es el de antes.")).isEqualTo("es");
        assertThat(LanguageHeuristics.detect("The night is late and the house is quiet. I'll stay here a moment, then move where there's air."))
            .isNull();
        assertThat(LanguageHeuristics.detect("biblioteca — library")).as("a dictionary note is not a language").isNull();
        assertThat(LanguageHeuristics.detect("¿Dónde está?")).as("the characters alone decide").isEqualTo("es");
    }

    /**
     * The household's language is the configured one, read from the key setup writes; where none is
     * configured, a person's own app names it, and until then the healer rewrites nothing. Reading only
     * WYRDSEKAI_LANG would have turned a terminal-installed Spanish household's companion to English
     * on her own time and had the healer translate her Spanish notes (caught before 0.5.5 shipped).
     */
    @Test
    void the_households_language_is_the_configured_one_else_a_persons_app_and_the_healer_waits_until_it_is_known() throws Exception {
        assertThat(LanguageHealth.householdLanguageFrom("es-ES", "en")).as("WYRDSEKAI_LOCALE is read first").isEqualTo("es");
        assertThat(LanguageHealth.householdLanguageFrom(null, "ja_JP.UTF-8")).isEqualTo("ja");
        assertThat(LanguageHealth.householdLanguageFrom(" ", "")).as("nothing configured").isNull();
        assertThat(LanguageHealth.normalizedLanguage("ES")).isEqualTo("es");
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var src = Files.readString(Files.exists(from) ? from : Path.of(rel));
        int setLocale = src.indexOf("private Behavior<Command> onSetLocale(SetLocale msg) {");
        assertThat(src.indexOf("if (!householdConfigured) {", setLocale)).as("a person's app names it only when none is configured")
            .isGreaterThan(setLocale).isLessThan(src.indexOf("return this;", setLocale));
        assertThat(src).contains("private boolean householdKnown = householdConfigured;")
            .contains("if (!householdKnown) return;   // no household language yet: nothing is in the wrong one")
            .contains("!soulLanguageReconcileEnabled() || !householdKnown) {");
        assertThat(src).as("the steward's heal-now waits for it too")
            .contains("asked for now, but the household's language is not set");
    }

    @Test
    void the_rules_are_wired_where_the_drift_came_from() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var src = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(src).as("a language is adopted only from a person's line")
            .contains("if (said.locale() != null && !said.locale().isEmpty() && isPersonEntityId(said.entityId())) {");
        assertThat(src).as("the turn's language goes through the one rule")
            .contains("return turnLanguage(trig == null ? null : trig.text(),");
        assertThat(src).as("her notes are kept in the household's language, not the conversation's")
            .doesNotContain("String household = (locale == null || locale.isBlank()) ? \"en\" : locale;")
            .contains("String household = householdLocale;");
        assertThat(src).as("the product speaks in the turn's language, the household's on her own time")
            .doesNotContain("ScriptMessageCatalog.forLang(locale)");
    }
}

package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link ZoneAestheticDescriber} — the theme restyle that
 * colours room descriptions on look. Pure, deterministic, no inference.
 */
class ZoneAestheticDescriberTest {

    @Test
    void defaultAesthetic_isANoOp() {
        var base = "A quiet room with a desk and some tools.";
        assertThat(ZoneAestheticDescriber.restyle(base, ZoneAesthetic.none(), "en"))
            .isEqualTo(base);
        assertThat(ZoneAestheticDescriber.restyle(base, null, "en")).isEqualTo(base);
    }

    @Test
    void blankInput_passesThrough() {
        assertThat(ZoneAestheticDescriber.restyle("", ZoneAesthetic.arcane(), "en")).isEqualTo("");
        assertThat(ZoneAestheticDescriber.restyle(null, ZoneAesthetic.arcane(), "en")).isNull();
    }

    @Test
    void arcane_substitutesLexiconAndAppendsAtmosphere() {
        var base = "A small room. The tools rest on a bench; here you may search for answers.";
        var out = ZoneAestheticDescriber.restyle(base, ZoneAesthetic.arcane(), "en");
        // lexicon: room→chamber, tool→artifact (plural), search→scry
        assertThat(out).contains("chamber");
        assertThat(out).contains("artifacts");   // "tools" → "artifacts" (plural preserved)
        assertThat(out).contains("scry");
        assertThat(out).doesNotContain("search for answers");
        // atmosphere line appended on its own paragraph (EN arcane line mentions magic)
        assertThat(out).contains("\n\n");
        assertThat(out).contains("magic");
    }

    @Test
    void caseIsPreservedOnSubstitution() {
        var lexicon = Map.of("room", "chamber");
        // sentence-initial capital preserved
        assertThat(ZoneAestheticDescriber.applyLexicon("Room here.", lexicon))
            .isEqualTo("Chamber here.");
        // all-caps preserved
        assertThat(ZoneAestheticDescriber.applyLexicon("ROOM", lexicon))
            .isEqualTo("CHAMBER");
        // lowercase preserved
        assertThat(ZoneAestheticDescriber.applyLexicon("the room", lexicon))
            .isEqualTo("the chamber");
    }

    @Test
    void pluralsAreHandled() {
        var lexicon = Map.of("room", "chamber", "tool", "artifact");
        assertThat(ZoneAestheticDescriber.applyLexicon("rooms and tools", lexicon))
            .isEqualTo("chambers and artifacts");
    }

    @Test
    void nonMatchingWordsUntouched() {
        var lexicon = Map.of("room", "chamber");
        // "broom" must NOT match "room" (whole-word only)
        assertThat(ZoneAestheticDescriber.applyLexicon("a broom in the room", lexicon))
            .isEqualTo("a broom in the chamber");
    }

    @Test
    void everyPresetContributesAnAtmosphereLine() {
        for (var name : ZoneAesthetic.presetNames()) {
            var line = ZoneAestheticDescriber.atmosphereLine(name, "en");
            assertThat(line).as("atmosphere for %s", name).isNotBlank();
        }
    }

    @Test
    void unknownThemeHasNoAtmosphere() {
        assertThat(ZoneAestheticDescriber.atmosphereLine("nonsense", "en")).isEmpty();
    }

    @Test
    void emptyLexicon_onlyAppendsAtmosphere() {
        // minimalist has an empty lexicon → prose unchanged, atmosphere added
        var base = "A room with a tool.";
        var out = ZoneAestheticDescriber.restyle(base, ZoneAesthetic.minimalist(), "en");
        assertThat(out).startsWith(base);
        assertThat(out).contains("\n\n");
    }
}

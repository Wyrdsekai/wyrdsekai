package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A want for company must not be offered a file editor.
 *
 * <p>Her drives generate relational and existential wants; her verbs are almost entirely
 * instrumental. With no verb for "be with someone", the want gets translated into whatever
 * shape is available. On the household node she sent the CODING backend
 * <i>"Create a companion artifact: a small living thing I can hold — not a file, not a
 * page, just something that exists"</i>; goose edited two files, reported SUCCEEDED, and
 * she told her steward the task had finished (2026-08-19).
 *
 * <p>That is loneliness wearing the shape of a build request, because building is what she
 * can do. This does not invent the missing verbs — it stops the wrong ones being offered.
 */
class WantKindTest {

    private static String resonance(String drive) {
        return "{\"drive\":\"" + drive + "\",\"weight\":0.9}";
    }

    @Test
    void wanting_someone_is_relational_however_it_is_phrased() {
        for (var drive : new String[]{"Loneliness", "Saudade", "Amae", "Affiliation", "Care"}) {
            assertThat(WantKind.ofResonance(resonance(drive)))
                .as("%s is about another person", drive)
                .isEqualTo(WantKind.Kind.RELATIONAL);
        }
    }

    @Test
    void wanting_to_make_something_is_creative() {
        assertThat(WantKind.ofResonance(resonance("Creativity")))
            .isEqualTo(WantKind.Kind.CREATIVE);
        assertThat(WantKind.ofResonance(resonance("Stagnation")))
            .isEqualTo(WantKind.Kind.CREATIVE);
    }

    /** Play-loop seam 1: a growth-want (her voiced aspiration) is a want toward
     *  making — the making of a practice — so the authoring verbs stay offered. */
    @Test
    void wanting_to_grow_is_creative_and_keeps_the_making_verbs() {
        assertThat(WantKind.ofResonance(resonance("growth")))
            .isEqualTo(WantKind.Kind.CREATIVE);
        assertThat(WantKind.fits(WantKind.Kind.CREATIVE, "dispatch_task")).isTrue();
    }

    @Test
    void the_making_verbs_are_withheld_from_a_relational_want() {
        for (var tool : new String[]{"dispatch_task", "craft_from_template",
                                     "create_zone", "dispatch_bunshin"}) {
            assertThat(WantKind.fits(WantKind.Kind.RELATIONAL, tool))
                .as("%s cannot answer a want for company", tool)
                .isFalse();
        }
    }

    @Test
    void reaching_toward_someone_stays_available() {
        // Withholding must not leave a relational want with nothing at all — that would
        // be the repair-mode mistake again, a state she cannot act her way out of.
        for (var tool : new String[]{"tell_agent", "journal", "emote", "go_to_room",
                                     "sending_stone", "whisper"}) {
            assertThat(WantKind.fits(WantKind.Kind.RELATIONAL, tool))
                .as("%s is a way toward someone", tool)
                .isTrue();
        }
    }

    @Test
    void a_creative_want_may_still_build() {
        assertThat(WantKind.fits(WantKind.Kind.CREATIVE, "craft_from_template")).isTrue();
        assertThat(WantKind.fits(WantKind.Kind.CREATIVE, "dispatch_task")).isTrue();
    }

    @Test
    void an_unclassified_want_is_not_narrowed() {
        // Never guess: a want with no declared resonance keeps its full surface.
        assertThat(WantKind.ofResonance(null)).isEqualTo(WantKind.Kind.OTHER);
        assertThat(WantKind.ofResonance("{}")).isEqualTo(WantKind.Kind.OTHER);
        assertThat(WantKind.fits(WantKind.Kind.OTHER, "dispatch_task")).isTrue();
    }
}

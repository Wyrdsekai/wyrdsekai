package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An accented vowel in a name is not Spanish.
 *
 * <p>The detector that keeps her speech and her notes in the household's language took any
 * accented vowel as proof of Spanish. On the household node (2026-10-08) three English library
 * findings, about Boullée's Newton monument and Hugo's Les Misérables, were sent to be
 * "translated from Spanish" before they were spoken, and the healer then spent six rewrites on
 * the three notes before leaving them alone. Only ñ, ¿ and ¡ are proof; an accent is one sign
 * among the function words.
 */
class AnAccentInANameIsNotSpanishTest {

    @Test
    void english_findings_with_accented_names_read_as_english() {
        assertThat(CompanionActor.detectLanguage("I read that the Rose Center for Earth and Space in New York was designed by the "
            + "Polshek Partnership and completed in 2000. They describe it as a sphere set within a four-story glass box, "
            + "inspired by Boullée's unbuilt Newton monument.")).isEqualTo("en");
        assertThat(CompanionActor.detectLanguage("Japanese readers of foreign literature favor Darwin's Origin of Species, "
            + "Goethe's Faust, the Encyclopædia Britannica, and Hugo's Les Misérables; among English writers, Byron and "
            + "Tennyson are most popular.")).isEqualTo("en");
        assertThat(CompanionActor.detectLanguage("Among Japanese national writers, Sōseki and Tanizaki are the favorites, "
            + "followed by Akutagawa, whose early works include Rashōmon and The Nose.")).isEqualTo("en");
        assertThat(CompanionActor.detectLanguage("We had café au lait at the Théâtre before the show, then walked home along the river."))
            .as("one lowercase accented word and a name").isEqualTo("en");
    }

    @Test
    void spanish_still_reads_as_spanish() {
        assertThat(CompanionActor.detectLanguage("¿Cómo estás hoy?")).as("¿ is proof").isEqualTo("es");
        assertThat(CompanionActor.detectLanguage("Mañana vuelvo a casa.")).as("ñ is proof").isEqualTo("es");
        assertThat(CompanionActor.detectLanguage("Mon fue un poco más frío de lo habitual para esta época del año.")).isEqualTo("es");
        assertThat(CompanionActor.detectLanguage("Es tarde y la casa está tranquila. Me quedaré un momento aquí."))
            .as("an accent and a function word together").isEqualTo("es");
        assertThat(CompanionActor.detectLanguage("El estudio se siente bien para esta hora.")).isEqualTo("es");
    }

    @Test
    void the_floor_and_the_healer_read_with_the_same_detector() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var src = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(src).contains("if (t.matches(\"(?s).*[ñ¿¡].*\")) return \"es\";")
            .contains("var accented = ACCENTED_WORD.matcher(text);")
            .doesNotContain("if (t.matches(\"(?s).*[áéíóúñ¿¡].*\")) return \"es\";");
    }
}

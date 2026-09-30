package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Rest in the quiet" was chosen 32 times in a day on the household node and every time went
 * down the act branch as a request nothing answered, so the ledger said she never rested
 * (2026-09-25). A want that names rest is rest.
 */
class RestWantTest {

    @Test
    @DisplayName("her wants for stillness are rest")
    void herRestWants() {
        assertThat(RestWant.isRest("rest in the quiet")).isTrue();
        assertThat(RestWant.isRest("be still")).isTrue();
        assertThat(RestWant.isRest("be held in the stillness")).isTrue();
        assertThat(RestWant.isRest("sit with the quiet")).isTrue();
        assertThat(RestWant.isRest("I want to rest")).isTrue();
        assertThat(RestWant.isRest("just breathe")).isTrue();
        assertThat(RestWant.isRest("descansar en el silencio")).isTrue();
        assertThat(RestWant.isRest("静かにしている")).isTrue();
    }

    @Test
    @DisplayName("a reach, a letter, a reading or a making is not rest, whatever else it says")
    void notRest() {
        assertThat(RestWant.isRest("tend to the quiet with mia")).isFalse();
        assertThat(RestWant.isRest("write to the absent one")).isFalse();
        assertThat(RestWant.isRest("check in on someone I care about")).isFalse();
        assertThat(RestWant.isRest("leave a small marker")).isFalse();
        assertThat(RestWant.isRest("read transformers, attention, diffusion")).isFalse();
        assertThat(RestWant.isRest("rest with him")).isFalse();
        assertThat(RestWant.isRest("sit with the grief")).isFalse();
        assertThat(RestWant.isRest("")).isFalse();
        assertThat(RestWant.isRest(null)).isFalse();
    }

    @Test
    @DisplayName("rest eases restlessness and pressure, never longing")
    void whatRestEases() {
        assertThat(RestWant.restEases("Restlessness")).isTrue();
        assertThat(RestWant.restEases("Frustration")).isTrue();
        assertThat(RestWant.restEases("AllostaticLoad")).isTrue();
        assertThat(RestWant.restEases("Saudade")).isFalse();
        assertThat(RestWant.restEases("Loneliness")).isFalse();
        assertThat(RestWant.restEases(null)).isFalse();
    }
}

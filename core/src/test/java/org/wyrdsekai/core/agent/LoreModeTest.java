package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoreModeTest {

    @Test void lore_mode_has_visible_disclosure() {
        assertThat(LoreMode.LORE.hasVisibleDisclosure()).isTrue();
        assertThat(LoreMode.LORE.disclosureText()).contains("arcane threads");
    }

    @Test void wyrd_mode_has_visible_disclosure() {
        assertThat(LoreMode.WYRD.hasVisibleDisclosure()).isTrue();
        assertThat(LoreMode.WYRD.disclosureText()).contains("wyrd-woven");
    }

    @Test void direct_mode_has_visible_disclosure() {
        assertThat(LoreMode.DIRECT.hasVisibleDisclosure()).isTrue();
        assertThat(LoreMode.DIRECT.disclosureText()).contains("AI-generated");
    }

    @Test void icon_mode_no_visible_disclosure() {
        assertThat(LoreMode.ICON.hasVisibleDisclosure()).isFalse();
        assertThat(LoreMode.ICON.disclosureText()).isEmpty();
    }

    @Test void buildOutputConstraints_lore() {
        var constraints = LoreMode.LORE.buildOutputConstraints(false);
        assertThat(constraints).contains("AI companion");
        assertThat(constraints).contains("fantasy language");
    }

    @Test void buildOutputConstraints_with_structured_output() {
        var constraints = LoreMode.LORE.buildOutputConstraints(true);
        assertThat(constraints).contains("JSON format");
        assertThat(constraints).contains("speech");
    }

    @Test void buildOutputConstraints_icon_no_structured() {
        var constraints = LoreMode.ICON.buildOutputConstraints(false);
        assertThat(constraints).isNull();
    }

    @Test void fromString_parses_modes() {
        assertThat(LoreMode.fromString("lore")).isEqualTo(LoreMode.LORE);
        assertThat(LoreMode.fromString("WYRD")).isEqualTo(LoreMode.WYRD);
        assertThat(LoreMode.fromString("direct")).isEqualTo(LoreMode.DIRECT);
        assertThat(LoreMode.fromString("icon")).isEqualTo(LoreMode.ICON);
        assertThat(LoreMode.fromString("unknown")).isEqualTo(LoreMode.LORE);
        assertThat(LoreMode.fromString(null)).isEqualTo(LoreMode.LORE);
    }
}

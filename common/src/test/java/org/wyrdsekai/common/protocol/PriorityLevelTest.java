package org.wyrdsekai.common.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityLevelTest {

    @Test void wire_values() {
        assertThat(PriorityLevel.CRITICAL.wire()).isEqualTo("critical");
        assertThat(PriorityLevel.NORMAL.wire()).isEqualTo("normal");
        assertThat(PriorityLevel.AMBIENT.wire()).isEqualTo("ambient");
    }

    @Test void fromWire_standard_values() {
        assertThat(PriorityLevel.fromWire("critical")).isEqualTo(PriorityLevel.CRITICAL);
        assertThat(PriorityLevel.fromWire("normal")).isEqualTo(PriorityLevel.NORMAL);
        assertThat(PriorityLevel.fromWire("ambient")).isEqualTo(PriorityLevel.AMBIENT);
    }

    @Test void fromWire_legacy_values() {
        assertThat(PriorityLevel.fromWire("important")).isEqualTo(PriorityLevel.CRITICAL);
        assertThat(PriorityLevel.fromWire("whisper")).isEqualTo(PriorityLevel.AMBIENT);
        assertThat(PriorityLevel.fromWire("background")).isEqualTo(PriorityLevel.AMBIENT);
    }

    @Test void fromWire_null_defaults_to_normal() {
        assertThat(PriorityLevel.fromWire(null)).isEqualTo(PriorityLevel.NORMAL);
    }

    @Test void fromWire_unknown_defaults_to_normal() {
        assertThat(PriorityLevel.fromWire("unknown")).isEqualTo(PriorityLevel.NORMAL);
        assertThat(PriorityLevel.fromWire("")).isEqualTo(PriorityLevel.NORMAL);
    }

    @Test void fromWire_case_insensitive() {
        assertThat(PriorityLevel.fromWire("CRITICAL")).isEqualTo(PriorityLevel.CRITICAL);
        assertThat(PriorityLevel.fromWire("Normal")).isEqualTo(PriorityLevel.NORMAL);
        assertThat(PriorityLevel.fromWire("AMBIENT")).isEqualTo(PriorityLevel.AMBIENT);
    }
}

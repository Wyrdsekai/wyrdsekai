package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Relay gate v2 (second-node 2026-07-09) — "tell <name> …" target extraction. */
class RelayGateTest {

    @Test
    void extracts_tell_target() {
        assertThat(CompanionActor.relayTargetOf("Tell lulu that operator says good morning.", "operator"))
            .isEqualTo("lulu");
        assertThat(CompanionActor.relayTargetOf(
            "[from operator] Please tell lulu I said good morning and ask how she is settling in.", "operator"))
            .isEqualTo("lulu");
    }

    @Test
    void ask_and_let_know_forms() {
        assertThat(CompanionActor.relayTargetOf("Ask lulu how she slept.", "operator")).isEqualTo("lulu");
        assertThat(CompanionActor.relayTargetOf("Let mia know dinner is ready.", "operator")).isEqualTo("mia");
    }

    @Test
    void non_names_and_requester_do_not_count() {
        assertThat(CompanionActor.relayTargetOf("Tell me a joke.", "operator")).isNull();
        assertThat(CompanionActor.relayTargetOf("Tell us about the sea.", "operator")).isNull();
        assertThat(CompanionActor.relayTargetOf("Tell operator what you found.", "operator")).isNull();
        assertThat(CompanionActor.relayTargetOf("Build me a web tool.", "operator")).isNull();
        assertThat(CompanionActor.relayTargetOf(null, "operator")).isNull();
    }
}

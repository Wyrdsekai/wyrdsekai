package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.inference.NatsInferenceProtocol;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a zone asks of the relay stays inside what the relay grants a zone-bound registration
 * (deploy/relay/registration.py _subject_permissions_for, security review 2026-09-28): gate traffic
 * addressed to this zone only, and answers named after this zone.
 */
class RelayZoneScopeTest {

    @Test
    void theBridgeListensOnlyForGatesAddressedToItsZone() {
        assertThat(RelayBridge.inboundGatePatterns("alpha"))
            .containsExactly("federation.alpha.gate.>", "federation.*.alpha.gate.>");
    }

    @Test
    void answersComeBackUnderTheAskingZonesName() {
        var id = RelayIds.scopedId("alpha");
        assertThat(id).startsWith("alpha.");
        assertThat(id.substring("alpha.".length())).matches("[0-9a-f-]{36}");
        assertThat(RelayIds.scopedId("alpha")).isNotEqualTo(id);
        assertThat(NatsInferenceProtocol.streamSubject(id))
            .startsWith("federation.inference.stream.alpha.");
    }

    @Test
    void aNameThatIsNotAPlainLabelFallsBackToABareId() {
        assertThat(RelayIds.scopedId(null)).matches("[0-9a-f-]{36}");
        assertThat(RelayIds.scopedId("a.b")).matches("[0-9a-f-]{36}");
        assertThat(RelayIds.scopedId("*")).matches("[0-9a-f-]{36}");
    }
}

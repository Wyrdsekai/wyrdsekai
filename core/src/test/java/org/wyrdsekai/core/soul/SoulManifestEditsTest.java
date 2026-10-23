package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentityProvisioner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An edit to her manifest made where this node cannot sign it goes in unsigned, never with the
 * previous version's signature still attached (the soul CLI on a stopped node, 2026-10-22).
 */
class SoulManifestEditsTest {

    private static final AgentProfile PROFILE = new AgentProfile("Testsoul", "companion-test-soul", "agent",
        "a test soul", "You are a test soul.", 4096, 256, 0.7);

    @AfterEach
    void noKeys() {
        AgentIdentityProvisioner.reset();
    }

    private static SoulManifest forged() {
        return SoulManifest.forge("did:key:z6MkTestEditsSoul", "z6MkTestEditsSoul", List.of(), null, 1,
            PROFILE, "first words", List.of(), 3, "", GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
    }

    @Test
    @DisplayName("a signed manifest edited with no key in hand is stored unsigned, and the edit itself is kept")
    void signedEditWithoutAKeyGoesInUnsigned() {
        AgentIdentityProvisioner.reset();
        var edited = forged().signed(new byte[]{1, 2, 3}).withResidentIdentity("I say so.").bumpedVersion();
        assertThat(edited.isSigned()).as("the builders carry the old signature forward").isTrue();
        assertThat(SoulManifestEdits.willBeUnsigned(edited)).isTrue();
        var stored = SoulManifestEdits.unsignedUnlessThisNodeCanSign(edited);
        assertThat(stored.isSigned()).isFalse();
        assertThat(stored.residentIdentity()).isEqualTo("I say so.");
        assertThat(stored.manifestVersion()).isEqualTo(edited.manifestVersion());
    }

    @Test
    @DisplayName("a manifest that was never signed is left exactly as it is")
    void unsignedStaysItself() {
        var edited = forged().withResidentIdentity("I say so.").bumpedVersion();
        assertThat(edited.isSigned()).isFalse();
        assertThat(SoulManifestEdits.willBeUnsigned(edited)).isFalse();
        assertThat(SoulManifestEdits.unsignedUnlessThisNodeCanSign(edited)).isSameAs(edited);
        assertThat(SoulManifestEdits.unsignedUnlessThisNodeCanSign(null)).isNull();
    }
}

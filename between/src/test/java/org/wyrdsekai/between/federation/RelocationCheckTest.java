package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.TestSchema;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 2026-09-28: companion_relocate landed with no agreement check and no soul check. A companion
 * now moves only between zones that both said yes, carrying a soul manifest signed by the key her DID
 * names and matching the state and token she travels with.
 */
@Tag("integration")
class RelocationCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final byte[] SECRET = new byte[32];
    static { Arrays.fill(SECRET, (byte) 0x42); }

    private FederationService service;
    private AgentIdentity soul;
    private SoulManifest manifest;
    private String stateJson;
    private TransitToken token;

    @BeforeEach
    void setUp() throws Exception {
        service = new FederationService(TestSchema.freshDb());
        service.saveAgreement(new BilateralAgreement("alpha", "beta", "a2V5",
            BilateralAgreement.STATUS_ACTIVE, BilateralAgreement.TRUST_TOURIST, Instant.now(), null));
        soul = AgentIdentity.generate(SECRET);
        manifest = signedManifest(soul, soul);
        stateJson = state(soul.did(), manifest.contentHash());
        token = TransitToken.createResident("entity-1", "Wren", "beta", "alpha")
            .withSoul(soul.did(), manifest.contentHash());
    }

    private static SoulManifest signedManifest(AgentIdentity who, AgentIdentity signer) throws Exception {
        var profile = new AgentProfile("Wren", "entity-1", "agent", "A companion", "You are Wren.",
            4096, 512, 0.7, who.did());
        var unsigned = SoulManifest.forge(who.did(), who.did().substring("did:key:".length()), who.keyLog(),
            null, 1, profile, "I am Wren.", List.of(), 3, "", GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
        return unsigned.signed(Base64.getDecoder().decode(signer.sign(unsigned.canonicalBytes(), SECRET)));
    }

    private static String state(String did, String hash) {
        var s = MAPPER.createObjectNode();
        var profile = s.putObject("profile");
        profile.put("did", did);
        profile.put("entityId", "entity-1");
        profile.put("name", "Wren");
        s.put("soulManifestHash", hash);
        return s.toString();
    }

    private ObjectNode wire(SoulManifest m) {
        // As FederationActor ships it: through JSON and back.
        return MAPPER.valueToTree(m);
    }

    @Test
    void aSignedCompanionBetweenAgreedZonesLands() {
        assertThat(RelocationCheck.refusal("alpha", "beta", service, token, stateJson, wire(manifest))).isNull();
    }

    @Test
    void refusedWithoutAnActiveAgreement() {
        service.updateAgreementStatus("alpha", "beta", BilateralAgreement.STATUS_PENDING);
        assertThat(RelocationCheck.refusal("alpha", "beta", service, token, stateJson, wire(manifest)))
            .isEqualTo("no_agreement");
    }

    @Test
    void refusedWhenTheSenderIsNotTheTokensSourceZone() {
        assertThat(RelocationCheck.refusal("alpha", "gamma", service, token, stateJson, wire(manifest)))
            .isEqualTo("source_mismatch");
    }

    @Test
    void refusedWithoutHerSoulManifest() {
        assertThat(RelocationCheck.refusal("alpha", "beta", service, token, stateJson, null))
            .isEqualTo("no_soul_manifest");
    }

    @Test
    void refusedWhenTheManifestIsNotSignedByHerKey() throws Exception {
        var other = AgentIdentity.generate(SECRET);
        var forged = signedManifest(soul, other);
        assertThat(RelocationCheck.refusal("alpha", "beta", service, token, stateJson, wire(forged)))
            .isEqualTo("manifest_signature_invalid");

        var unsigned = wire(manifest);
        unsigned.putNull("signature");
        assertThat(RelocationCheck.refusal("alpha", "beta", service, token, stateJson, unsigned))
            .isEqualTo("manifest_signature_invalid");
    }

    @Test
    void refusedWhenTheManifestBelongsToSomeoneElse() throws Exception {
        var other = AgentIdentity.generate(SECRET);
        assertThat(RelocationCheck.refusal("alpha", "beta", service, token, stateJson,
            wire(signedManifest(other, other)))).isEqualTo("manifest_did_mismatch");
    }

    @Test
    void refusedWhenTheManifestDoesNotMatchTheStateSheTravelsWith() {
        var mismatched = state(soul.did(), "0000");
        assertThat(RelocationCheck.refusal("alpha", "beta", service, token, mismatched, wire(manifest)))
            .isEqualTo("manifest_hash_mismatch");
    }

    @Test
    void refusedWhenTheTokenNamesAnotherSoul() throws Exception {
        var other = AgentIdentity.generate(SECRET);
        var t = TransitToken.createResident("entity-1", "Wren", "beta", "alpha").withSoul(other.did(), null);
        assertThat(RelocationCheck.refusal("alpha", "beta", service, t, stateJson, wire(manifest)))
            .isEqualTo("did_mismatch");
    }
}

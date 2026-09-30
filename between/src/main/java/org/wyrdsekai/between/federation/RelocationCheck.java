package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.DidKey;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulVerifier;

import java.time.Instant;
import java.util.List;

/**
 * Whether an inbound {@code companion_relocate} may land (audit 2026-09-28: it used to land with no
 * agreement check and no soul check, spawning whatever state arrived). A companion moves only between
 * zones that both said yes, and arrives whole: the relocation carries her soul manifest, which must be
 * signed by the key her DID names and match the state and token she travels with.
 */
final class RelocationCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RelocationCheck() {}

    /**
     * @param fromZone        the zone the envelope verifiably came from
     * @param soulManifestNode the {@code soulManifest} field of the payload, or null
     * @return null when the relocation may land; otherwise the refusal reason for the ack
     */
    static String refusal(String localZoneId, String fromZone, FederationService service,
                          TransitToken token, String stateJson, JsonNode soulManifestNode) {
        if (token == null) return "missing_token";
        if (!localZoneId.equals(token.targetZoneId())) return "target_mismatch:" + localZoneId;
        if (fromZone == null || !fromZone.equals(token.sourceZoneId())) return "source_mismatch";
        if (token.isExpired()) return "token_expired";
        var agreement = service.getAgreement(localZoneId, fromZone);
        if (agreement.isEmpty() || !agreement.get().isActive()) return "no_agreement";
        if (stateJson == null) return "no_state";

        String did;
        String stateHash;
        try {
            var state = MAPPER.readTree(stateJson);
            did = state.path("profile").path("did").asText("");
            stateHash = state.path("soulManifestHash").asText("");
        } catch (Exception e) {
            return "bad_state";
        }
        if (did.isBlank()) return "no_soul_identity";
        if (!did.equals(token.agentDid())) return "did_mismatch";

        if (soulManifestNode == null || soulManifestNode.isNull() || soulManifestNode.isMissingNode()) {
            return "no_soul_manifest";
        }
        SoulManifest manifest;
        try {
            manifest = MAPPER.treeToValue(soulManifestNode, SoulManifest.class);
        } catch (Exception e) {
            return "bad_soul_manifest";
        }
        if (!did.equals(manifest.did())) return "manifest_did_mismatch";
        byte[] rawKey;
        try {
            rawKey = DidKey.rawPublicKeyFromMultibase(manifest.publicKeyMultibase());
            if (!did.equals(DidKey.fromRawPublicKey(rawKey))) return "manifest_key_mismatch";
        } catch (RuntimeException e) {
            return "manifest_key_mismatch";
        }
        var identity = new AgentIdentity(manifest.did(), rawKey, null,
            manifest.keyLog() != null ? manifest.keyLog() : List.of(), Instant.now(), manifest.parentDid(), null);
        if (!manifest.isSigned() || !SoulVerifier.verifySignature(manifest, identity)) {
            return "manifest_signature_invalid";
        }
        var hash = manifest.contentHash();
        if (!stateHash.isBlank() && !stateHash.equals(hash)) return "manifest_hash_mismatch";
        if (token.manifestHash() != null && !token.manifestHash().isBlank() && !token.manifestHash().equals(hash)) {
            return "manifest_hash_mismatch";
        }
        return null;
    }
}

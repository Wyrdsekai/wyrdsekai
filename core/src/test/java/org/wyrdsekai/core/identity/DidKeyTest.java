package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.Signature;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DID:key generation from Ed25519 keypairs (§73, §80).
 */
class DidKeyTest {

    @Test
    void generate_produces_valid_did_key() throws Exception {
        var result = DidKey.generate();

        assertThat(result.did()).startsWith("did:key:z6Mk");
        assertThat(result.keyPair()).isNotNull();
        assertThat(result.keyPair().getPublic().getAlgorithm()).isEqualTo("EdDSA");
    }

    @Test
    void from_public_key_produces_consistent_did() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var keyPair = kpg.generateKeyPair();

        var did1 = DidKey.fromPublicKey(keyPair.getPublic());
        var did2 = DidKey.fromPublicKey(keyPair.getPublic());

        assertThat(did1).isEqualTo(did2); // Deterministic
        assertThat(did1).startsWith("did:key:z");
    }

    @Test
    void different_keys_produce_different_dids() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var did1 = DidKey.fromPublicKey(kpg.generateKeyPair().getPublic());
        var did2 = DidKey.fromPublicKey(kpg.generateKeyPair().getPublic());

        assertThat(did1).isNotEqualTo(did2);
    }

    @Test
    void did_document_has_required_fields() throws Exception {
        var result = DidKey.generate();
        var doc = DidKey.buildDocument(result.did());

        assertThat(doc.get("@context").get(0).asText()).contains("did");
        assertThat(doc.get("id").asText()).isEqualTo(result.did());

        var vm = doc.get("verificationMethod").get(0);
        assertThat(vm.get("type").asText()).isEqualTo("Multikey");
        assertThat(vm.get("controller").asText()).isEqualTo(result.did());
        assertThat(vm.get("publicKeyMultibase").asText()).startsWith("z6Mk");

        // Verification relationships reference the verification method
        var vmId = vm.get("id").asText();
        assertThat(doc.get("authentication").get(0).asText()).isEqualTo(vmId);
        assertThat(doc.get("assertionMethod").get(0).asText()).isEqualTo(vmId);
        assertThat(doc.get("capabilityDelegation").get(0).asText()).isEqualTo(vmId);
        assertThat(doc.get("capabilityInvocation").get(0).asText()).isEqualTo(vmId);
    }

    @Test
    void extract_raw_public_key_is_32_bytes() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var raw = DidKey.extractRawEd25519PublicKey(kpg.generateKeyPair().getPublic());

        assertThat(raw).hasSize(32);
    }

    @Test
    void from_raw_public_key_matches_from_public_key() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var pub = kpg.generateKeyPair().getPublic();

        var didFromPub = DidKey.fromPublicKey(pub);
        var didFromRaw = DidKey.fromRawPublicKey(DidKey.extractRawEd25519PublicKey(pub));

        assertThat(didFromPub).isEqualTo(didFromRaw);
    }

    @Test
    void raw_public_key_from_multibase_roundtrips() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var pub = kpg.generateKeyPair().getPublic();
        var rawOriginal = DidKey.extractRawEd25519PublicKey(pub);

        // Build the multibase key (same path as fromRawPublicKey but extract the z-prefixed part)
        var did = DidKey.fromRawPublicKey(rawOriginal);
        var multibaseKey = did.substring("did:key:".length()); // "z6Mk..."

        // Decode back to raw bytes
        var rawDecoded = DidKey.rawPublicKeyFromMultibase(multibaseKey);
        assertThat(rawDecoded).hasSize(32);
        assertThat(rawDecoded).isEqualTo(rawOriginal);
    }

    @Test
    void base58_encode_decode_roundtrip() {
        var original = new byte[]{1, 2, 3, 42, (byte) 255, 0, 0, 127};
        var encoded = DidKey.base58Encode(original);
        var decoded = DidKey.base58Decode(encoded);
        assertThat(decoded).isEqualTo(original);
    }

    // ── publicKeyFromDid ( signature verification) ─────

    @Test
    void public_key_from_did_roundtrips_to_verifying_key() throws Exception {
        var pair = DidKey.generate();

        var recovered = DidKey.publicKeyFromDid(pair.did());
        assertThat(recovered).isPresent();

        // Sign with the original private key; verify with the recovered pub key.
        // Proves the pub key extracted from the DID really does match the pair.
        var payload = "test payload bytes".getBytes();
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.keyPair().getPrivate());
        signer.update(payload);
        var sig = signer.sign();

        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(recovered.get());
        verifier.update(payload);
        assertThat(verifier.verify(sig)).isTrue();
    }

    @Test
    void public_key_from_did_rejects_non_did_key_format() {
        assertThat(DidKey.publicKeyFromDid(null)).isEmpty();
        assertThat(DidKey.publicKeyFromDid("")).isEmpty();
        assertThat(DidKey.publicKeyFromDid("not-a-did")).isEmpty();
        assertThat(DidKey.publicKeyFromDid("did:web:example.com")).isEmpty();
        assertThat(DidKey.publicKeyFromDid("did:key:")).isEmpty();
        assertThat(DidKey.publicKeyFromDid("did:key:invalid-multibase")).isEmpty();
    }

    @Test
    void public_key_from_did_is_deterministic() throws Exception {
        var pair = DidKey.generate();
        var a = DidKey.publicKeyFromDid(pair.did()).orElseThrow();
        var b = DidKey.publicKeyFromDid(pair.did()).orElseThrow();
        // Same DID → same encoded public key bytes
        assertThat(a.getEncoded()).isEqualTo(b.getEncoded());
    }
}

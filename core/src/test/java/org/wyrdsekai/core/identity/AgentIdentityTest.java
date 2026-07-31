package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentIdentityTest {

    private static byte[] householdSecret;

    @BeforeAll
    static void generateHouseholdSecret() {
        householdSecret = new byte[32];
        new SecureRandom().nextBytes(householdSecret);
    }

    @Test void generate_creates_valid_identity() throws Exception {
        var identity = AgentIdentity.generate(householdSecret);

        assertThat(identity.did()).startsWith("did:key:z");
        assertThat(identity.publicKey()).hasSize(32);
        assertThat(identity.privateKeyEncrypted()).isNotNull();
        assertThat(identity.privateKeyEncrypted().length).isGreaterThan(32); // IV + encrypted + tag
        assertThat(identity.keyLog()).hasSize(1); // inception event
        assertThat(identity.parentDid()).isNull();
        assertThat(identity.delegation().level()).isEqualTo(DelegationLevel.FULL);
    }

    @Test void did_matches_public_key() throws Exception {
        var identity = AgentIdentity.generate(householdSecret);

        // Reconstruct DID from public key and verify match
        var expectedDid = DidKey.fromRawPublicKey(identity.publicKey());
        assertThat(identity.did()).isEqualTo(expectedDid);
    }

    @Test void sign_and_verify() throws Exception {
        var identity = AgentIdentity.generate(householdSecret);
        var data = "hello world".getBytes();

        var signature = identity.sign(data, householdSecret);
        assertThat(signature).isNotBlank();

        assertThat(identity.verify(data, signature)).isTrue();
        assertThat(identity.verify("wrong data".getBytes(), signature)).isFalse();
    }

    @Test void toKeyPair_roundtrips() throws Exception {
        var identity = AgentIdentity.generate(householdSecret);
        var keyPair = identity.toKeyPair(householdSecret);

        // Public key should match
        var rawPub = DidKey.extractRawEd25519PublicKey(keyPair.getPublic());
        assertThat(rawPub).isEqualTo(identity.publicKey());

        // Should be able to sign with the reconstructed keypair
        var sig = Signature.getInstance("Ed25519");
        sig.initSign(keyPair.getPrivate());
        sig.update("test".getBytes());
        var signed = sig.sign();

        sig.initVerify(keyPair.getPublic());
        sig.update("test".getBytes());
        assertThat(sig.verify(signed)).isTrue();
    }

    @Test void wrong_secret_fails_decryption() throws Exception {
        var identity = AgentIdentity.generate(householdSecret);
        var wrongSecret = new byte[32];
        new SecureRandom().nextBytes(wrongSecret);

        assertThatThrownBy(() -> identity.toKeyPair(wrongSecret))
            .isInstanceOf(Exception.class);
    }

    @Test void fork_creates_child_with_parent_reference() throws Exception {
        var parent = AgentIdentity.generate(householdSecret);
        var child = parent.fork(householdSecret,
            AgentIdentity.IdentityDelegation.of(DelegationLevel.READ_ONLY));

        assertThat(child.did()).startsWith("did:key:z");
        assertThat(child.did()).isNotEqualTo(parent.did()); // different keypair
        assertThat(child.parentDid()).isEqualTo(parent.did());
        assertThat(child.delegation().level()).isEqualTo(DelegationLevel.READ_ONLY);
        assertThat(child.keyLog()).hasSize(1); // own inception event
    }

    @Test void keri_inception_event_in_key_log() throws Exception {
        var identity = AgentIdentity.generate(householdSecret);
        var event = identity.keyLog().getFirst();

        assertThat(event.get("t").asText()).isEqualTo("icp");
        assertThat(event.get("d").asText()).isNotBlank();
        assertThat(event.get("i").asText()).isEqualTo(event.get("d").asText()); // self-addressing
        assertThat(event.get("k").isArray()).isTrue();
        assertThat(event.get("k").size()).isEqualTo(1);
        assertThat(event.get("n").isArray()).isTrue();
        assertThat(event.get("n").size()).isEqualTo(1); // pre-rotation commitment
    }

    @Test void jackson_serialization_roundtrip() throws Exception {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        var identity = AgentIdentity.generate(householdSecret);
        var json = mapper.writeValueAsString(identity);
        var deserialized = mapper.readValue(json, AgentIdentity.class);

        assertThat(deserialized.did()).isEqualTo(identity.did());
        assertThat(deserialized.publicKey()).isEqualTo(identity.publicKey());
        assertThat(deserialized.privateKeyEncrypted()).isEqualTo(identity.privateKeyEncrypted());
        assertThat(deserialized.created()).isEqualTo(identity.created());
        assertThat(deserialized.parentDid()).isEqualTo(identity.parentDid());
        assertThat(deserialized.delegation().level()).isEqualTo(identity.delegation().level());

        // Verify the deserialized identity can still sign
        var data = "roundtrip test".getBytes();
        var sig = deserialized.sign(data, householdSecret);
        assertThat(deserialized.verify(data, sig)).isTrue();
    }

    @Test void private_key_encryption_roundtrip() throws Exception {
        // Direct test of encrypt/decrypt helpers
        var rawKey = new byte[32];
        new SecureRandom().nextBytes(rawKey);

        var encrypted = AgentIdentity.encryptPrivateKey(rawKey, householdSecret);
        assertThat(encrypted.length).isGreaterThan(32); // IV (12) + ciphertext (32) + tag (16) = 60

        var decrypted = AgentIdentity.decryptPrivateKey(encrypted, householdSecret);
        assertThat(decrypted).isEqualTo(rawKey);
    }
}

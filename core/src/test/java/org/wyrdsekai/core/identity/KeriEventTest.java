package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for KERI inception events with pre-rotation (§73).
 */
class KeriEventTest {

    @Test
    void cesr_encode_ed25519_public_key() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var raw = DidKey.extractRawEd25519PublicKey(kpg.generateKeyPair().getPublic());

        var cesr = KeriEvent.cesrEncodeEd25519PubKey(raw);

        assertThat(cesr).startsWith("D"); // CESR code for transferable Ed25519
        assertThat(cesr).hasSize(44);     // 1 code char + 43 base64url chars
    }

    @Test
    void cesr_encode_sha256_digest() throws Exception {
        var digest = KeriEvent.sha256("test".getBytes());

        var cesr = KeriEvent.cesrEncodeSha256Digest(digest);

        assertThat(cesr).startsWith("I"); // CESR code for SHA-256
        assertThat(cesr).hasSize(44);
    }

    @Test
    void pre_rotation_commitment_is_deterministic() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var raw = DidKey.extractRawEd25519PublicKey(kpg.generateKeyPair().getPublic());

        var commit1 = KeriEvent.preRotationCommitment(raw);
        var commit2 = KeriEvent.preRotationCommitment(raw);

        assertThat(commit1).isEqualTo(commit2);
        assertThat(commit1).startsWith("I"); // SHA-256 digest code
        assertThat(commit1).hasSize(44);
    }

    @Test
    void inception_event_has_required_fields() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var currentKey = kpg.generateKeyPair().getPublic();
        var nextKey = kpg.generateKeyPair().getPublic();

        var event = KeriEvent.inception(currentKey, nextKey);

        // Version string
        assertThat(event.get("v").asText()).startsWith("KERI10JSON");
        assertThat(event.get("v").asText()).endsWith("_");

        // Event type
        assertThat(event.get("t").asText()).isEqualTo("icp");

        // SAID (Self-Addressing Identifier)
        assertThat(event.get("d").asText()).startsWith("I"); // SHA-256 CESR
        assertThat(event.get("d").asText()).hasSize(44);

        // AID = SAID for self-addressing
        assertThat(event.get("i").asText()).isEqualTo(event.get("d").asText());

        // Sequence number
        assertThat(event.get("s").asText()).isEqualTo("0");

        // Current keys
        assertThat(event.get("kt").asText()).isEqualTo("1");
        assertThat(event.get("k")).hasSize(1);
        assertThat(event.get("k").get(0).asText()).startsWith("D");

        // Pre-rotation commitments
        assertThat(event.get("nt").asText()).isEqualTo("1");
        assertThat(event.get("n")).hasSize(1);
        assertThat(event.get("n").get(0).asText()).startsWith("I"); // SHA-256 digest

        // Empty backers/config/anchors
        assertThat(event.get("bt").asText()).isEqualTo("0");
        assertThat(event.get("b")).isEmpty();
        assertThat(event.get("c")).isEmpty();
        assertThat(event.get("a")).isEmpty();
    }

    @Test
    void inception_event_said_is_valid() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var event = KeriEvent.inception(
            kpg.generateKeyPair().getPublic(),
            kpg.generateKeyPair().getPublic());

        // Verify SAID by recomputing
        var said = event.get("d").asText();
        var recomputed = KeriEvent.computeSaid(event.deepCopy());
        assertThat(said).isEqualTo(recomputed);
    }

    @Test
    void different_keys_produce_different_inception_events() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");

        var event1 = KeriEvent.inception(
            kpg.generateKeyPair().getPublic(),
            kpg.generateKeyPair().getPublic());
        var event2 = KeriEvent.inception(
            kpg.generateKeyPair().getPublic(),
            kpg.generateKeyPair().getPublic());

        assertThat(event1.get("d").asText()).isNotEqualTo(event2.get("d").asText());
        assertThat(event1.get("k").get(0).asText()).isNotEqualTo(event2.get("k").get(0).asText());
    }
}

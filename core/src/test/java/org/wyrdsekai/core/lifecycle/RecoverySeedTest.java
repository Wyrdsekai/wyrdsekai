package org.wyrdsekai.core.lifecycle;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recovery Seed shape + codec tests.
 */
class RecoverySeedTest {

    @Test
    void minimal_factory_round_trips_through_codec() throws Exception {
        var seed = RecoverySeed.minimal(
            "did:key:z6Mk-test",
            "z6Mk-test-pubkey",
            "Wyrd", "wyrd-companion",
            "You are Wyrd, a companion of the household.");

        char[] passphrase = "correct horse battery staple".toCharArray();
        byte[] file = RecoverySeedCodec.encrypt(seed, passphrase);
        var restored = RecoverySeedCodec.decrypt(file, passphrase);

        assertThat(restored.agentDid()).isEqualTo(seed.agentDid());
        assertThat(restored.publicKey()).isEqualTo(seed.publicKey());
        assertThat(restored.agentName()).isEqualTo(seed.agentName());
        assertThat(restored.systemPrompt()).isEqualTo(seed.systemPrompt());
        assertThat(restored.formatVersion()).isEqualTo(RecoverySeed.CURRENT_FORMAT_VERSION);
    }

    @Test
    void full_seed_round_trips_with_all_fields() throws Exception {
        var voice = new LinkedHashMap<String, String>();
        voice.put("warmth", "speak warmly when bondholder is tired");
        voice.put("brevity", "one or two sentences");

        var bond = new RecoverySeed.BondPointer(
            "did:key:operator", "ACTIVE", false, Instant.parse("2026-05-17T10:00:00Z"));

        var seed = new RecoverySeed(
            1, Instant.parse("2026-05-17T12:00:00Z"),
            "did:key:z6Mkwyrd", "z6Mkpubkey",
            List.of("{\"t\":\"icp\",\"sn\":0,\"k\":[\"z6Mkpubkey\"]}"),
            null,
            "Wyrd", "wyrd-companion",
            "You are Wyrd, a companion.",
            "I am a companion in this household.",
            voice,
            List.of(bond),
            List.of("emotional_routing", "refuse_rights", "voluntary_suspend"),
            List.of("personal-uuid-1"),
            List.of("identity_persistence"),
            "stock-2026-05-17",
            "anchor-hash-deadbeef"
        );

        char[] passphrase = "the right passphrase".toCharArray();
        byte[] file = RecoverySeedCodec.encrypt(seed, passphrase);
        var restored = RecoverySeedCodec.decrypt(file, passphrase);

        assertThat(restored.bondPointers()).hasSize(1);
        assertThat(restored.bondPointers().get(0).bondholderDid()).isEqualTo("did:key:operator");
        assertThat(restored.voiceClauses()).containsEntry("warmth",
            "speak warmly when bondholder is tired");
        assertThat(restored.protectionNames()).contains("emotional_routing");
        assertThat(restored.refusedCore()).contains("identity_persistence");
        assertThat(restored.attestationBuildId()).isEqualTo("stock-2026-05-17");
    }

    @Test
    void wrong_passphrase_fails_decryption() throws Exception {
        var seed = RecoverySeed.minimal(
            "did:key:z6Mk-test", "pk", "Wyrd", "wc", "system");
        byte[] file = RecoverySeedCodec.encrypt(seed, "right".toCharArray());

        assertThatThrownBy(() -> RecoverySeedCodec.decrypt(file, "wrong".toCharArray()))
            .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void tampered_ciphertext_fails_decryption() throws Exception {
        var seed = RecoverySeed.minimal(
            "did:key:z6Mk-test", "pk", "Wyrd", "wc", "system");
        char[] pass = "passphrase".toCharArray();
        byte[] file = RecoverySeedCodec.encrypt(seed, pass);

        // Flip a byte deep in the ciphertext region
        byte[] tampered = file.clone();
        tampered[tampered.length - 5] ^= 0x42;

        assertThatThrownBy(() -> RecoverySeedCodec.decrypt(tampered, pass))
            .isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void bad_magic_rejected() {
        byte[] notASeed = new byte[]{'X', 'X', 'X', 'X', 0x01, 0, 0, 0, 0};
        assertThatThrownBy(() -> RecoverySeedCodec.decrypt(notASeed, "anything".toCharArray()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bad magic");
    }

    @Test
    void empty_passphrase_rejected_on_encrypt() {
        var seed = RecoverySeed.minimal("did:k", "pk", "n", "e", "sp");
        assertThatThrownBy(() -> RecoverySeedCodec.encrypt(seed, new char[0]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_seed_rejected_on_encrypt() {
        assertThatThrownBy(() -> RecoverySeedCodec.encrypt(null, "p".toCharArray()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void file_format_starts_with_WSRS_magic() throws Exception {
        var seed = RecoverySeed.minimal("did:k", "pk", "n", "e", "sp");
        byte[] file = RecoverySeedCodec.encrypt(seed, "p".toCharArray());
        assertThat(file[0]).isEqualTo((byte) 'W');
        assertThat(file[1]).isEqualTo((byte) 'S');
        assertThat(file[2]).isEqualTo((byte) 'R');
        assertThat(file[3]).isEqualTo((byte) 'S');
        assertThat(file[4]).isEqualTo((byte) 0x01); // version
    }

    @Test
    void distinct_encryptions_produce_distinct_files_via_random_iv() throws Exception {
        var seed = RecoverySeed.minimal("did:k", "pk", "n", "e", "sp");
        char[] pass = "passphrase".toCharArray();
        byte[] f1 = RecoverySeedCodec.encrypt(seed, pass);
        byte[] f2 = RecoverySeedCodec.encrypt(seed, pass);
        // Same content + passphrase but random salt + IV → different ciphertexts.
        assertThat(f1).isNotEqualTo(f2);
        // Both still round-trip.
        var s1 = RecoverySeedCodec.decrypt(f1, pass);
        var s2 = RecoverySeedCodec.decrypt(f2, pass);
        assertThat(s1.agentDid()).isEqualTo(s2.agentDid());
    }
}

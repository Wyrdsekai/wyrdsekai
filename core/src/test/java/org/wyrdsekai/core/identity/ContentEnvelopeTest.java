package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Envelope encryption exists so identity can CHANGE without destroying data.
 *
 * <p>The behaviour being replaced: {@code PrivateJournalCipher} derives its key
 * from the owner string and uses it as AAD, so content encrypted under one
 * identity can never be read as another. These tests pin the escape from that.</p>
 */
class ContentEnvelopeTest {

    private static byte[] secret() {
        var s = new byte[32];
        new SecureRandom().nextBytes(s);
        return s;
    }

    @Test
    void seals_and_opens_round_trip() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);

        var env = ContentEnvelope.seal("journal:1", "the greenhouse door sticks", me, hs);
        assertEquals("the greenhouse door sticks", env.open("journal:1", me, hs));
    }

    /**
     * THE property this class exists for: after rebinding to a new person the
     * content is still readable — and the ciphertext was never rewritten.
     */
    @Test
    void rebind_changes_owner_without_touching_the_ciphertext() throws Exception {
        var hs = secret();
        var oldMe = PersonIdentity.generate(hs);
        var newMe = PersonIdentity.generate(hs);

        var sealed = ContentEnvelope.seal("journal:1", "irreplaceable writing", oldMe, hs);
        var rebound = sealed.rebind(oldMe, newMe, hs);

        assertArrayEquals(sealed.ciphertext(), rebound.ciphertext(),
            "content must NOT be re-encrypted — this is what makes rebinding affordable");
        assertNotEquals(Arrays.toString(sealed.wrappedKey()),
            Arrays.toString(rebound.wrappedKey()),
            "the wrapped content key must be rewritten");
        assertEquals(newMe.did(), rebound.ownerDid());
        assertEquals("irreplaceable writing", rebound.open("journal:1", newMe, hs),
            "the new owner must be able to read it");
    }

    /** After rebinding, the old identity must no longer open it. */
    @Test
    void old_owner_cannot_open_after_rebind() throws Exception {
        var hs = secret();
        var oldMe = PersonIdentity.generate(hs);
        var newMe = PersonIdentity.generate(hs);

        var rebound = ContentEnvelope.seal("j", "secret", oldMe, hs).rebind(oldMe, newMe, hs);
        assertThrows(IllegalArgumentException.class, () -> rebound.open("j", oldMe, hs));
    }

    /** A person the envelope was never wrapped for cannot read it. */
    @Test
    void a_stranger_cannot_open_it() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);
        var stranger = PersonIdentity.generate(hs);

        var env = ContentEnvelope.seal("j", "private", me, hs);
        assertThrows(IllegalArgumentException.class, () -> env.open("j", stranger, hs));
    }

    /**
     * AAD binds to the ITEM, not the person. If it bound to the person, every
     * rebind would break authentication on every item — the trap being escaped.
     */
    @Test
    void wrong_item_id_fails_authentication() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);

        var env = ContentEnvelope.seal("journal:1", "content", me, hs);
        assertThrows(Exception.class, () -> env.open("journal:2", me, hs),
            "item id is authenticated data — a mismatched id must fail");
    }

    /** Rebinding must survive repeated identity changes, not just one. */
    @Test
    void survives_a_chain_of_rebinds() throws Exception {
        var hs = secret();
        var a = PersonIdentity.generate(hs);
        var b = PersonIdentity.generate(hs);
        var c = PersonIdentity.generate(hs);

        var env = ContentEnvelope.seal("j", "durable", a, hs);
        var original = env.ciphertext();

        var end = env.rebind(a, b, hs).rebind(b, c, hs);
        assertEquals("durable", end.open("j", c, hs));
        assertArrayEquals(original, end.ciphertext(),
            "content survives arbitrarily many identity changes untouched");
    }

    /** Rebinding to the same person is a no-op, not a re-encryption. */
    @Test
    void rebind_to_same_person_is_a_noop() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);
        var env = ContentEnvelope.seal("j", "x", me, hs);
        assertSame(env, env.rebind(me, me, hs));
    }

    /** Rebinding from the wrong source identity must be refused. */
    @Test
    void rebind_from_wrong_owner_is_refused() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);
        var other = PersonIdentity.generate(hs);
        var third = PersonIdentity.generate(hs);

        var env = ContentEnvelope.seal("j", "x", me, hs);
        assertThrows(IllegalArgumentException.class, () -> env.rebind(other, third, hs));
    }

    /** Two seals of identical plaintext must differ — fresh content key and IV each time. */
    @Test
    void identical_plaintext_produces_different_ciphertext() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);

        var a = ContentEnvelope.seal("j", "same words", me, hs);
        var b = ContentEnvelope.seal("j", "same words", me, hs);
        assertFalse(Arrays.equals(a.ciphertext(), b.ciphertext()),
            "a fresh content key and IV per item must prevent identical ciphertexts");
    }

    /** The plaintext must not be recoverable without the household secret. */
    @Test
    void wrong_household_secret_cannot_open() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);
        var env = ContentEnvelope.seal("j", "x", me, hs);

        assertThrows(Exception.class, () -> env.open("j", me, secret()));
    }

    /** Plaintext must not appear anywhere in the stored bytes. */
    @Test
    void plaintext_does_not_leak_into_stored_bytes() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);
        var marker = "GREENHOUSE-CANARY-8811";

        var env = ContentEnvelope.seal("j", marker, me, hs);
        var blob = new String(env.ciphertext(), StandardCharsets.ISO_8859_1);
        assertFalse(blob.contains(marker));
        assertFalse(env.toString().contains(marker));
    }
}

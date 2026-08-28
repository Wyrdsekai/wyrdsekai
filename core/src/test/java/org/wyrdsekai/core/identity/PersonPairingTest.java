package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Base64;

/**
 * Without a join flow, one human who registers on two machines becomes two
 * people — each with their own Study, bonds, and half the content. Merging them
 * afterwards is a rebind under worse conditions, with irreplaceable journal
 * entries on both sides.
 */
class PersonPairingTest {

    @TempDir Path tmp;
    private String jdbc;
    private byte[] hs;
    private PersonIdentityStore identities;
    private PersonIdentityResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db");
        hs = new byte[32];
        new SecureRandom().nextBytes(hs);
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("CREATE TABLE users(id TEXT PRIMARY KEY, username TEXT UNIQUE, "
                + "password_hash TEXT, display_name TEXT, description TEXT, role TEXT, created_at INTEGER)");
            st.execute("INSERT INTO users(id,username,display_name,role) "
                + "VALUES('phone-cred','operator-phone','operator','member')");
        }
        identities = new PersonIdentityStore(jdbc);
        resolver = new PersonIdentityResolver(jdbc);
    }

    /** THE point: joining binds to the existing person and mints nothing new. */
    @Test
    void redeeming_binds_to_the_existing_person_without_minting() throws Exception {
        var me = PersonIdentity.generate(hs);
        identities.save(me);
        var before = identities.listDids().size();

        var invite = PersonPairing.issue(me, hs, Duration.ofMinutes(5));
        var joined = PersonPairing.redeem(invite, "phone-cred", identities, resolver).orElseThrow();

        assertEquals(me.did(), joined, "the second device must adopt the SAME person");
        assertEquals(before, identities.listDids().size(),
            "joining must not create a second identity for one human");
        assertEquals(me.did(), resolver.resolve("phone-cred").orElseThrow());
        assertEquals(me.did(), resolver.resolve("operator-phone").orElseThrow());
    }

    /** An invite must actually come from the person it names. */
    @Test
    void an_invite_signed_by_someone_else_is_refused() throws Exception {
        var me = PersonIdentity.generate(hs);
        var attacker = PersonIdentity.generate(hs);
        identities.save(me);

        var real = PersonPairing.issue(attacker, hs, Duration.ofMinutes(5));
        var forged = new PersonPairing.Invite(me.did(), real.nonce(),
            real.expiresAt(), real.signature());

        assertFalse(PersonPairing.verify(forged, me, Instant.now()));
        assertTrue(PersonPairing.redeem(forged, "phone-cred", identities, resolver).isEmpty());
    }

    /** An invite that adopts an identity must not live long. */
    @Test
    void an_expired_invite_is_refused() throws Exception {
        var me = PersonIdentity.generate(hs);
        identities.save(me);

        var invite = PersonPairing.issue(me, hs, Duration.ofSeconds(-1));
        assertTrue(invite.isExpired(Instant.now()));
        assertFalse(PersonPairing.verify(invite, me, Instant.now()));
        assertTrue(PersonPairing.redeem(invite, "phone-cred", identities, resolver).isEmpty());
    }

    /** Tampering with the expiry must break the signature. */
    @Test
    void extending_the_expiry_breaks_the_signature() throws Exception {
        var me = PersonIdentity.generate(hs);
        identities.save(me);

        var invite = PersonPairing.issue(me, hs, Duration.ofSeconds(-1));
        var extended = new PersonPairing.Invite(invite.personDid(), invite.nonce(),
            Instant.now().plusSeconds(3600), invite.signature());

        assertFalse(PersonPairing.verify(extended, me, Instant.now()),
            "an expiry is signed data — extending it must not work");
    }

    /** A person this node has never heard of cannot be joined. */
    @Test
    void cannot_join_a_person_this_node_does_not_hold() throws Exception {
        var elsewhere = PersonIdentity.generate(hs);
        var invite = PersonPairing.issue(elsewhere, hs, Duration.ofMinutes(5));

        assertTrue(PersonPairing.redeem(invite, "phone-cred", identities, resolver).isEmpty());
    }

    /** The invite must survive being carried as text — QR, or read aloud. */
    @Test
    void encodes_and_decodes_round_trip() throws Exception {
        var me = PersonIdentity.generate(hs);
        identities.save(me);

        var invite = PersonPairing.issue(me, hs, Duration.ofMinutes(5));
        var decoded = PersonPairing.Invite.decode(invite.encode()).orElseThrow();

        assertEquals(invite.personDid(), decoded.personDid());
        assertEquals(invite.nonce(), decoded.nonce());
        assertTrue(PersonPairing.verify(decoded, me, Instant.now()),
            "a transferred invite must still verify");
    }

    /** Malformed invite text must not blow up. */
    @Test
    void rejects_malformed_invite_text() {
        assertTrue(PersonPairing.Invite.decode(null).isEmpty());
        assertTrue(PersonPairing.Invite.decode("").isEmpty());
        assertTrue(PersonPairing.Invite.decode("not|an|invite").isEmpty());
        assertTrue(PersonPairing.Invite.decode("a|b|not-a-number|c").isEmpty());
    }

    /** No key material may appear in the transferable form. */
    @Test
    void the_invite_carries_no_key_material() throws Exception {
        var me = PersonIdentity.generate(hs);
        identities.save(me);

        var encoded = PersonPairing.issue(me, hs, Duration.ofMinutes(5)).encode();
        var priv = Base64.getEncoder().encodeToString(me.encryptedPrivateKey());

        assertFalse(encoded.contains(priv),
            "a pairing invite must never carry the person's key — only a claim");
    }

    /** Two separate invites must not collide. */
    @Test
    void each_invite_has_its_own_nonce() throws Exception {
        var me = PersonIdentity.generate(hs);
        identities.save(me);

        var a = PersonPairing.issue(me, hs, Duration.ofMinutes(5));
        var b = PersonPairing.issue(me, hs, Duration.ofMinutes(5));
        assertFalse(a.nonce().equals(b.nonce()));
    }
}

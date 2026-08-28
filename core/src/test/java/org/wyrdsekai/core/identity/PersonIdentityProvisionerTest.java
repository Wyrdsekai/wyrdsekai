package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;

/**
 * Registration must mint a PERSON, not just a local credential.
 *
 * <p>This is the half that stops the defect existing on fresh installs: without
 * it, {@code register()} produced a UUID and nothing else, so any later code
 * needing an owner had nothing to resolve and invented a string instead.</p>
 */
class PersonIdentityProvisionerTest {

    @TempDir Path tmp;
    private String jdbc;

    private static byte[] secret() {
        var s = new byte[32];
        new SecureRandom().nextBytes(s);
        return s;
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db");
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE users(
                  id TEXT PRIMARY KEY, username TEXT UNIQUE, password_hash TEXT,
                  display_name TEXT, description TEXT, role TEXT,
                  created_at INTEGER DEFAULT 0)
                """);
            st.execute("""
                CREATE TABLE sessions(
                  token TEXT PRIMARY KEY, user_id TEXT, created_at INTEGER, expires_at INTEGER)
                """);
        }
        PersonIdentityProvisioner.reset();
    }

    @AfterEach
    void tearDown() {
        PersonIdentityProvisioner.reset();
    }

    /** Until wired, everything no-ops — an un-migrated node behaves exactly as before. */
    @Test
    void is_a_noop_until_initialised() {
        assertFalse(PersonIdentityProvisioner.isEnabled());
        assertTrue(PersonIdentityProvisioner.provision("some-uuid", "someone").isEmpty());
    }

    /** Once wired, provisioning mints a person and binds the credential to it. */
    @Test
    void provisioning_mints_a_person_and_binds_the_credential() throws Exception {
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("INSERT INTO users(id, username, display_name, role) "
                + "VALUES('uuid-1','operator','operator','steward')");
        }
        var hs = secret();
        PersonIdentityProvisioner.init(jdbc, () -> hs);

        var did = PersonIdentityProvisioner.provision("uuid-1", "operator").orElseThrow();
        assertTrue(did.startsWith("did:key:"), "a person must be a did:key, not a UUID");

        var resolver = PersonIdentityProvisioner.resolver().orElseThrow();
        assertEquals(did, resolver.resolve("uuid-1").orElseThrow(),
            "the legacy credential id must now reach the person");
        assertEquals(did, resolver.resolve("operator").orElseThrow(),
            "the username must now reach the same person");
    }

    /** The minted person can sign — the property PlayerAccount.create never gave. */
    @Test
    void the_minted_person_can_sign() throws Exception {
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("INSERT INTO users(id, username, display_name, role) "
                + "VALUES('uuid-1','operator','operator','steward')");
        }
        var hs = secret();
        PersonIdentityProvisioner.init(jdbc, () -> hs);
        var did = PersonIdentityProvisioner.provision("uuid-1", "operator").orElseThrow();

        var identity = PersonIdentityProvisioner.identities().orElseThrow()
            .findByDid(did).orElseThrow();
        var msg = "I am who I say".getBytes(StandardCharsets.UTF_8);
        assertTrue(identity.verify(msg, identity.sign(msg, hs)));
    }

    /** END TO END: registering through AuthService produces a person. */
    @Test
    void register_through_authservice_produces_a_person() {
        var hs = secret();
        PersonIdentityProvisioner.init(jdbc, () -> hs);
        var auth = new AuthService(jdbc);

        auth.register("operator", "correct-horse", "operator");

        var resolver = PersonIdentityProvisioner.resolver().orElseThrow();
        var did = resolver.resolve("operator");
        assertTrue(did.isPresent(), "a freshly registered account must have a person identity");
        assertTrue(did.get().startsWith("did:key:"));
    }

    /** Registration must still succeed when provisioning is off. */
    @Test
    void registration_still_works_without_provisioning() {
        var auth = new AuthService(jdbc);
        assertTrue(auth.register("operator", "correct-horse", "operator").isPresent(),
            "account creation must not depend on identity provisioning being wired");
    }

    /** Provisioning must never break account creation, even with a bad secret. */
    @Test
    void a_bad_household_secret_does_not_break_registration() {
        PersonIdentityProvisioner.init(jdbc, () -> new byte[7]); // wrong length
        var auth = new AuthService(jdbc);

        assertTrue(auth.register("operator", "correct-horse", "operator").isPresent(),
            "a person who cannot log in is worse than one provisioned late");
        assertTrue(PersonIdentityProvisioner.resolver().orElseThrow().resolve("operator").isEmpty());
    }

    /** provisionIfMissing must not mint a second identity for the same person. */
    @Test
    void provisionIfMissing_is_idempotent() throws Exception {
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("INSERT INTO users(id, username, display_name, role) "
                + "VALUES('uuid-1','operator','operator','steward')");
        }
        var hs = secret();
        PersonIdentityProvisioner.init(jdbc, () -> hs);

        var first = PersonIdentityProvisioner.provisionIfMissing("operator", "operator").orElseThrow();
        var second = PersonIdentityProvisioner.provisionIfMissing("operator", "operator").orElseThrow();
        assertEquals(first, second, "a person must not be minted twice — that is how one human becomes two");
    }

    /** Two different accounts must be two different people. */
    @Test
    void separate_accounts_get_separate_people() {
        var hs = secret();
        PersonIdentityProvisioner.init(jdbc, () -> hs);
        var auth = new AuthService(jdbc);

        auth.register("operator", "pw1", "operator");
        auth.register("someone", "pw2", "someone");

        var r = PersonIdentityProvisioner.resolver().orElseThrow();
        assertFalse(r.resolve("operator").orElseThrow().equals(r.resolve("someone").orElseThrow()));
    }
}

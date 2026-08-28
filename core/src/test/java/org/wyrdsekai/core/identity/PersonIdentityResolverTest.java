package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;

/**
 * The resolver's whole job is to be the one place that answers "which person is
 * this?" — and, crucially, to answer NOTHING when it does not know.
 *
 * <p>Every one of the four owner namespaces that showed up on a live household
 * (a username, a UUID, a mobile {@code 'local-user'} placeholder, and nothing)
 * came from code that needed an owner, could not resolve one, and substituted a
 * plausible string. These tests pin the opposite behaviour.</p>
 */
class PersonIdentityResolverTest {

    @TempDir Path tmp;
    private String jdbc;
    private PersonIdentityStore identities;
    private PersonIdentityResolver resolver;

    private static byte[] secret() {
        var s = new byte[32];
        new SecureRandom().nextBytes(s);
        return s;
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db");
        // A legacy install: users table with a UUID id and no DID anywhere.
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE users(
                  id TEXT PRIMARY KEY, username TEXT, password_hash TEXT,
                  display_name TEXT, description TEXT, role TEXT, created_at INTEGER)
                """);
            st.execute("INSERT INTO users(id, username, display_name, role, created_at) "
                + "VALUES('1d8d87ce-b7c0-46f4-b827-5fbaf797dbb3','operator','operator','steward',0)");
        }
        identities = new PersonIdentityStore(jdbc);
        resolver = new PersonIdentityResolver(jdbc);
    }

    /** THE contract: an unknown identifier resolves to nothing, not to a guess. */
    @Test
    void unresolvable_identifiers_return_empty() {
        assertTrue(resolver.resolve("local-user").isEmpty(), "the mobile placeholder must not resolve");
        assertTrue(resolver.resolve("nobody").isEmpty());
        assertTrue(resolver.resolve("did:key:zNotOurs").isEmpty(),
            "a well-formed DID we never minted must not resolve");
        assertTrue(resolver.resolve(null).isEmpty());
        assertTrue(resolver.resolve("   ").isEmpty());
    }

    /** Before linking, the legacy identifiers resolve to nothing — that is correct. */
    @Test
    void legacy_identifiers_do_not_resolve_until_linked() {
        assertTrue(resolver.resolve("operator").isEmpty(),
            "a bare username is not a person until it is bound to one");
        assertTrue(resolver.resolve("1d8d87ce-b7c0-46f4-b827-5fbaf797dbb3").isEmpty(),
            "a legacy account UUID is not a person until it is bound to one");
    }

    /** After linking, both the username and the legacy UUID reach the same person. */
    @Test
    void linked_username_and_uuid_both_resolve_to_the_person() throws Exception {
        var me = PersonIdentity.generate(secret());
        identities.save(me);
        resolver.linkUserToPerson("operator", me.did());

        assertEquals(me.did(), resolver.resolve("operator").orElseThrow(),
            "username must reach the person once bound");
        assertEquals(me.did(), resolver.resolve("1d8d87ce-b7c0-46f4-b827-5fbaf797dbb3").orElseThrow(),
            "the legacy UUID must reach the SAME person — this is what rebinding relies on");
        assertEquals(me.did(), resolver.resolve(me.did()).orElseThrow(),
            "a known person DID resolves to itself");
    }

    /** A DID we never minted must not become resolvable by linking. */
    @Test
    void cannot_link_a_credential_to_an_unknown_person() {
        assertThrows(IllegalArgumentException.class,
            () -> resolver.linkUserToPerson("operator", "did:key:zFabricated"));
    }

    /** isResolvable is the guard callers use before accepting a write. */
    @Test
    void isResolvable_mirrors_resolve() throws Exception {
        assertFalse(resolver.isResolvable("operator"));
        var me = PersonIdentity.generate(secret());
        identities.save(me);
        resolver.linkUserToPerson("operator", me.did());
        assertTrue(resolver.isResolvable("operator"));
    }

    /** The resolver adds users.did on construction — the credential→person link. */
    @Test
    void adds_the_users_did_column_on_a_legacy_schema() throws Exception {
        try (var conn = DriverManager.getConnection(jdbc);
             var rs = conn.getMetaData().getColumns(null, null, "users", "did")) {
            assertTrue(rs.next(), "users.did must be added so a credential can point at a person");
        }
    }

    /** Constructing twice must not fail — migrations run on every startup. */
    @Test
    void is_idempotent_across_restarts() {
        new PersonIdentityResolver(jdbc);
        new PersonIdentityResolver(jdbc);
        assertTrue(resolver.resolve("still-nobody").isEmpty());
    }

    /** Two people must not collide. */
    @Test
    void distinct_people_resolve_distinctly() throws Exception {
        var a = PersonIdentity.generate(secret());
        var b = PersonIdentity.generate(secret());
        identities.save(a);
        identities.save(b);

        assertEquals(a.did(), resolver.resolve(a.did()).orElseThrow());
        assertEquals(b.did(), resolver.resolve(b.did()).orElseThrow());
        assertFalse(a.did().equals(b.did()));
    }

    /** Round-trip through storage keeps the identity usable for signing. */
    @Test
    void stored_identity_can_still_sign_after_reload() throws Exception {
        var s = secret();
        var me = PersonIdentity.generate(s);
        identities.save(me);

        var reloaded = identities.findByDid(me.did()).orElseThrow();
        var msg = "attestation".getBytes(StandardCharsets.UTF_8);
        assertTrue(reloaded.verify(msg, reloaded.sign(msg, s)),
            "an identity must survive persistence with its signing ability intact");
    }
}

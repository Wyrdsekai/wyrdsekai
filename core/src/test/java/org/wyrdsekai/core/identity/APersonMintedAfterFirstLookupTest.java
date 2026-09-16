package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * On a fresh install a person's DID is minted at their first login, and their login id
 * has usually been looked up before that. The answer "no such person" must not outlive
 * the minting: it did, for the life of the process, and every door keyed on the
 * canonical id kept the login id until the next restart — after which the same person's
 * mail was split between two keys (0.3.4 install test, 2026-09-15).
 */
class APersonMintedAfterFirstLookupTest {

    private static final String LOGIN_ID = "1f56a2d4-0000-4000-8000-000000000003";
    private static final byte[] SECRET = new byte[32];

    @AfterEach
    void tearDown() {
        PersonIds.resetForTesting(null);
    }

    @Test
    @DisplayName("a lookup before the mint is not the answer after it")
    void theAnswerFollowsTheMint(@TempDir Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("INSERT INTO users(id, username, password_hash, display_name, role, created_at) "
                + "VALUES('" + LOGIN_ID + "','kaz','x','Kazuo','steward',0)");
        }
        var resolver = new PersonIdentityResolver(jdbc);
        PersonIds.resetForTesting(resolver);

        assertEquals(LOGIN_ID, PersonIds.canonical(LOGIN_ID), "nobody yet — the id as given");
        assertEquals("kaz", PersonIds.canonical("kaz"));

        var person = PersonIdentity.generate(SECRET);
        new PersonIdentityStore(jdbc).save(person);
        resolver.linkUserToPerson(LOGIN_ID, person.did());

        assertEquals(person.did(), PersonIds.canonical(LOGIN_ID), "the mint is the new answer, no restart needed");
        assertEquals(person.did(), PersonIds.canonical("kaz"), "by username too");
    }

    @Test
    @DisplayName("with no resolver at all nothing is remembered, so the first call cannot fix the answer")
    void noResolverRemembersNothing(@TempDir Path dir) throws Exception {
        PersonIds.resetForTesting(null);
        // resolverOrNull() builds one from the configured jdbc url when it can; in this JVM
        // it cannot, so the identifier comes back as given and is not cached.
        assertEquals(LOGIN_ID, PersonIds.canonical(LOGIN_ID));
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("INSERT INTO users(id, username, password_hash, display_name, role, created_at) "
                + "VALUES('" + LOGIN_ID + "','kaz','x','Kazuo','steward',0)");
        }
        var resolver = new PersonIdentityResolver(jdbc);
        var person = PersonIdentity.generate(SECRET);
        new PersonIdentityStore(jdbc).save(person);
        resolver.linkUserToPerson(LOGIN_ID, person.did());
        PersonIds.resetForTesting(resolver);
        assertEquals(person.did(), PersonIds.canonical(LOGIN_ID));
    }
}

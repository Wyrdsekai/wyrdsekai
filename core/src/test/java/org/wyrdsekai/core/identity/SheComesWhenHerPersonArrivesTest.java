package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A login and a bond name the same person in two different ID spaces.
 *
 * <h2>What went wrong</h2>
 * {@code CompanionActor.maybeJoinReturningPlayerStudy} decided whether an arriving player
 * was the bondholder with {@code bondholderDid.equals(msg.playerId())}. The bond stores a
 * {@code did:key}; {@code PlayerReturned} carries whatever the surface authenticated —
 * on SSH, the username. Live on staging 2026-08-22:
 *
 * <pre>Companion 'testwisp' not joining steward: bonded to did:key:z6Mkf7vM…</pre>
 *
 * where that key <i>is</i> steward. The test can never pass once a bond exists, so a
 * bonded companion stopped coming to meet her own person — the one case the behaviour is
 * for. It reads as her choosing not to come.
 */
class SheComesWhenHerPersonArrivesTest {

    private static final String STEWARD_DID =
        "did:key:z6Mkf7vM4gW7GFzEyBu634sqBut5YC1B1vM53a98NZmm6d9B";

    @TempDir Path tmp;
    private String jdbc;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db");
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE users(
                  id TEXT PRIMARY KEY, username TEXT, password_hash TEXT,
                  display_name TEXT, description TEXT, role TEXT, created_at INTEGER,
                  did TEXT)
                """);
            // A provisioned household: the steward has a DID, which is what a bond stores.
            st.execute("INSERT INTO users(id, username, display_name, role, created_at, did) "
                + "VALUES('u-1','steward','steward','steward',0,'" + STEWARD_DID + "')");
        }
        PersonIds.resetForTesting(new PersonIdentityResolver(jdbc));
    }

    @AfterEach
    void tearDown() {
        PersonIds.resetForTesting(null);
    }

    @Test
    @DisplayName("the username a surface authenticates and the DID a bond stores are one person")
    void aUsernameAndItsDidAreTheSamePerson() {
        var did = new PersonIdentityResolver(jdbc).resolve("steward").orElseThrow();

        assertThat(did)
            .as("the bond stores a key, the login carries a name — different strings")
            .isNotEqualTo("steward");
        assertThat(PersonIds.samePerson(did, "steward"))
            .as("and they are nevertheless the same person, which is the whole question")
            .isTrue();
    }

    @Test
    @DisplayName("a different person is still a different person")
    void anotherLoginIsNotTheBondholder() {
        var did = new PersonIdentityResolver(jdbc).resolve("steward").orElseThrow();
        assertThat(PersonIds.samePerson(did, "someone-else")).isFalse();
        assertThat(PersonIds.samePerson(did, null)).isFalse();
    }
}

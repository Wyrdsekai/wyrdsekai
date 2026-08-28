package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration must move the PERSON's references and leave alone the two things
 * a field-update would corrupt: audit history and local credentials.
 */
class PersonIdentityMigrationTest {

    private static final String LEGACY = "1d8d87ce-b7c0-46f4-b827-5fbaf797dbb3";

    @TempDir Path tmp;
    private String jdbc;
    private byte[] hs;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db");
        hs = new byte[32];
        new SecureRandom().nextBytes(hs);

        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("CREATE TABLE users(id TEXT PRIMARY KEY, username TEXT UNIQUE, "
                + "password_hash TEXT, display_name TEXT, description TEXT, role TEXT, created_at INTEGER)");
            st.execute("INSERT INTO users(id,username,display_name,role) VALUES('" + LEGACY
                + "','operator','operator','steward')");
            // live person references
            st.execute("CREATE TABLE bonds(bond_id TEXT, agent_a_did TEXT, agent_b_did TEXT)");
            st.execute("INSERT INTO bonds VALUES('b1','did:key:zCompanion','" + LEGACY + "')");
            st.execute("CREATE TABLE residency(did TEXT, zone TEXT)");
            st.execute("INSERT INTO residency VALUES('" + LEGACY + "','home')");
            st.execute("CREATE TABLE inventory(entity_id TEXT, item TEXT)");
            st.execute("INSERT INTO inventory VALUES('" + LEGACY + "','lantern')");
            st.execute("CREATE TABLE bondholder_engagement(companion_did TEXT, bondholder_did TEXT)");
            st.execute("INSERT INTO bondholder_engagement VALUES('did:key:zC','" + LEGACY + "')");
            // history + credentials — must NOT move
            st.execute("CREATE TABLE audit_log(actor TEXT, home_owner TEXT, what TEXT)");
            st.execute("INSERT INTO audit_log VALUES('" + LEGACY + "','" + LEGACY + "','opened door')");
            st.execute("CREATE TABLE sessions(token TEXT, user_id TEXT)");
            st.execute("INSERT INTO sessions VALUES('tok','" + LEGACY + "')");
            st.execute("CREATE TABLE user_ssh_keys(user_id TEXT, key TEXT)");
            st.execute("INSERT INTO user_ssh_keys VALUES('" + LEGACY + "','ssh-rsa AAA')");
        }
        PersonIdentityProvisioner.reset();
    }

    @AfterEach
    void tearDown() {
        PersonIdentityProvisioner.reset();
    }

    private String one(String sql) throws Exception {
        try (var conn = DriverManager.getConnection(jdbc);
             var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Live person references follow the person. */
    @Test
    void live_references_are_rebound_to_the_person() throws Exception {
        var r = PersonIdentityMigration.run(jdbc, LEGACY, "operator", () -> hs);

        assertTrue(r.ran());
        assertNotNull(r.personDid());
        assertTrue(r.personDid().startsWith("did:key:"));

        assertEquals(r.personDid(), one("SELECT agent_b_did FROM bonds"), "bond must follow the person");
        assertEquals(r.personDid(), one("SELECT did FROM residency"), "residency must follow");
        assertEquals(r.personDid(), one("SELECT entity_id FROM inventory"), "ownership must follow");
        assertEquals(r.personDid(), one("SELECT bondholder_did FROM bondholder_engagement"));
    }

    /** Audit history must NOT be rewritten — that would falsify the record. */
    @Test
    void audit_history_is_left_untouched() throws Exception {
        PersonIdentityMigration.run(jdbc, LEGACY, "operator", () -> hs);

        assertEquals(LEGACY, one("SELECT actor FROM audit_log"),
            "rewriting an audit actor asserts a different person acted — that is falsification");
        assertEquals(LEGACY, one("SELECT home_owner FROM audit_log"));
    }

    /** Local credentials must NOT be repointed at the person. */
    @Test
    void local_credentials_are_left_untouched() throws Exception {
        PersonIdentityMigration.run(jdbc, LEGACY, "operator", () -> hs);

        assertEquals(LEGACY, one("SELECT id FROM users"),
            "users.id is a credential for this machine, not the person");
        assertEquals(LEGACY, one("SELECT user_id FROM sessions"));
        assertEquals(LEGACY, one("SELECT user_id FROM user_ssh_keys"));
    }

    /** History stays readable: the old id resolves forward through the attestation. */
    @Test
    void the_old_identifier_still_resolves_to_the_person() throws Exception {
        var r = PersonIdentityMigration.run(jdbc, LEGACY, "operator", () -> hs);

        var atts = new RebindAttestationStore(jdbc).all();
        assertFalse(atts.isEmpty(), "a rebind must leave an attestation");

        var start = atts.get(0).fromDid();
        assertEquals(r.personDid(), new RebindAttestationStore(jdbc).resolveCurrent(start),
            "an audit row written under the old identity must still reach the person");
    }

    /** Idempotent — migrations run on every startup. */
    @Test
    void running_twice_changes_nothing_further() throws Exception {
        var first = PersonIdentityMigration.run(jdbc, LEGACY, "operator", () -> hs);
        var second = PersonIdentityMigration.run(jdbc, LEGACY, "operator", () -> hs);

        assertTrue(first.ran());
        assertFalse(second.ran(), "a completed migration must not run again");
        assertEquals(first.personDid(), one("SELECT agent_b_did FROM bonds"),
            "a second run must not disturb the first");
    }

    /** A partial install missing tables must not abort the run. */
    @Test
    void missing_tables_do_not_abort_the_migration() throws Exception {
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("DROP TABLE inventory");
            st.execute("DROP TABLE residency");
        }
        var r = PersonIdentityMigration.run(jdbc, LEGACY, "operator", () -> hs);

        assertTrue(r.ran(), "absent tables are normal across installs and must be skipped");
        assertEquals(r.personDid(), one("SELECT agent_b_did FROM bonds"));
    }

    /** After migrating, the resolver reaches the person from either legacy string. */
    @Test
    void resolver_reaches_the_person_from_legacy_identifiers() throws Exception {
        var r = PersonIdentityMigration.run(jdbc, LEGACY, "operator", () -> hs);
        var resolver = PersonIdentityProvisioner.resolver().orElseThrow();

        assertEquals(r.personDid(), resolver.resolve(LEGACY).orElseThrow());
        assertEquals(r.personDid(), resolver.resolve("operator").orElseThrow());
    }

    /** The preserved list is explicit, so the choice is reviewable. */
    @Test
    void preserved_tables_are_documented() {
        var preserved = PersonIdentityMigration.preservedTables();
        assertTrue(preserved.containsKey("audit_log"));
        assertTrue(preserved.containsKey("sessions"));
        assertTrue(preserved.containsKey("users"));
    }
}

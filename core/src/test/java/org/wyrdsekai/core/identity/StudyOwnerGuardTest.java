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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard is the fix for the actual defect: code that needed an owner, could
 * not resolve one, and wrote a plausible string instead of failing.
 */
class StudyOwnerGuardTest {

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
            st.execute("INSERT INTO users(id,username,display_name,role) "
                + "VALUES('uuid-1','operator','operator','steward')");
            st.execute("INSERT INTO users(id,username,display_name,role) "
                + "VALUES('uuid-2','someone','someone','member')");
        }
        PersonIdentityProvisioner.reset();
    }

    @AfterEach
    void tearDown() {
        PersonIdentityProvisioner.reset();
        AgentIdentityProvisioner.reset();
    }

    /** Enable provisioning, a person present, and mint a real agent identity. */
    private String enableWithAPersonAndAnAgent() {
        enableWithAPersonPresent();
        AgentIdentityProvisioner.reset();
        AgentIdentityProvisioner.init(jdbc, () -> hs);
        return AgentIdentityProvisioner.mint("companion-test").did();
    }

    /**
     * An agent owns its own Study content — its journal, its notes.
     *
     * <p>The guard refused these because an agent DID never resolves to a PERSON, which
     * conflated "not a person" with "not resolvable". Live cost on a household node
     * (2026-08-20): every Study write the companion made under her own DID was refused,
     * including her journal, and the raw exception reached a ReAct loop as a tool failure
     * that she then narrated aloud. It is also a second, independent cause of the 08-19
     * finding that 107 write_journal enactments produced zero journal entries.
     */
    @Test
    void accepts_a_registered_agent_as_an_owner_of_its_own_content() {
        var agentDid = enableWithAPersonAndAnAgent();
        assertEquals(agentDid, StudyOwnerGuard.require(agentDid),
            "a companion must be able to own its own journal");
    }

    /** ...but only one this node actually minted. */
    @Test
    void refuses_a_did_shaped_string_that_is_not_a_registered_agent() {
        enableWithAPersonAndAnAgent();
        assertThrows(StudyOwnerGuard.UnresolvableOwnerException.class,
            () -> StudyOwnerGuard.require("did:key:z6MkNotAnAgentOnThisNode"),
            "looking like a DID must not be enough");
    }

    /** The original defect must stay closed — a guessed string is still refused. */
    @Test
    void the_agent_door_does_not_reopen_the_placeholder_hole() {
        enableWithAPersonAndAnAgent();
        assertThrows(StudyOwnerGuard.UnresolvableOwnerException.class,
            () -> StudyOwnerGuard.require("local-user"));
        assertThrows(StudyOwnerGuard.UnresolvableOwnerException.class,
            () -> StudyOwnerGuard.require("operator"));
    }


    /**
     * Enable provisioning AND mint a person, leaving 'operator'/'uuid-1' unbound.
     *
     * <p>The guard deliberately does not enforce on a node that holds no people:
     * there is nothing to resolve against, and refusing every write would brick
     * a fresh install before its first account exists. So a test of refusal has
     * to model a node that HAS someone.</p>
     */
    private void enableWithAPersonPresent() {
        PersonIdentityProvisioner.init(jdbc, () -> hs);
        PersonIdentityProvisioner.provision("uuid-2", "someone");
    }

    /** Before migration, behaviour is unchanged — nothing half-refuses. */
    @Test
    void passes_everything_through_until_provisioning_is_enabled() {
        assertEquals("operator", StudyOwnerGuard.require("operator"));
        assertEquals("local-user", StudyOwnerGuard.require("local-user"));
        assertEquals(null, StudyOwnerGuard.require(null));
    }

    /** The mobile placeholder must be refused outright — it writes journals. */
    @Test
    void refuses_the_mobile_placeholder() {
        enableWithAPersonPresent();
        var e = assertThrows(StudyOwnerGuard.UnresolvableOwnerException.class,
            () -> StudyOwnerGuard.require("local-user"));
        assertTrue(e.getMessage().contains("not a person"));
    }

    /** A Unix username that maps to nobody must be refused — the 13.7M-row bug. */
    @Test
    void refuses_an_unresolvable_username() {
        enableWithAPersonPresent();
        assertThrows(StudyOwnerGuard.UnresolvableOwnerException.class,
            () -> StudyOwnerGuard.require("operator"),
            "an unbound username must not silently become an owner");
    }

    /** Empty and blank owners must be refused. */
    @Test
    void refuses_missing_owners() {
        PersonIdentityProvisioner.init(jdbc, () -> hs);
        assertThrows(StudyOwnerGuard.UnresolvableOwnerException.class,
            () -> StudyOwnerGuard.require(null));
        assertThrows(StudyOwnerGuard.UnresolvableOwnerException.class,
            () -> StudyOwnerGuard.require("   "));
    }

    /** Once a person exists, their identifiers are accepted and canonicalised. */
    @Test
    void accepts_a_resolvable_owner_and_returns_the_person_did() {
        PersonIdentityProvisioner.init(jdbc, () -> hs);
        var did = PersonIdentityProvisioner.provision("uuid-1", "operator").orElseThrow();

        assertEquals(did, StudyOwnerGuard.require("operator"),
            "a username bound to a person must resolve to the person DID");
        assertEquals(did, StudyOwnerGuard.require("uuid-1"),
            "the legacy id must canonicalise to the same person");
        assertEquals(did, StudyOwnerGuard.require(did));
    }

    /** Reads must degrade rather than throw — a bad owner simply matches nothing. */
    @Test
    void read_path_does_not_throw() {
        PersonIdentityProvisioner.init(jdbc, () -> hs);
        assertEquals("local-user", StudyOwnerGuard.forRead("local-user"),
            "reads must not raise — they should just match nothing");
    }

    /** isAcceptable mirrors require without throwing. */
    @Test
    void isAcceptable_mirrors_require() {
        enableWithAPersonPresent();
        assertFalse(StudyOwnerGuard.isAcceptable("local-user"));
        assertFalse(StudyOwnerGuard.isAcceptable("operator"));

        var did = PersonIdentityProvisioner.provision("uuid-1", "operator").orElseThrow();
        assertTrue(StudyOwnerGuard.isAcceptable(did));
    }

    /** The refusal must say what to do, not just fail. */
    @Test
    void refusal_message_is_actionable() {
        enableWithAPersonPresent();
        var e = assertThrows(StudyOwnerGuard.UnresolvableOwnerException.class,
            () -> StudyOwnerGuard.require("nobody-at-all"));
        // Wording updated 2026-08-20 when agents became valid owners of their own
        // content: the refusal now names BOTH doors that were tried, which is more
        // actionable than "does not resolve to any person" was — that phrasing sent a
        // reader looking for a person who was never going to exist.
        assertTrue(e.getMessage().contains("neither a person"),
            "the message should say a person was looked for");
        assertTrue(e.getMessage().contains("registered agent"),
            "...and that the agent door was tried too");
        assertTrue(e.getMessage().contains("several different strings"),
            "the message should explain the failure mode it is preventing");
    }

    /**
     * A node with provisioning on but NO people yet must not refuse everything —
     * that would brick a fresh install between zone bootstrap and first account.
     */
    @Test
    void does_not_enforce_before_anyone_exists() {
        PersonIdentityProvisioner.init(jdbc, () -> hs);
        assertEquals("operator", StudyOwnerGuard.require("operator"),
            "with no people on the node there is nothing to resolve against");
        assertEquals("local-user", StudyOwnerGuard.require("local-user"));
    }
}

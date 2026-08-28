package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closing the record after a companion fold.
 *
 * <p>A rebind deliberately leaves audit history under the old identity — rewriting
 * {@code audit_log.actor} would assert a different agent acted. Those rows are
 * meant to be read <em>through</em> an attestation; without one they point at an
 * identity that no longer answers. On the live household that was 168 rows after
 * the 2026-08-08 merge.</p>
 *
 * <p>Signing needs the zone master, which exists correctly only inside a booted
 * node, while the rebind can run offline against a database copy. So this
 * reconciles afterwards — and must be safe to run on every boot.</p>
 */
class AgentRebindAttestorTest {

    @TempDir Path tmp;
    private String jdbc;
    private byte[] secret;

    private static final String FOLDED = "did:key:z6MkFoldedOne";
    private static final String TRUNK  = "did:key:z6MkSurvivor";

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try (var c = DriverManager.getConnection(jdbc); var st = c.createStatement()) {
            st.execute("CREATE TABLE companions(did TEXT PRIMARY KEY, entity_id TEXT,"
                + " name TEXT, archived INT DEFAULT 0)");
            st.execute("INSERT INTO companions VALUES('" + TRUNK + "','companion-x','x',0)");
            st.execute("INSERT INTO companions VALUES('" + FOLDED + "','companion-x','x',1)");
        }
    }

    private PersonIdentity seedPerson() throws Exception {
        var store = new PersonIdentityStore(jdbc);
        var p = PersonIdentity.generate(secret);
        store.save(p);
        return p;
    }

    /** THE case: an archived companion with a live sibling gets attested. */
    @Test
    void a_folded_companion_gets_a_witnessed_attestation() throws Exception {
        var person = seedPerson();

        var r = AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        assertThat(r.pending()).isEqualTo(1);
        assertThat(r.attested()).isEqualTo(1);

        var saved = new RebindAttestationStore(jdbc).all();
        assertThat(saved).hasSize(1);
        var att = saved.getFirst();
        assertThat(att.fromDid()).isEqualTo(FOLDED);
        assertThat(att.toDid()).isEqualTo(TRUNK);
        assertThat(att.isWitnessed())
            .as("this companion predates AgentIdentityStore and holds no key, so the "
                + "honest record is the steward's observation — see SelfIssuedAgentRebindTest "
                + "for the stronger claim a keyed companion makes instead")
            .isTrue();
        assertThat(att.verifyWitnessed(person)).isTrue();
    }

    /** History under the old DID must now resolve forward. */
    @Test
    void the_old_identity_resolves_to_the_survivor() throws Exception {
        seedPerson();
        AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        var chain = new RebindAttestationStore(jdbc).all();

        assertThat(RebindAttestation.resolveCurrent(FOLDED, chain))
            .as("an audit row under the folded DID must resolve to who she is now")
            .isEqualTo(TRUNK);
    }

    /** Safe on every boot — never re-issues. */
    @Test
    void is_idempotent_across_boots() throws Exception {
        seedPerson();
        AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        var second = AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        assertThat(second.attested()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(new RebindAttestationStore(jdbc).all()).hasSize(1);
    }

    /** A retirement is not a fold — an archive with no survivor is left alone. */
    @Test
    void an_archived_companion_with_no_survivor_is_not_a_rebind() throws Exception {
        seedPerson();
        try (var c = DriverManager.getConnection(jdbc); var st = c.createStatement()) {
            st.execute("UPDATE companions SET archived = 1");   // nobody survives
        }

        var r = AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        assertThat(r.pending()).as("retiring a companion is not becoming another").isZero();
        assertThat(new RebindAttestationStore(jdbc).all()).isEmpty();
    }

    /** Nothing archived, nothing to do. */
    @Test
    void a_household_with_no_folds_does_nothing() throws Exception {
        seedPerson();
        try (var c = DriverManager.getConnection(jdbc); var st = c.createStatement()) {
            st.execute("UPDATE companions SET archived = 0");
        }

        assertThat(AgentRebindAttestor.reconcile(jdbc, () -> secret, null).pending()).isZero();
    }

    /** No person to witness → report it, never fabricate an attester. */
    @Test
    void without_a_person_it_reports_rather_than_guesses() {
        var r = AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        assertThat(r.pending()).isEqualTo(1);
        assertThat(r.attested()).isZero();
        assertThat(new RebindAttestationStore(jdbc).all()).isEmpty();
    }

    /** No secret → sign nothing, and say so. */
    @Test
    void without_the_household_secret_it_signs_nothing() throws Exception {
        seedPerson();

        var r = AgentRebindAttestor.reconcile(jdbc, () -> null, null);

        assertThat(r.attested()).isZero();
        assertThat(new RebindAttestationStore(jdbc).all()).isEmpty();
    }

    /** With several people it must not put an arbitrary name on the record. */
    @Test
    void refuses_to_pick_an_arbitrary_attester() throws Exception {
        seedPerson();
        seedPerson();

        var r = AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        assertThat(r.attested())
            .as("who attested is exactly what this record exists to state")
            .isZero();
    }

    /** An explicitly named attester is used even when several people exist. */
    @Test
    void an_explicit_attester_is_honoured() throws Exception {
        seedPerson();
        var chosen = seedPerson();

        var r = AgentRebindAttestor.reconcile(jdbc, () -> secret, chosen.did());

        assertThat(r.attested()).isEqualTo(1);
        assertThat(new RebindAttestationStore(jdbc).all().getFirst().attesterDid())
            .isEqualTo(chosen.did());
    }
}

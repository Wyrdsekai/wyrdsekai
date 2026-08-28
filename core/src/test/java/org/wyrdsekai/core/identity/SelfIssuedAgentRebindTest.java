package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.List;

/**
 * A companion declaring, in its own hand, what it became.
 *
 * <p>The 2026-08-08 merge could not record this. Both companions had DIDs with
 * no private key behind them, so the only honest thing to write down was the
 * steward's observation — "I saw that this became that" — which is a weaker
 * claim than "I declare I became them" and had to stay distinguishable from it.
 * With {@link AgentIdentityStore} in place the stronger claim is available to
 * anyone born since, and {@link AgentRebindAttestor} should reach for it first.</p>
 *
 * <p>The two must never verify as each other. A witnessed claim accepted as
 * self-issued would let a household assert, on a companion's behalf, something
 * only the companion can truthfully say.</p>
 */
class SelfIssuedAgentRebindTest {

    @TempDir Path tmp;
    private String jdbc;
    private byte[] secret;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("CREATE TABLE companions(did TEXT PRIMARY KEY, entity_id TEXT,"
                + " archived INT DEFAULT 0)");
        }
        AgentIdentityProvisioner.init(jdbc, () -> secret);
    }

    @AfterEach
    void tearDown() {
        AgentIdentityProvisioner.reset();
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(jdbc);
    }

    /** THE case: an agent with a key can say it itself, and it verifies. */
    @Test
    void an_agent_with_a_key_declares_its_own_rebind() throws Exception {
        var from = AgentIdentity.generate(secret);
        var to = AgentIdentity.generate(secret);

        var att = RebindAttestation.issueSelf(from, to.did(), secret);

        assertThat(att.isWitnessed()).isFalse();
        assertThat(att.verify(from)).isTrue();
    }

    /** Signed content is the pair of identities — not any other pair. */
    @Test
    void the_claim_does_not_transfer_to_another_identity() throws Exception {
        var from = AgentIdentity.generate(secret);
        var to = AgentIdentity.generate(secret);
        var stranger = AgentIdentity.generate(secret);

        var att = RebindAttestation.issueSelf(from, to.did(), secret);

        assertThat(att.verify(stranger)).isFalse();
    }

    /** A witnessed claim must not pass as a self-declaration. */
    @Test
    void a_witnessed_claim_is_not_a_self_declaration() throws Exception {
        var person = PersonIdentity.generate(secret);
        var agent = AgentIdentity.generate(secret);

        var witnessed = RebindAttestation.issueWitnessed(
            person, agent.did(), "did:key:z6MkTheNewOne", secret);

        assertThat(witnessed.isWitnessed()).isTrue();
        assertThat(witnessed.verify(agent))
            .as("accepting this would let the household speak in her voice")
            .isFalse();
        assertThat(witnessed.verifyWitnessed(person)).isTrue();
    }

    /** A keyless agent must be refused loudly rather than produce an empty claim. */
    @Test
    void a_keyless_agent_cannot_self_issue() throws Exception {
        var keyless = new AgentIdentity("did:key:z6MkBornBeforeAnyOfThis",
            new byte[32], null, List.of(), Instant.now(), null, null);

        assertThatThrownBy(() ->
            RebindAttestation.issueSelf(keyless, "did:key:z6MkTheNewOne", secret))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("issueWitnessed");
    }

    /** Rebinding onto yourself is not a rebind. */
    @Test
    void a_rebind_must_change_identity() throws Exception {
        var from = AgentIdentity.generate(secret);

        assertThatThrownBy(() -> RebindAttestation.issueSelf(from, from.did(), secret))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The reconciler prefers the companion's own word when one is available. */
    @Test
    void the_reconciler_prefers_self_issued() throws Exception {
        var people = new PersonIdentityStore(jdbc);
        var person = PersonIdentity.generate(secret);
        people.save(person);

        var folded = AgentIdentityProvisioner.mint("entity-ari");
        var trunk = AgentIdentityProvisioner.mint(null);
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("INSERT INTO companions(did, entity_id, archived) VALUES('"
                + folded.did() + "', 'entity-ari', 1)");
            st.execute("INSERT INTO companions(did, entity_id, archived) VALUES('"
                + trunk.did() + "', 'entity-ari', 0)");
        }

        var result = AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        assertThat(result.attested()).isEqualTo(1);
        assertThat(result.notes()).anyMatch(n -> n.contains("self-issued"));

        var stored = new RebindAttestationStore(jdbc).all();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().isWitnessed()).isFalse();
        assertThat(stored.getFirst().verify(
            AgentIdentityProvisioner.find(folded.did()).orElseThrow())).isTrue();
    }

    /** A companion born before any of this still gets a witnessed record. */
    @Test
    void a_keyless_fold_still_falls_back_to_a_witness() throws Exception {
        var people = new PersonIdentityStore(jdbc);
        var person = PersonIdentity.generate(secret);
        people.save(person);

        try (var c = conn(); var st = c.createStatement()) {
            st.execute("INSERT INTO companions(did, entity_id, archived) "
                + "VALUES('did:key:z6MkTheOldKeylessOne', 'entity-ari', 1)");
            st.execute("INSERT INTO companions(did, entity_id, archived) "
                + "VALUES('did:key:z6MkTheSurvivor', 'entity-ari', 0)");
        }

        var result = AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        assertThat(result.attested()).isEqualTo(1);
        assertThat(result.notes()).anyMatch(n -> n.contains("witnessed by"));
        assertThat(new RebindAttestationStore(jdbc).all().getFirst().isWitnessed()).isTrue();
    }

    /** Re-running must not re-issue — and must not upgrade an existing record. */
    @Test
    void reconciliation_is_idempotent() throws Exception {
        var people = new PersonIdentityStore(jdbc);
        people.save(PersonIdentity.generate(secret));
        var folded = AgentIdentityProvisioner.mint("entity-ari");
        var trunk = AgentIdentityProvisioner.mint(null);
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("INSERT INTO companions(did, entity_id, archived) VALUES('"
                + folded.did() + "', 'entity-ari', 1)");
            st.execute("INSERT INTO companions(did, entity_id, archived) VALUES('"
                + trunk.did() + "', 'entity-ari', 0)");
        }

        AgentRebindAttestor.reconcile(jdbc, () -> secret, null);
        var second = AgentRebindAttestor.reconcile(jdbc, () -> secret, null);

        assertThat(second.attested()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(new RebindAttestationStore(jdbc).all()).hasSize(1);
    }
}

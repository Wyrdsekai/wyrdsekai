package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;

/**
 * Recording a rebind that the folded identity could not sign for itself.
 *
 * <p>{@link RebindAttestation#issue} means <em>"I declare I became them"</em>, and
 * only the old identity can say it — that is what makes it worth anything. A
 * companion cannot: {@code CompanionActor} mints a DID with
 * {@code DidKey.generate()}, keeps the public half, and lets the private key fall
 * out of scope. There is no {@code agent_identities} table and
 * {@code encryption_keys} is empty on the live household, so a folded companion
 * has nothing to sign with — the same defect the person-identity work fixed for
 * {@code PlayerAccount.create()}, still live on the agent side.</p>
 *
 * <p>So the steward witnesses it instead. That is a weaker claim, and these tests
 * exist to make sure it stays <b>visibly</b> weaker: a witnessed attestation must
 * never verify as a self-issued one, and must not lose who made it in storage.</p>
 */
class WitnessedRebindTest {

    @TempDir Path tmp;
    private String jdbc;
    private byte[] secret;
    private PersonIdentity steward;
    private PersonIdentity someoneElse;

    private static final String FOLDED = "did:key:z6MkFoldedCompanion";
    private static final String TRUNK  = "did:key:z6MkSurvivingCompanion";

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("att.db").toAbsolutePath();
        secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        steward = PersonIdentity.generate(secret);
        someoneElse = PersonIdentity.generate(secret);
    }

    /** THE case: a steward can record that one companion became another. */
    @Test
    void a_steward_can_witness_a_companion_rebind() throws Exception {
        var att = RebindAttestation.issueWitnessed(steward, FOLDED, TRUNK, secret);

        assertThat(att.fromDid()).isEqualTo(FOLDED);
        assertThat(att.toDid()).isEqualTo(TRUNK);
        assertThat(att.isWitnessed()).isTrue();
        assertThat(att.attesterDid()).isEqualTo(steward.did());
        assertThat(att.verifyWitnessed(steward))
            .as("the witness's own signature must check out")
            .isTrue();
    }

    /** A witnessed claim must NOT pass as the old identity's own declaration. */
    @Test
    void a_witnessed_attestation_never_verifies_as_self_issued() throws Exception {
        var att = RebindAttestation.issueWitnessed(steward, FOLDED, TRUNK, secret);

        assertThat(att.verify(steward))
            .as("'I saw this happen' must not read as 'I declare I became them'")
            .isFalse();
    }

    /** And a self-issued one must not pass as witnessed. */
    @Test
    void a_self_issued_attestation_is_not_witnessed() throws Exception {
        var from = PersonIdentity.generate(secret);
        var to = PersonIdentity.generate(secret);

        var att = RebindAttestation.issue(from, to, secret);

        assertThat(att.isWitnessed()).isFalse();
        assertThat(att.attesterDid()).isNull();
        assertThat(att.verify(from)).isTrue();
        assertThat(att.verifyWitnessed(from)).isFalse();
    }

    /** Someone else's key must not validate the steward's witness. */
    @Test
    void another_identity_cannot_validate_the_witness() throws Exception {
        var att = RebindAttestation.issueWitnessed(steward, FOLDED, TRUNK, secret);

        assertThat(att.verifyWitnessed(someoneElse)).isFalse();
    }

    /** Tampering with either endpoint must break the signature. */
    @Test
    void the_signature_covers_both_identities() throws Exception {
        var att = RebindAttestation.issueWitnessed(steward, FOLDED, TRUNK, secret);
        var forged = new RebindAttestation(
            FOLDED, "did:key:z6MkSomewhereElse", att.issuedAt(),
            att.signature(), att.attesterDid());

        assertThat(forged.verifyWitnessed(steward))
            .as("redirecting the destination must not survive verification")
            .isFalse();
    }

    /** An attestation that forgets who made it is worse than none. */
    @Test
    void the_witness_survives_a_storage_round_trip() throws Exception {
        var store = new RebindAttestationStore(jdbc);
        var att = RebindAttestation.issueWitnessed(steward, FOLDED, TRUNK, secret);

        store.save(att);
        var back = store.all();

        assertThat(back).hasSize(1);
        var r = back.getFirst();
        assertThat(r.isWitnessed()).isTrue();
        assertThat(r.attesterDid()).isEqualTo(steward.did());
        assertThat(r.verifyWitnessed(steward))
            .as("must still verify after the round-trip")
            .isTrue();
    }

    /** Self-issued rows must round-trip as self-issued, not become witnessed. */
    @Test
    void self_issued_rows_round_trip_unchanged() throws Exception {
        var store = new RebindAttestationStore(jdbc);
        var from = PersonIdentity.generate(secret);
        var to = PersonIdentity.generate(secret);
        store.save(RebindAttestation.issue(from, to, secret));

        var r = store.all().getFirst();

        assertThat(r.isWitnessed()).isFalse();
        assertThat(r.attesterDid()).isNull();
        assertThat(r.verify(from)).isTrue();
    }

    /** The chain resolution must work for witnessed links too. */
    @Test
    void a_witnessed_link_still_resolves_the_chain() throws Exception {
        var att = RebindAttestation.issueWitnessed(steward, FOLDED, TRUNK, secret);

        assertThat(RebindAttestation.resolveCurrent(FOLDED, List.of(att)))
            .as("an audit row under the folded DID must resolve to the survivor")
            .isEqualTo(TRUNK);
    }

    /** Degenerate arguments are refused. */
    @Test
    void refuses_a_rebind_that_changes_nothing() {
        assertThatThrownBy(() -> RebindAttestation.issueWitnessed(steward, TRUNK, TRUNK, secret))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RebindAttestation.issueWitnessed(steward, null, TRUNK, secret))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

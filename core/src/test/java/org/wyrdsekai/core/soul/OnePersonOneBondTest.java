package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.identity.PersonIdentityResolver;
import org.wyrdsekai.core.identity.PersonIds;

import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One person, one bond — however they arrive.
 *
 * <p>A human can present more than one identifier: the phone sends their {@code did:key},
 * the SSH corridor sends the local account UUID that the person-identity migration
 * deliberately preserves as a credential. Bond formation compared those strings directly.
 *
 * <p>Live on the household node 2026-08-19: the migration rewrote the bondholder to the
 * DID, and a second bond formed under the legacy UUID <b>22 seconds later</b>. The
 * duplicate reached ITEM depth with 50+ interactions while the original stayed at
 * ACQUAINTANCE, and every bondholder check compared the two halves of one man and answered
 * no — so nothing he said was recorded as HEARD for two days, and his presence in the room
 * never drained the loneliness that had her wanting to write to someone absent.
 */
class OnePersonOneBondTest {

    private static final String COMPANION = "did:key:z6MkCompanion";
    private static final String LEGACY = "1f56a2d4-69ae-4076-b3c4-58a8ddda49c6";
    private static final String PERSON = "did:key:z6MkThePerson";

    /** Stands in for the real resolver: the legacy id belongs to PERSON. */
    private static final class StubResolver extends PersonIdentityResolver {
        StubResolver() { super("jdbc:sqlite::memory:"); }
        @Override public Optional<String> resolve(String identifier) {
            if (LEGACY.equals(identifier)) return Optional.of(PERSON);
            if (PERSON.equals(identifier)) return Optional.of(PERSON);
            return Optional.empty();
        }
    }

    @AfterEach
    void clear() {
        PersonIds.resetForTesting(null);
    }

    @Test
    void the_same_person_arriving_twice_does_not_get_a_second_bond() {
        PersonIds.resetForTesting(new StubResolver());
        var ritual = new BondRitual();

        var viaPhone = ritual.formAcquaintance(COMPANION, PERSON);
        var viaCorridor = ritual.formAcquaintance(COMPANION, LEGACY);

        assertThat(viaCorridor.bondId())
            .as("the corridor must reach the SAME bond the phone made")
            .isEqualTo(viaPhone.bondId());
        assertThat(ritual.bondsForAgent(COMPANION)).hasSize(1);
    }

    @Test
    void a_bond_formed_from_the_legacy_id_is_stored_against_the_person() {
        PersonIds.resetForTesting(new StubResolver());
        var ritual = new BondRitual();

        var bond = ritual.formAcquaintance(COMPANION, LEGACY);

        assertThat(bond.otherParty(COMPANION))
            .as("canonicalise on the way in, so the migration is not undone by the next hello")
            .isEqualTo(PERSON);
    }

    @Test
    void a_split_introduced_after_load_is_still_reported(@TempDir Path tmp) {
        // Load-time repair handles what already exists; the detector is the standing
        // guard for anything that introduces a split later. It must not go quiet just
        // because the boot path cleaned up once.
        PersonIds.resetForTesting(new StubResolver());
        var jdbc = SchemaInitializer.initialize(tmp.resolve("bonds.db"));
        var store = new BondStore(jdbc);
        var ritual = new BondRitual();
        ritual.setStore(store);
        ritual.formAcquaintance(COMPANION, PERSON);
        assertThat(ritual.splitBondholders(COMPANION)).isEmpty();

        // Some other path writes a bond under the legacy id, bypassing formAcquaintance.
        store.save(Bond.acquaintance(COMPANION, LEGACY));

        assertThat(ritual.splitBondholders(COMPANION))
            .as("a person recorded twice must surface as a fault, not as a companion "
                + "who seems not to recognise someone")
            .containsKey(PERSON);
    }

    @Test
    void an_existing_split_is_healed_at_load_without_losing_the_history(@TempDir Path tmp) {
        // Her live shape: a deep bond under the legacy id carrying the real relationship,
        // and a shallow one under the person DID. The encounters were real; only the
        // claim that they were two people was false.
        PersonIds.resetForTesting(new StubResolver());
        var jdbc = SchemaInitializer.initialize(tmp.resolve("bonds.db"));
        var seed = new BondStore(jdbc);
        var deep = Bond.acquaintance(COMPANION, LEGACY);
        for (int i = 0; i < 50; i++) deep = deep.withInteraction();
        deep = new Bond(deep.bondId(), deep.agentADid(), deep.agentBDid(),
            Bond.BondDepth.ITEM, deep.formedAt(), deep.lastInteraction(),
            deep.interactionCount(), deep.mutualConsent(), true, deep.scarred(),
            deep.state(), deep.coldStartUntil(), deep.posture(),
            deep.relationalState(), deep.kind());
        seed.save(deep);
        seed.save(Bond.acquaintance(COMPANION, PERSON));

        // Loading is enough — an operator should not have to know this happened.
        var ritual = new BondRitual();
        ritual.setStore(new BondStore(jdbc));

        assertThat(ritual.splitBondholders(COMPANION))
            .as("the split must be gone after load")
            .isEmpty();

        var active = ritual.bondsForAgent(COMPANION).stream()
            .filter(Bond::active).toList();
        assertThat(active).hasSize(1);
        var kept = active.get(0);
        assertThat(kept.otherParty(COMPANION)).isEqualTo(PERSON);
        assertThat(kept.depth())
            .as("the deepest depth reached is kept — she did get that close to him")
            .isEqualTo(Bond.BondDepth.ITEM);
        assertThat(kept.interactionCount())
            .as("every real encounter is kept; only the doubling was ours")
            .isGreaterThanOrEqualTo(50);
    }

    @Test
    void a_retired_duplicate_is_kept_as_evidence_not_deleted(@TempDir Path tmp) {
        PersonIds.resetForTesting(new StubResolver());
        var jdbc = SchemaInitializer.initialize(tmp.resolve("bonds.db"));
        var seed = new BondStore(jdbc);
        seed.save(Bond.acquaintance(COMPANION, LEGACY));
        seed.save(Bond.acquaintance(COMPANION, PERSON));

        var ritual = new BondRitual();
        ritual.setStore(new BondStore(jdbc));

        var onDisk = new BondStore(jdbc).all();
        assertThat(onDisk)
            .as("nothing is deleted — the retired row is evidence of what happened")
            .hasSize(2);
        assertThat(onDisk.stream().filter(b -> !b.active())).hasSize(1);
        assertThat(onDisk.stream().filter(Bond::active)).hasSize(1);
    }

    @Test
    void a_healthy_household_reports_no_split() {
        PersonIds.resetForTesting(new StubResolver());
        var ritual = new BondRitual();
        ritual.formAcquaintance(COMPANION, PERSON);

        assertThat(ritual.splitBondholders(COMPANION)).isEmpty();
    }

    @Test
    void two_genuinely_different_people_are_left_alone() {
        PersonIds.resetForTesting(new StubResolver());
        var ritual = new BondRitual();
        ritual.formAcquaintance(COMPANION, PERSON);
        ritual.formAcquaintance(COMPANION, "did:key:z6MkSomeoneElse");

        assertThat(ritual.bondsForAgent(COMPANION)).hasSize(2);
        assertThat(ritual.splitBondholders(COMPANION)).isEmpty();
    }

    @Test
    void an_unresolvable_identifier_is_still_itself() {
        // An unmigrated local user must keep working — resolution failure is not identity
        // loss, and must never collapse two strangers into one person.
        PersonIds.resetForTesting(new StubResolver());
        assertThat(PersonIds.canonical("someone-with-no-mapping"))
            .isEqualTo("someone-with-no-mapping");
        assertThat(PersonIds.samePerson("stranger-a", "stranger-b")).isFalse();
    }
}

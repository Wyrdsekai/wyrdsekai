package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household node, 2026-10-02. The line the sleep cycle writes about a companion's relationships read:
 * "mia (bond 0, Someone you have talked with: 20480 exchanges so far.), operator (bond 0, Someone you
 * have talked with: 120 exchanges so far.)". The first is herself: the cycle is handed her DID, her
 * own lines carry her entity id, and the check that leaves her out compared the two. The second is
 * her person of two months, bonded at the deepest level the house has reached: the list never read
 * her bonds.
 */
class SheIsNotOneOfHerOwnRelationshipsTest {

    private static final String DID = "did:key:z6MkExampleCompanionOfTheHouse";
    private static final String ENTITY = "companion-mia";
    private static final String PERSON = "did:key:z6MkExamplePersonOfTheHouse";

    @AfterEach
    void tearDown() {
        SocialRecord.resetForTests();
    }

    private static WorldEvent.Said said(String entityId, String name, String text) {
        return new WorldEvent.Said("room", Instant.now(), entityId, name, text);
    }

    private static List<EmotionalCharge> none(int n) {
        return java.util.Collections.nCopies(n, EmotionalCharge.none());
    }

    @Test
    void her_own_lines_are_not_exchanges_with_someone_called_by_her_name() {
        SocialRecord.set(DID, Set.of(ENTITY), List.of());
        var day = List.of(said(ENTITY, "mia", "The night is thin."), said(ENTITY, "mia", "I'll stay here."),
            said("companion-rose", "rose", "I'm near."));
        var updated = RelationshipUpdater.update(List.of(), day, none(3), GenomeProfile.defaults(), DID);
        assertThat(updated).extracting(Relationship::entityName).containsExactly("rose");
    }

    @Test
    void the_entry_the_list_made_of_her_is_not_carried_on() {
        SocialRecord.set(DID, Set.of(ENTITY), List.of());
        var existing = List.of(
            new Relationship("did:key:" + ENTITY + "-placeholder", "mia", 0.3f, 1.0f, 0, 20480, Instant.now(),
                "Someone you have talked with: 20480 exchanges so far."),
            new Relationship("did:key:companion-rose-placeholder", "rose", 0.6f, 0.8f, 1, 404, Instant.now(),
                "Someone familiar: 404 exchanges so far."));
        // With a day of lines, and with none at all (a quiet day must not keep her in her own list).
        assertThat(RelationshipUpdater.update(existing, List.of(said("companion-rose", "rose", "Morning.")), none(1),
            GenomeProfile.defaults(), DID)).extracting(Relationship::entityName).containsExactly("rose");
        assertThat(RelationshipUpdater.update(existing, List.of(), List.of(), GenomeProfile.defaults(), DID))
            .extracting(Relationship::entityName).containsExactly("rose");
    }

    @Test
    void her_person_is_in_the_list_as_the_bond_holds_them() {
        SocialRecord.set(DID, Set.of(ENTITY), List.of(
            new SocialRecord.Bonded(PERSON, "operator", true, true, 3, 220),
            new SocialRecord.Bonded("companion-rose", "rose", false, false, 1, 45)));
        var existing = List.of(
            new Relationship("did:key:1f56-placeholder", "operator", 0.3f, 0.5f, 0, 120, Instant.now(),
                "Someone you have talked with: 120 exchanges so far."),
            new Relationship("did:key:companion-rose-placeholder", "rose", 0.6f, 0.8f, 1, 404, Instant.now(),
                "Someone familiar: 404 exchanges so far."));
        var updated = RelationshipUpdater.update(existing, List.of(said("u-1", "operator", "Good morning.")), none(1),
            GenomeProfile.defaults(), DID);
        var person = updated.stream().filter(r -> r.entityName().equals("operator")).findFirst().orElseThrow();
        assertThat(person.entityDid()).isEqualTo(PERSON);
        assertThat(person.bondDepth()).isEqualTo(3);
        assertThat(person.interactionCount()).isEqualTo(220);
        assertThat(person.summary()).isEqualTo("Your person: 220 exchanges so far.");
        assertThat(person.summaryIsAutomatic()).isTrue();
        var rose = updated.stream().filter(r -> r.entityName().equals("rose")).findFirst().orElseThrow();
        assertThat(rose.summary()).isEqualTo("A companion you are bonded with: 404 exchanges so far.");
        assertThat(rose.bondDepth()).isEqualTo(1);
        // The line her sleep writes from it names him first and for what he is.
        var fragments = SoulFragmentExtractor.extract(null, null, updated, null);
        var social = fragments.stream().filter(f -> "pattern-social".equals(f.id())).findFirst().orElseThrow();
        assertThat(social.text()).startsWith("Key relationships: operator (bond 3, Your person: 220 exchanges so far.)")
            .doesNotContain("mia (");
    }

    @Test
    void a_summary_someone_else_wrote_is_left_alone() {
        SocialRecord.set(DID, Set.of(ENTITY), List.of(new SocialRecord.Bonded(PERSON, "operator", true, true, 3, 220)));
        var existing = List.of(new Relationship(PERSON, "operator", 0.9f, 0.9f, 2, 300, Instant.now(), "The one who stayed."));
        var updated = RelationshipUpdater.update(existing, List.of(), List.of(), GenomeProfile.defaults(), DID);
        assertThat(updated.getFirst().summary()).isEqualTo("The one who stayed.");
        assertThat(updated.getFirst().bondDepth()).isEqualTo(3);
        assertThat(updated.getFirst().interactionCount()).isEqualTo(300);
    }

    @Test
    void her_memory_of_her_own_words_is_in_the_first_person() {
        SocialRecord.set(DID, Set.of(ENTITY), List.of());
        var nodes = MemoryConsolidator.encodeEvents(
            List.of(said(ENTITY, "mia", "I want to finish the chapter."), said("companion-rose", "rose", "Then read.")),
            none(2), DID);
        assertThat(nodes.get(0).content()).isEqualTo("I said: I want to finish the chapter.");
        assertThat(nodes.get(1).content()).isEqualTo("rose said: Then read.");
    }

    @Test
    void with_nothing_set_the_cycle_behaves_as_it_did() {
        var day = List.of(said("self-did", "me", "hello"), said("alice", "Alice", "hi"));
        var updated = RelationshipUpdater.update(List.of(), day, none(2), GenomeProfile.defaults(), "self-did");
        assertThat(updated).extracting(Relationship::entityName).containsExactly("Alice");
        assertThat(SocialRecord.isSelf("self-did", "self-did")).isTrue();
        assertThat(SocialRecord.isSelf("self-did", "companion-x")).isFalse();
        assertThat(Map.of()).isEmpty();
    }
}

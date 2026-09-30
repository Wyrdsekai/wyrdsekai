package org.wyrdsekai.core.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.identity.PersonIdentityResolver;
import org.wyrdsekai.core.identity.PersonIds;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit W4 (2026-09-28): a memory records who told it and whether privately, and a turn reads
 * only what its person may read.
 */
class MemoryOriginTest {

    static final String ALICE = "did:key:alice";
    static final String BOB = "did:key:bob";

    /** A resolver that knows two people by name and login id, and nobody else. */
    static PersonIdentityResolver household() {
        var known = Map.of("Alice", ALICE, "alice-login", ALICE, "Bob", BOB);
        return new PersonIdentityResolver("jdbc:sqlite::memory:") {
            @Override
            public Optional<String> resolve(String identifier) {
                return Optional.ofNullable(known.get(identifier));
            }
        };
    }

    @BeforeEach
    void knowTheHousehold() {
        PersonIds.resetForTesting(household());
    }

    @AfterEach
    void forget() {
        PersonIds.resetForTesting(null);
    }

    @Test
    @DisplayName("one person's private words are read back only in that person's turns")
    void privateStaysWithItsTeller() {
        var secret = MemoryOrigin.privateTo("alice-login");
        assertThat(secret.tellerDid()).isEqualTo(ALICE);
        assertThat(MemoryReader.of(ALICE, false).mayRead(secret)).isTrue();
        assertThat(MemoryReader.of("alice-login", false).mayRead(secret)).isTrue();
        assertThat(MemoryReader.of(BOB, true).mayRead(secret)).as("not even to her bondholder").isFalse();
        assertThat(MemoryReader.NO_ONE.mayRead(secret)).as("not on her own time").isFalse();
    }

    @Test
    @DisplayName("what was said openly, and her own experience, is read in any turn")
    void openIsForEveryone() {
        assertThat(MemoryReader.of(BOB, false).mayRead(MemoryOrigin.openFrom(ALICE))).isTrue();
        assertThat(MemoryReader.NO_ONE.mayRead(MemoryOrigin.OWN)).isTrue();
    }

    @Test
    @DisplayName("what is known ABOUT a person is what they told her, not what someone else said openly")
    void toldIsTheTellersOwn() {
        assertThat(MemoryReader.of(BOB, false).told(MemoryOrigin.openFrom(ALICE))).isFalse();
        assertThat(MemoryReader.of(ALICE, false).told(MemoryOrigin.openFrom(ALICE))).isTrue();
        assertThat(MemoryReader.NO_ONE.told(MemoryOrigin.OWN)).isFalse();
    }

    @Test
    @DisplayName("a memory whose teller cannot be known is read only in turns with her bondholder")
    void unknownIsTheBondholders() {
        assertThat(MemoryReader.of(ALICE, true).mayRead(MemoryOrigin.UNKNOWN)).isTrue();
        assertThat(MemoryReader.of(BOB, false).mayRead(MemoryOrigin.UNKNOWN)).isFalse();
        assertThat(MemoryReader.NO_ONE.mayRead(MemoryOrigin.UNKNOWN)).isFalse();
        assertThat(MemoryReader.of(BOB, false).mayReadAudience(null)).as("no stored audience = unknown").isFalse();
    }

    @Test
    @DisplayName("the audience a search filters on round-trips")
    void audienceRoundTrips() {
        assertThat(MemoryOrigin.privateTo(ALICE).audience()).isEqualTo("person:" + ALICE);
        assertThat(MemoryOrigin.fromAudience("person:" + ALICE)).isEqualTo(MemoryOrigin.privateTo(ALICE));
        assertThat(MemoryOrigin.fromAudience("open")).isEqualTo(MemoryOrigin.OWN);
        assertThat(MemoryOrigin.fromAudience("bondholder")).isEqualTo(MemoryOrigin.UNKNOWN);
        assertThat(MemoryReader.of(ALICE, true).audiences())
            .containsExactly("open", "person:" + ALICE, "bondholder");
        assertThat(MemoryReader.NO_ONE.audiences()).containsExactly("open");
    }

    @Test
    @DisplayName("earlier memories: a line that names the person it was a private exchange with is theirs")
    void legacyLinesNameTheirTeller() {
        assertThat(MemoryOrigin.inferLegacy("09:14 [User fact] Alice: I am allergic to cashews"))
            .isEqualTo(new MemoryOrigin(ALICE, MemoryOrigin.Visibility.PRIVATE));
        assertThat(MemoryOrigin.inferLegacy("Replied to Bob via tell: the garden is fine"))
            .isEqualTo(new MemoryOrigin(BOB, MemoryOrigin.Visibility.PRIVATE));
        assertThat(MemoryOrigin.inferLegacy("11:02 Alice wrote to me: the results came back"))
            .isEqualTo(new MemoryOrigin(ALICE, MemoryOrigin.Visibility.PRIVATE));
        assertThat(MemoryOrigin.inferLegacy("Wrote to did:key:carol's journal: remember the gate"))
            .isEqualTo(new MemoryOrigin("did:key:carol", MemoryOrigin.Visibility.PRIVATE));
        assertThat(MemoryOrigin.inferLegacy("[PENDING REPLY] Report to Alice via: {}").tellerDid())
            .isEqualTo(ALICE);
    }

    @Test
    @DisplayName("earlier memories: anything else, or a name nobody here is known by, is of unknown origin")
    void legacyUnknown() {
        assertThat(MemoryOrigin.inferLegacy("10:00 Created room: the garden")).isEqualTo(MemoryOrigin.UNKNOWN);
        assertThat(MemoryOrigin.inferLegacy("[User fact] Zed: I live alone")).isEqualTo(MemoryOrigin.UNKNOWN);
        assertThat(MemoryOrigin.inferLegacy(null)).isEqualTo(MemoryOrigin.UNKNOWN);
    }
}

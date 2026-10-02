package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After a restart with nobody logged in, the entity registry has never seen her person, and the
 * facts of her house named her housemate and her room but not him (household node, 2026-10-02).
 * The record itself knows the name a DID goes by.
 */
class HerPersonIsNamedFromTheRecordTest {

    private static final String DID = "did:key:z6MkhExamplePersonOfTheHouseNotARealKey11111111";

    @AfterEach
    void forget() {
        PersonIds.resetForTesting(null);
    }

    @Test
    void the_record_names_her_person_by_did_account_id_or_username() {
        var names = Map.of(DID, "operator", "1f56a2d4", "operator", "operator", "operator");
        PersonIds.resetForTesting(new PersonIdentityResolver("jdbc:sqlite::memory:") {
            @Override
            public Optional<String> displayNameFor(String identifier) {
                return Optional.ofNullable(names.get(identifier));
            }
        });
        assertThat(PersonIds.displayName(DID)).contains("operator");
        assertThat(PersonIds.displayName("1f56a2d4")).contains("operator");
        assertThat(PersonIds.displayName("did:key:z6MkNobody")).isEmpty();
        assertThat(PersonIds.displayName("  ")).isEmpty();
        assertThat(PersonIds.displayName(null)).isEmpty();
    }

    @Test
    void with_no_record_to_ask_there_is_no_name_and_no_error() {
        PersonIds.resetForTesting(new PersonIdentityResolver("jdbc:sqlite::memory:") {
            @Override
            public Optional<String> displayNameFor(String identifier) {
                throw new IllegalStateException("no record");
            }
        });
        assertThat(PersonIds.displayName(DID)).isEmpty();
    }
}

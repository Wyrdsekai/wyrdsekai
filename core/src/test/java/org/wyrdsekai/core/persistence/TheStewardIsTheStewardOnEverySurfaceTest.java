package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.identity.PersonIdentityResolver;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.test.TestDb;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session presents the login id on ssh and the person DID in a browser or on a phone. The
 * steward-only verbs (birth, restore, demolish, rename) look the account up from whichever id
 * the room was given, so they must find the same account from either.
 */
@Tag("integration")
class TheStewardIsTheStewardOnEverySurfaceTest {

    @AfterEach
    void tearDown() {
        PersonIds.resetForTesting(null);
    }

    @Test
    @DisplayName("the account is found from the login id and from the person DID")
    void bothIds() {
        var jdbc = TestDb.createInMemory();
        var auth = new AuthService(jdbc);
        var userId = auth.register("sam", "password123", "Sam").orElseThrow().userId();
        var did = "did:key:z6MkSamThePerson";
        PersonIds.resetForTesting(new PersonIdentityResolver(jdbc) {
            @Override public Optional<String> resolve(String identifier) {
                return userId.equals(identifier) ? Optional.of(did) : Optional.empty();
            }
        });

        assertThat(auth.findUserForPerson(userId)).map(AuthService.User::username).contains("sam");
        assertThat(auth.findUser(did)).isEmpty();
        assertThat(auth.findUserForPerson(did)).map(AuthService.User::username).contains("sam");
        assertThat(auth.findUserForPerson("did:key:z6MkSomeoneElse")).isEmpty();
        assertThat(auth.findUserForPerson(null)).isEmpty();
    }
}

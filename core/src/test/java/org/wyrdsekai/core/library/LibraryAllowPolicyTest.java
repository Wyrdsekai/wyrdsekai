package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.library.LibraryAllowPolicy.Caller;
import org.wyrdsekai.core.library.LibraryAllowPolicy.Door;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@code allow} values anyone may cause to be sent to the household's library is the
 * household's rule, not the library's: a child nothing, an adult {@code self-harm} through their
 * own {@code research yes} only, and every agent nothing, ever.
 */
class LibraryAllowPolicyTest {

    @AfterEach
    void tearDown() {
        LibraryAllowPolicy.resetForTests();
    }

    @Test
    void the_table() {
        assertThat(LibraryAllowPolicy.mayCause(Caller.CHILD, Door.OWN_YES)).isEmpty();
        assertThat(LibraryAllowPolicy.mayCause(Caller.CHILD, Door.OTHER)).isEmpty();
        assertThat(LibraryAllowPolicy.mayCause(Caller.ADULT, Door.OWN_YES)).isEqualTo(Set.of("self-harm"));
        assertThat(LibraryAllowPolicy.mayCause(Caller.ADULT, Door.OTHER)).isEmpty();
        assertThat(LibraryAllowPolicy.mayCause(Caller.AGENT, Door.OWN_YES)).isEmpty();
        assertThat(LibraryAllowPolicy.mayCause(Caller.AGENT, Door.OTHER)).isEmpty();
    }

    @Test
    void explicit_and_howto_never_go_out_whoever_asks_and_however() {
        for (var who : Caller.values()) {
            for (var door : Door.values()) {
                assertThat(LibraryAllowPolicy.toSend(who, door, List.of("explicit"))).as(who + "/" + door).isEmpty();
                assertThat(LibraryAllowPolicy.toSend(who, door, List.of("howto"))).as(who + "/" + door).isEmpty();
                assertThat(LibraryAllowPolicy.toSend(who, door, "explicit")).as(who + "/" + door).isEmpty();
            }
        }
    }

    @Test
    void self_harm_goes_out_only_on_an_adults_own_yes_and_a_mixed_request_sends_nothing() {
        assertThat(LibraryAllowPolicy.toSend(Caller.ADULT, Door.OWN_YES, List.of("self-harm"))).containsExactly("self-harm");
        assertThat(LibraryAllowPolicy.toSend(Caller.ADULT, Door.OTHER, List.of("self-harm"))).isEmpty();
        assertThat(LibraryAllowPolicy.toSend(Caller.CHILD, Door.OWN_YES, List.of("self-harm"))).isEmpty();
        assertThat(LibraryAllowPolicy.toSend(Caller.AGENT, Door.OTHER, List.of("self-harm"))).isEmpty();
        // A yes is to the one thing; a request carrying anything else is not trimmed, it is refused.
        assertThat(LibraryAllowPolicy.toSend(Caller.ADULT, Door.OWN_YES, List.of("self-harm", "explicit"))).isEmpty();
        assertThat(LibraryAllowPolicy.toSend(Caller.ADULT, Door.OWN_YES, List.of())).isEmpty();
        assertThat(LibraryAllowPolicy.toSend(Caller.ADULT, Door.OWN_YES, null)).isEmpty();
    }

    @Test
    void who_a_person_is_comes_from_parental_controls_and_unknown_is_not_adult() {
        LibraryAllowPolicy.setParentalControlsForTests(id -> id.equals("did:person:kit"));
        assertThat(LibraryAllowPolicy.personOf("did:person:kit")).isEqualTo(Caller.CHILD);
        assertThat(LibraryAllowPolicy.personOf("did:person:ada")).isEqualTo(Caller.ADULT);
        assertThat(LibraryAllowPolicy.personOf(null)).isEqualTo(Caller.AGENT);
        assertThat(LibraryAllowPolicy.personOf(" ")).isEqualTo(Caller.AGENT);

        LibraryAllowPolicy.setParentalControlsForTests(id -> { throw new IllegalStateException("db down"); });
        assertThat(LibraryAllowPolicy.personOf("did:person:ada")).isEqualTo(Caller.CHILD);
    }
}

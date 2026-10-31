package org.wyrdsekai.server.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules of a held place, with the clock driven by hand. */
class HeldPlacesTest {

    private final List<Runnable> due = new ArrayList<>();
    private final HeldPlaces places = new HeldPlaces(Duration.ofSeconds(30), (delay, task) -> due.add(task));
    private final List<String> left = new ArrayList<>();

    private void timePasses() {
        var now = new ArrayList<>(due);
        due.clear();
        now.forEach(Runnable::run);
    }

    @Test
    void only_a_drop_no_one_chose_is_held() {
        assertThat(HeldPlaces.mayHold(1006)).as("the network dropped it").isTrue();
        assertThat(HeldPlaces.mayHold(1001)).as("a reload or a closed tab").isTrue();
        assertThat(HeldPlaces.mayHold(1000)).as("the client said goodbye").isFalse();
        for (int code : new int[] {4000, 4001, 4005, 4030, 4031}) {
            assertThat(HeldPlaces.mayHold(code)).as("the server's own close " + code).isFalse();
        }
    }

    @Test
    void back_in_time_the_session_is_taken_up_and_no_one_leaves() {
        places.hold("s1", "ada", "Firefox", () -> left.add("s1"));

        assertThat(places.takeUp("s1")).isPresent();
        timePasses();

        assertThat(left).isEmpty();
        assertThat(places.isHeld("s1")).isFalse();
    }

    @Test
    void not_back_in_time_they_leave_once() {
        places.hold("s1", "ada", "Firefox", () -> left.add("s1"));

        timePasses();

        assertThat(left).containsExactly("s1");
        assertThat(places.takeUp("s1")).as("too late to take it up").isEmpty();
    }

    @Test
    void a_client_naming_no_session_takes_up_its_persons_newest_place_on_that_kind_of_client() throws Exception {
        places.hold("old", "ada", "Firefox", () -> left.add("old"));
        Thread.sleep(5);
        places.hold("new", "ada", "Firefox", () -> left.add("new"));
        places.hold("phone", "ada", "WyrdApp", () -> left.add("phone"));
        places.hold("bea", "bea", "Firefox", () -> left.add("bea"));

        assertThat(places.takeUpFor("ada", "Firefox")).map(HeldPlaces.Place::sessionId).contains("new");
        assertThat(places.takeUpFor("ada", "Chrome")).as("another kind of client").isEmpty();
        assertThat(places.takeUpFor("cyd", "Firefox")).as("another person").isEmpty();
    }

    @Test
    void a_logout_ends_a_hold_at_once() {
        places.hold("s1", "ada", "Firefox", () -> left.add("s1"));

        assertThat(places.endNow("s1")).isTrue();
        timePasses();

        assertThat(left).as("the caller leaves; the timer finds nothing").isEmpty();
        assertThat(places.endNow("s1")).isFalse();
    }

    @Test
    void a_session_held_again_after_being_taken_up_has_a_hold_of_its_own() {
        places.hold("s1", "ada", "Firefox", () -> left.add("first"));
        places.takeUp("s1");
        places.hold("s1", "ada", "Firefox", () -> left.add("second"));

        timePasses();

        assertThat(left).as("the first hold's timer does not end the second").containsExactly("second");
    }
}

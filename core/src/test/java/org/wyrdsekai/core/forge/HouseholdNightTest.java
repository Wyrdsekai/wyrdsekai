package org.wyrdsekai.core.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the night's write stops the only model, it waits for the whole household to be asleep.
 *
 * <p>With one companion the hold on her own sleep was enough. With two, the first one's write
 * stopped the model while the second was awake, so her turns failed for the length of it.
 */
class HouseholdNightTest {

    private static final Duration LONG = Duration.ofSeconds(30);

    @Test
    @DisplayName("one companion: her write runs at once and she wakes when it is over")
    void aHouseholdOfOne() throws Exception {
        var written = Collections.synchronizedList(new ArrayList<String>());
        var over = HouseholdNight.arrive("c-one", "one", Set.of("c-one"), id -> { throw new AssertionError("nobody to ask"); },
            (n, id, r) -> written.add(id), () -> false, LONG);
        over.get(5, TimeUnit.SECONDS);
        assertThat(written).containsExactly("c-one");
    }

    @Test
    @DisplayName("two companions: the other is asked to sleep, no write starts until both are asleep, then one after another")
    void theWritesWaitForEveryone() throws Exception {
        var written = Collections.synchronizedList(new ArrayList<String>());
        var asked = Collections.synchronizedList(new ArrayList<String>());
        var both = Set.of("c-one", "c-two");
        var first = HouseholdNight.arrive("c-one", "one", both, asked::add, (n, id, r) -> written.add(id + ":" + r), () -> true, LONG);
        assertThat(asked).containsExactly("c-two");
        assertThat(first.isDone()).isFalse();
        assertThat(written).as("the model is not stopped while someone is awake").isEmpty();

        var second = HouseholdNight.arrive("c-two", "two", both, id -> { throw new AssertionError("asked twice"); },
            (n, id, r) -> written.add(id + ":" + r), () -> true, LONG);
        second.get(5, TimeUnit.SECONDS);
        first.get(5, TimeUnit.SECONDS);
        assertThat(written).as("arrival order, and one answer about rehearsal for the whole night")
            .containsExactly("c-one:true", "c-two:true");
    }

    @Test
    @DisplayName("a companion who stays up is not overridden: no write runs and the sleeper wakes")
    void aNightThatDoesNotGather() throws Exception {
        var written = Collections.synchronizedList(new ArrayList<String>());
        var over = HouseholdNight.arrive("c-one", "one", Set.of("c-one", "c-two"), id -> { },
            (n, id, r) -> written.add(id), () -> false, Duration.ofMillis(200));
        over.get(5, TimeUnit.SECONDS);
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("falling asleep after the writes began: she sleeps until they are over and is not written tonight")
    void aLateSleeper() throws Exception {
        var written = Collections.synchronizedList(new ArrayList<String>());
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var first = HouseholdNight.arrive("c-one", "one", Set.of("c-one"), id -> { }, (n, id, r) -> {
            written.add(id);
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, () -> false, LONG);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        var late = HouseholdNight.arrive("c-two", "two", Set.of("c-one", "c-two"), id -> { }, (n, id, r) -> written.add(id), () -> false, LONG);
        assertThat(late.isDone()).isFalse();
        release.countDown();
        late.get(5, TimeUnit.SECONDS);
        first.get(5, TimeUnit.SECONDS);
        assertThat(written).containsExactly("c-one");
    }
}

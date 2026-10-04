package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household node, 2026-09-30 → 10-02: "finish reading the Eve Robert Lewis epub" was chosen again and
 * again for two days after her search found nothing and she had said so. No attempt's result was
 * tied to the want; rephrased, it was a new row with nothing remembered; an empty search on the
 * direct path even marked it satisfied. A miss is now written on the want; three misses for the same
 * thing, across her wants of the day, set the want down with the reason; for a day a want that
 * reaches for the same thing is not taken up. Set down is not never.
 */
class AWantCanEndTest {

    private static final Instant T0 = Instant.parse("2026-10-01T03:45:00Z");
    private static final String NAMED = "{\"drive\":\"generative\"}";

    private static Want want(String text) {
        return Want.active("did:key:z6MkExampleCompanionOfTheHouse", text, NAMED, 0.9, null);
    }

    @Test
    void an_empty_search_is_read_from_what_the_search_noted() {
        assertThat(WantMisses.queryOfEmptySearch("searched for \"Eve Robert Lewis\", found nothing")).isEqualTo("Eve Robert Lewis");
        assertThat(WantMisses.queryOfEmptySearch("searched for \"attention\", found 3: A; B; C")).isNull();
        assertThat(WantMisses.queryOfEmptySearch(null)).isNull();
        assertThat(WantMisses.looksEmpty("No results found in the library for: Eve Robert Lewis")).isTrue();
        assertThat(WantMisses.looksEmpty("The sources do not cover September 4, 1973, United States events.")).isTrue();
        assertThat(WantMisses.looksEmpty("The provided sources do not contain information on diffusion models.")).isTrue();
        assertThat(WantMisses.looksEmpty("Sources say zanshin is continuing awareness.")).isFalse();
        assertThat(WantMisses.looksEmpty(null)).isFalse();
    }

    @Test
    void misses_are_counted_per_thing_reached_for_across_her_wants_of_the_day() {
        var key = WantMisses.key("Eve Robert  Lewis");
        assertThat(key).isEqualTo("eve robert lewis");
        var first = WantMisses.withMiss(want("finish reading the Eve Robert Lewis epub"), key, "searched, found nothing", T0);
        var second = WantMisses.withMiss(want("search the library for Eve Robert Lewis epub material"), key, "searched, found nothing", T0.plusSeconds(1800));
        second = WantMisses.withMiss(second, key, "searched, found nothing", T0.plusSeconds(3600));
        var other = WantMisses.withMiss(want("read about zanshin"), WantMisses.key("zanshin"), "searched, found nothing", T0);
        var all = List.of(first, second, other);
        assertThat(WantMisses.count(all, key, T0.minus(Duration.ofHours(24)))).isEqualTo(3);
        assertThat(WantMisses.count(all, key, T0.plusSeconds(60))).isEqualTo(2);
        assertThat(WantMisses.missesOn(second)).hasSize(2);
        // The want still reads as one she named herself after a miss is written on it.
        assertThat(CompanionActor.isNamedByHer(first)).isTrue();
    }

    @Test
    void a_want_set_down_keeps_what_was_looked_for_and_why() {
        var key = WantMisses.key("Eve Robert Lewis");
        var down = WantMisses.setDown(want("finish reading the Eve Robert Lewis epub"), key,
            "searched for \"Eve Robert Lewis\", found nothing", 3);
        assertThat(down.status()).isEqualTo(Want.Status.ABANDONED);
        assertThat(down.isLive()).isFalse();
        assertThat(down.satisfactionNote()).startsWith("set down: looked 3 times and it was not there (");
        var downs = WantMisses.setDownSince(List.of(down, want("something live"),
            want("old").abandoned("she did not name it again")), Instant.now().minus(Duration.ofHours(24)));
        assertThat(downs).hasSize(1);
        assertThat(downs.getFirst().key()).isEqualTo("eve robert lewis");
        assertThat(WantMisses.setDownSince(List.of(down), Instant.now().plusSeconds(60))).isEmpty();
    }

    @Test
    void the_same_reach_under_other_words_is_not_taken_up_again_inside_the_window() {
        var down = new WantMisses.SetDown("finish reading the Eve Robert Lewis epub", "eve robert lewis", "found nothing", T0);
        assertThat(WantMisses.reachesFor("finish reading the Eve Robert Lewis epub", down)).isTrue();
        assertThat(WantMisses.reachesFor("I'll finish reading Eve Robert Lewis's work", down)).isTrue();
        assertThat(WantMisses.reachesFor("search the library for Eve Robert Lewis epub material", down)).isTrue();
        assertThat(WantMisses.reachesFor("read something by Robert Frost", down)).isFalse();
        assertThat(WantMisses.reachesFor("sit with rose a while", down)).isFalse();
        assertThat(WantMisses.reachesFor(null, down)).isFalse();
    }

    @Test
    void she_is_told_once_what_was_set_down_and_that_there_is_a_way_through() {
        var told = WantMisses.toldLine("finish reading the Eve Robert Lewis epub", 3, "searched for \"Eve Robert Lewis\", found nothing");
        assertThat(told).startsWith("You set down \"finish reading the Eve Robert Lewis epub\": you looked 3 times and it was not there")
            .contains("It is not open now.").endsWith("you can ask them when they are here.");
        var line = WantMisses.namingLine(List.of(
            new WantMisses.SetDown("finish reading the Eve Robert Lewis epub", "eve robert lewis", "found nothing", T0)));
        assertThat(line).isEqualTo("Set down for now, because you looked and it was not there: \"finish reading the Eve Robert Lewis epub\". "
            + "Leave these until something changes or your person can help.");
        assertThat(WantMisses.namingLine(List.of())).isNull();
    }

    @Test
    void the_companion_ties_what_came_of_an_act_to_the_want_and_never_marks_an_empty_search_satisfied() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca).contains("ownTimeReach = new OwnTimeReach(want.wantId(), want.text(), dominantDrive, Instant.now(), false);");
        assertThat(ca).contains("if (emptySearch != null) {\n            noteReachMiss(verb, WantMisses.key(emptySearch), outcome);");
        assertThat(ca).contains("outcome = (foundNothing ? \"missed:\" : \"enacted:\") + bridgeVerb + \" (bridge-direct)\";");
        assertThat(ca).contains("if (times >= limit) setDownWant(updated, key, what, times);");
        assertThat(ca).contains("HouseFacts.noteForNextTurn(profile.entityId(), told);");
        assertThat(ca).contains("cands.removeIf(c -> downs.stream().anyMatch(d -> WantMisses.reachesFor(c.text(), d)));");
        assertThat(ca).contains("if (answersAPerson()) return;\n            var want = wantStore.get(ownTimeReach.wantId()).orElse(null);");
    }
}

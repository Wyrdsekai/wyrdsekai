package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The library's research runs have no ceiling of their own: on the household node one ran four hours
 * and more, the desk ran three at once, and two companions' idle curiosity filled it (2026-11-05). The
 * household sets how long a run may take and how many model turns it may spend, by who asked, and a
 * companion's own time starts one run a day.
 */
class AResearchRunIsHeldToTheHouseholdsLimitsTest {

    private final List<Map<String, Object>> sent = new ArrayList<>();
    private final LibraryConsent.Asker mia = LibraryConsent.Asker.ownTimeOf("companion-mia");
    private final LibraryConsent.Asker ada = new LibraryConsent.Asker("did:person:ada", "Ada", "en");

    @BeforeEach
    void setUp() {
        LibraryConsent.resetForTests();
        LibraryConsent.ownTimePerDay = () -> 1;
        LibraryConsent.ownTimeMinutes = () -> 20;
        LibraryConsent.memberMinutes = () -> 60;
        LibraryConsent.ownTimeTurns = () -> 40;
        LibraryConsent.memberTurns = () -> 120;
    }

    @AfterEach
    void tearDown() { LibraryConsent.resetForTests(); }

    private String library(Map<String, Object> args) {
        sent.add(new HashMap<>(args));
        return "{\"job_id\":\"J-" + (60 + sent.size()) + "\",\"state\":\"queued\"}";
    }

    @Test
    void a_run_carries_the_ceiling_for_who_asked_and_a_smaller_one_asked_is_kept() throws Exception {
        LibraryConsent.call(mia, LibraryConsent.RESEARCH, Map.of("question", "the history of zen"), this::library);
        assertThat(sent.get(0)).containsEntry("max_minutes", 20).containsEntry("max_turns", 40);

        LibraryConsent.call(ada, LibraryConsent.RESEARCH, Map.of("question", "a long survey of chan", "max_minutes", 300), this::library);
        assertThat(sent.get(1)).containsEntry("max_minutes", 60).containsEntry("max_turns", 120);

        LibraryConsent.call(ada, LibraryConsent.RESEARCH, Map.of("question", "a quick look", "max_minutes", 6, "max_turns", 12), this::library);
        assertThat(sent.get(2)).containsEntry("max_minutes", 6).containsEntry("max_turns", 12);
    }

    @Test
    void her_own_time_starts_one_run_a_day_and_a_stopped_one_asked_again_is_not_counted_twice() throws Exception {
        assertThat(LibraryConsent.call(mia, LibraryConsent.RESEARCH, Map.of("question", "first"), this::library).answered()).isTrue();
        var second = LibraryConsent.call(mia, LibraryConsent.RESEARCH, Map.of("question", "second"), this::library);
        assertThat(second.answered()).isFalse();
        assertThat(sent).hasSize(1);

        var again = LibraryConsent.callAgain(mia, Map.of("question", "first"), this::library);
        assertThat(again.answered()).as("asked again after a stop: neither limited nor counted").isTrue();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).containsEntry("max_minutes", 20).containsEntry("max_turns", 40);
        assertThat(LibraryConsent.call(mia, LibraryConsent.RESEARCH, Map.of("question", "third"), this::library).answered()).isFalse();
    }

    @Test
    void zero_sets_no_ceiling() {
        var args = new HashMap<String, Object>(Map.of("question", "q"));
        LibraryConsent.capAt(args, "max_minutes", 0);
        assertThat(args).doesNotContainKey("max_minutes");
    }
}

package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link StuckReport} — structured failure extraction from a familiar's
 * turn log.
 */
class StuckReportTest {

    private Familiar withLog(List<String> turnContents, String summary) {
        var turns = new ArrayList<Familiar.TurnEntry>();
        for (int i = 0; i < turnContents.size(); i++) {
            turns.add(new Familiar.TurnEntry(
                i, turnContents.get(i), List.of(), Instant.now(), 10));
        }
        return new Familiar(
            UUID.randomUUID().toString(),
            "form-id", "1.0.0",
            "did:key:zParent",
            "find obscure sources",
            Tanks.defaults(),
            List.of(),
            turns.size(),
            Familiar.Status.STUCK,
            turns,
            Optional.empty(),
            Optional.ofNullable(summary),
            Optional.empty(),
            Instant.now(),
            Optional.of(Instant.now()));
    }

    @Test
    void extracts_attempted_approaches_from_turn_log() {
        var fam = withLog(List.of(
            "I'll try a web search for those sources.",
            "Let me check the library packs instead.",
            "Trying the oracle one more time."
        ), "no luck");
        var r = StuckReport.fromFamiliar(fam, "no luck");
        assertThat(r.attempted()).hasSize(3);
        assertThat(r.attempted().get(0)).contains("web search");
    }

    @Test
    void extracts_obstacles_from_error_markers() {
        var fam = withLog(List.of(
            "I'll search the web.",
            "The search failed: timeout.",
            "The library was unavailable.",
            "Let me try oracle.",
            "Error from oracle: quota exceeded."
        ), "couldn't complete");
        var r = StuckReport.fromFamiliar(fam, "couldn't complete");
        assertThat(r.obstacles()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(r.obstacles().get(0).toLowerCase()).contains("failed");
    }

    @Test
    void captures_suggestion_when_summary_proposes_next_step() {
        var fam = withLog(List.of("I'll try once."), "Maybe try with more tokens next time.");
        var r = StuckReport.fromFamiliar(fam, "Maybe try with more tokens next time.");
        assertThat(r.suggestion()).isPresent();
        assertThat(r.suggestion().get()).contains("more tokens");
    }

    @Test
    void empty_turn_log_gives_empty_report() {
        var fam = withLog(List.of(), "nothing to say");
        var r = StuckReport.fromFamiliar(fam, "nothing to say");
        assertThat(r.attempted()).isEmpty();
        assertThat(r.obstacles()).isEmpty();
    }

    @Test
    void dedups_repeated_approaches() {
        var fam = withLog(List.of(
            "I'll try searching.",
            "I'll try searching.",
            "I'll try searching."
        ), "x");
        var r = StuckReport.fromFamiliar(fam, "x");
        assertThat(r.attempted()).hasSize(1);
    }
}

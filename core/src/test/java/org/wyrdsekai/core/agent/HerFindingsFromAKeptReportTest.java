package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.library.FindingsLedger;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What she concluded from a report researched after a person's yes is left out of what she is
 * given while she answers a child (her library search, her conversation lane); her own findings
 * from her own shelves are not.
 */
class HerFindingsFromAKeptReportTest {

    static final Predicate<String> KEPT = id -> id.contains("I-0009") || id.contains("F-0100");

    private static FindingsLedger.Finding finding(String originLibrary, String originId, List<FindingsLedger.Source> sources) {
        return new FindingsLedger.Finding("finding:1", "did:mia", "a claim", FindingsLedger.ClaimType.SYNTHESIS,
            "medium", FindingsLedger.State.DRAFT, "companion:mia", "a question", Instant.EPOCH, sources,
            null, null, 1, "h", originLibrary, originId);
    }

    @Test
    void a_finding_that_came_from_a_kept_report_or_cites_one_is_left_out() {
        assertThat(CompanionActor.fromAKeptReport(finding("lib-1", "I-0009-methods", List.of()), KEPT)).isTrue();
        assertThat(CompanionActor.fromAKeptReport(finding("lib-1", "F-0300-x",
            List.of(FindingsLedger.Source.external("lib-1:F-0100-lethality", null, "Lethality"))), KEPT)).isTrue();
        assertThat(CompanionActor.fromAKeptReport(finding("lib-1", "F-0200-volcanoes",
            List.of(FindingsLedger.Source.external("lib-1:F-0200-volcanoes", null, "Obsidian"))), KEPT)).isFalse();

        // Her own shelves are not another library's reports, even while every library id is kept.
        assertThat(CompanionActor.fromAKeptReport(finding(null, null,
            List.of(new FindingsLedger.Source("S1: a book", "doc:1"))), id -> true)).isFalse();
    }
}

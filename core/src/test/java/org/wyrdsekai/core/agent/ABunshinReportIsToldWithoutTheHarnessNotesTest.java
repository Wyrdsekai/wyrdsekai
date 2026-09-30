package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness's bracketed notes on a bunshin's report are for the record, not for the room. Live
 * 2026-09-22: "[unverified claim — this bunshin executed no tool calls; treat as NOT done] I
 * cannot build rooms…" was spoken aloud, brackets and all.
 */
class ABunshinReportIsToldWithoutTheHarnessNotesTest {

    @Test
    void theNoteIsRemovedAndTheRestIsKept() {
        var told = CompanionActor.spokenBunshinSummary(
            "[unverified claim — this bunshin executed no tool calls; treat as NOT done] I cannot build rooms in this harness.");
        assertThat(told).isEqualTo("I cannot build rooms in this harness.");
    }

    @Test
    void aReportThatIsOnlyANoteIsEmpty() {
        assertThat(CompanionActor.spokenBunshinSummary("[unverified claim — nothing ran]")).isEmpty();
        assertThat(CompanionActor.spokenBunshinSummary(null)).isEmpty();
    }

    @Test
    void anOrdinaryReportIsUnchanged() {
        assertThat(CompanionActor.spokenBunshinSummary("Built the study and placed a desk.")).isEqualTo("Built the study and placed a desk.");
    }
}

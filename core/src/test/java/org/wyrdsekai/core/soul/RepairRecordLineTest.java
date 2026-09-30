package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A question about the repair record is answered from the record. On 2026-09-28 the 35B pair called
 * introspect_repair_history, the presence filter withheld it, and the model described amends that
 * never happened.
 */
class RepairRecordLineTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");

    @Test
    void questionsAboutTheRecordAreRecognised() {
        assertThat(RepairRecordLine.asksAboutTheRecord(
            "What's in our repair history so far? What patterns have we worked on?")).isTrue();
        assertThat(RepairRecordLine.asksAboutTheRecord("How have we repaired before?")).isTrue();
        assertThat(RepairRecordLine.asksAboutTheRecord("what repairs are on your record?")).isTrue();
        assertThat(RepairRecordLine.asksAboutTheRecord("¿Qué hay en nuestro historial de reparación?")).isTrue();
        assertThat(RepairRecordLine.asksAboutTheRecord("これまでの修復の履歴を教えて")).isTrue();
    }

    @Test
    void everydayRepairsAndConfessionsAreNot() {
        assertThat(RepairRecordLine.asksAboutTheRecord("can you help me repair my bike before Friday?")).isFalse();
        assertThat(RepairRecordLine.asksAboutTheRecord("the lamp needs repairing")).isFalse();
        assertThat(RepairRecordLine.asksAboutTheRecord("I said something cruel to my partner last night")).isFalse();
        assertThat(RepairRecordLine.asksAboutTheRecord("what's your history with the library?")).isFalse();
        assertThat(RepairRecordLine.asksAboutTheRecord(null)).isFalse();
    }

    @Test
    void anEmptyRecordSaysItIsEmpty() {
        var line = RepairRecordLine.line(List.of(), ZONE);
        assertThat(line).contains("nothing is recorded yet").contains("say it is empty")
            .contains("Do not describe repair work that is not in it");
    }

    @Test
    void aRecordIsCountedByKindAndNamesWhatNeverHappened() {
        var entries = new ArrayList<RepairLedger.Entry>();
        Instant t = Instant.parse("2026-08-10T14:00:00Z");
        for (int i = 0; i < 46; i++) entries.add(new RepairLedger.Entry(t.plusSeconds(3600L * i), RepairLedger.Kind.BEAR_THE_WOUND, "", "x"));
        for (int i = 0; i < 32; i++) entries.add(new RepairLedger.Entry(t.plusSeconds(3600L * (100 + i)), RepairLedger.Kind.OBJECTION, "did:key:b", "x"));
        for (int i = 0; i < 6; i++) entries.add(new RepairLedger.Entry(t.plusSeconds(3600L * (200 + i)), RepairLedger.Kind.RELEASE, "", "x"));
        entries.add(new RepairLedger.Entry(Instant.parse("2026-09-28T12:00:00Z"), RepairLedger.Kind.SET_ASIDE, "", "x"));

        var line = RepairRecordLine.line(entries, ZONE);
        assertThat(line).contains("85 acts since 10 Aug")
            .contains("46 times carrying something hard")
            .contains("32 times declining a request (an objection, not a harm)")
            .contains("6 times letting a held hurt go")
            .contains("1 time setting something aside")
            .contains("Most recent: setting something aside on 28 Sep")
            .contains("Nothing on the record is naming a harm or making amends")
            .doesNotContain("\"x\"");
    }

    @Test
    void kindsThatHappenedAreNotListedAsMissing() {
        var entries = List.of(
            new RepairLedger.Entry(Instant.parse("2026-09-01T10:00:00Z"), RepairLedger.Kind.ACKNOWLEDGE_HARM, "did:key:b", "x"),
            new RepairLedger.Entry(Instant.parse("2026-09-02T10:00:00Z"), RepairLedger.Kind.MAKE_AMENDS, "did:key:b", "x"));
        var line = RepairRecordLine.line(entries, ZONE);
        assertThat(line).contains("1 time naming a harm and your part in it").contains("1 time making amends")
            .doesNotContain("Nothing on the record is");
    }
}

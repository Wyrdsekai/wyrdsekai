package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The language healer's memory of what it tried (2026-10-05): a strike is a re-render that answered
 * and was no good; three leave a note in peace; the count survives a restart; a note handed back as
 * it went is no re-render.
 */
class LanguageHealLedgerTest {

    @Test
    void three_answered_failures_leave_a_note_in_peace_and_the_count_survives_a_restart(@TempDir Path dir) throws Exception {
        var file = LanguageHealLedger.pathFor(dir.toString(), "did:key:z6MkTest");
        assertThat(file).isNotNull();
        assertThat(file.getFileName().toString()).isEqualTo("language-heal-did_key_z6MkTest.json");
        var ledger = LanguageHealLedger.load(file);
        assertThat(ledger.mercied()).isEmpty();
        assertThat(ledger.strike("n1")).isEqualTo(1);
        assertThat(ledger.strike("n1")).isEqualTo(2);
        assertThat(ledger.mercied("n1")).isFalse();
        assertThat(ledger.strike("n1")).isEqualTo(3);
        assertThat(ledger.mercied("n1")).isTrue();
        ledger.strike("n2");
        ledger.save();
        assertThat(Files.isRegularFile(file)).isTrue();

        var again = LanguageHealLedger.load(file);
        assertThat(again.strikes("n1")).isEqualTo(3);
        assertThat(again.strikes("n2")).isEqualTo(1);
        assertThat(again.mercied()).containsExactly("n1");
        assertThat(again.all()).containsEntry("n2", 1);
    }

    @Test
    void a_missing_or_broken_file_is_an_empty_ledger_and_one_kept_nowhere_saves_nothing(@TempDir Path dir) throws Exception {
        var missing = dir.resolve("none.json");
        assertThat(LanguageHealLedger.load(missing).all()).isEmpty();
        var broken = dir.resolve("broken.json");
        Files.writeString(broken, "{not json");
        assertThat(LanguageHealLedger.load(broken).all()).isEmpty();
        var mem = LanguageHealLedger.inMemory();
        mem.strike("x");
        mem.save();
        assertThat(mem.file()).isNull();
        assertThat(LanguageHealLedger.pathFor(null, "did")).isNull();
        assertThat(LanguageHealLedger.pathFor(dir.toString(), "")).isNull();
    }

    @Test
    void a_note_handed_back_as_it_went_is_an_echo() {
        var es = "El peso que llevaba ya no es el de antes; ahora siento algo distinto en mí.";
        assertThat(CompanionActor.isEcho(es, es)).isTrue();
        assertThat(CompanionActor.isEcho(es, "  el peso que llevaba ya no es el de antes, ahora siento algo distinto en mí ")).isTrue();
        assertThat(CompanionActor.isEcho(es, "The weight I carried is not what it was; something different settles in me now.")).isFalse();
        assertThat(CompanionActor.isEcho(es, null)).isFalse();
    }

    @Test
    void the_report_names_the_household_language_and_shortens_long_notes() {
        var report = LanguageHealth.of(null, "en", LanguageHealLedger.inMemory(), "mia");
        assertThat(report.household()).isEqualTo("en");
        assertThat(report.verifiable()).isTrue();
        assertThat(report.items()).isEmpty();
        assertThat(LanguageHealth.of(null, "fr", LanguageHealLedger.inMemory(), "mia").verifiable()).isFalse();
        assertThat(LanguageHealth.snippet("a\n\n  b   c")).isEqualTo("a b c");
        assertThat(LanguageHealth.snippet("x".repeat(300))).hasSize(140).endsWith("…");
        assertThat(LanguageHealth.householdLanguage()).isNotBlank();
    }
}

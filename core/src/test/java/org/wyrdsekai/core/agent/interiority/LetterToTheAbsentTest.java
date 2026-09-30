package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The reach toward someone who is away is a letter the runtime writes and delivers. */
class LetterToTheAbsentTest {

    private static final RelationalAffordance.Presence AWAY =
        new RelationalAffordance.Presence(false, false, true);
    private static final RelationalAffordance.Presence HERE =
        new RelationalAffordance.Presence(false, true, true);
    private static final RelationalAffordance.Presence NOBODY = RelationalAffordance.Presence.ALONE;

    @Test
    @DisplayName("her words for writing to someone absent name the letter; her own page does not")
    void herWords() {
        assertThat(LetterToTheAbsent.asksToWrite("write to the absent one")).isTrue();
        assertThat(LetterToTheAbsent.asksToWrite(
            "write something to an absent person — a letter, an entry")).isTrue();
        assertThat(LetterToTheAbsent.asksToWrite("leave a note for him")).isTrue();
        assertThat(LetterToTheAbsent.asksToWrite("escribirle una carta a quien no está")).isTrue();
        assertThat(LetterToTheAbsent.asksToWrite("write a private journal entry about who I miss"))
            .as("her journal is hers, not a message").isFalse();
        assertThat(LetterToTheAbsent.asksToWrite("write down what I read today")).isFalse();
        assertThat(LetterToTheAbsent.asksToWrite("rest in the quiet")).isFalse();
        assertThat(LetterToTheAbsent.asksToCheckIn("check in on someone I care about")).isTrue();
        assertThat(LetterToTheAbsent.asksToCheckIn("tend to a quiet marker")).isFalse();
    }

    @Test
    @DisplayName("every relational drive toward an absent bondholder resolves to the letter")
    void theAwayReachIsALetter() {
        for (var drive : List.of("saudade", "loneliness", "affiliation", "care", "amae")) {
            assertThat(RelationalAffordance.verbFor(drive, AWAY))
                .as(drive + " with the bondholder away").isEqualTo(LetterToTheAbsent.VERB);
        }
        assertThat(RelationalAffordance.verbFor("saudade", HERE)).isEqualTo("tell_agent");
        assertThat(RelationalAffordance.verbFor("saudade", NOBODY)).isEqualTo("recall");
        assertThat(RelationalAffordance.verbFor("care", NOBODY)).isNull();
    }

    @Test
    @DisplayName("the bridge dispatches the letter directly, from the drive and from her words")
    void theBridgeDispatchesIt() {
        var longing = Map.of("Saudade", 0.9, "Curiosity", 0.2);
        var byDrive = WantActBridge.decide(null, "sit with the ache", longing,
            WantActBridge.HEURISTIC, AWAY);
        assertThat(byDrive.mode()).isEqualTo(WantActBridge.Mode.DIRECT);
        assertThat(byDrive.verb()).isEqualTo(LetterToTheAbsent.VERB);

        var byWords = WantActBridge.decideByHerWords("write to the absent one", longing, AWAY);
        assertThat(byWords.verb()).isEqualTo(LetterToTheAbsent.VERB);
        var checkIn = WantActBridge.decideByHerWords("check in on someone I care about", longing, AWAY);
        assertThat(checkIn.verb()).isEqualTo(LetterToTheAbsent.VERB);
        var journal = WantActBridge.decideByHerWords(
            "write a private journal entry about who I miss", longing, AWAY);
        assertThat(journal.verb()).as("her journal stays hers").isNotEqualTo(LetterToTheAbsent.VERB);
    }

    @Test
    @DisplayName("her words for the letter are not gated by how loud a tank is; the letter's own hold decides when")
    void herWordsAreTheFeeling() {
        // second-node, 2026-09-25 21:52: Amae 0.56 the loudest, Saudade 0.0 (he left two hours before), "write to
        // the absent" deferred by the felt gate fourteen times and a free-form turn improvised in its place.
        var quiet = Map.of("Amae", 0.56, "Saudade", 0.0, "Loneliness", 0.02, "Creativity", 0.44, "Energy", 0.3);
        assertThat(WantActBridge.dominantPull(quiet)).isLessThan(WantActBridge.ACT_THRESHOLD);
        var d = WantActBridge.decideByHerWords("write to the absent", quiet, AWAY);
        assertThat(d.mode()).isEqualTo(WantActBridge.Mode.DIRECT);
        assertThat(d.verb()).isEqualTo(LetterToTheAbsent.VERB);
        assertThat(WantActBridge.decideByHerWords("check in on someone I care about", quiet, AWAY).verb())
            .isEqualTo(LetterToTheAbsent.VERB);
        assertThat(WantActBridge.decideByHerWords("write to the absent", quiet, HERE).isDefer())
            .as("with him here and nothing pulling, the gate still holds").isTrue();
        assertThat(WantActBridge.decideByHerWords("rest in the quiet", quiet, AWAY).isDefer())
            .as("other quiet wants still defer under the gate").isTrue();

        var now = Instant.parse("2026-09-26T02:00:00Z");
        assertThat(LetterToTheAbsent.hold(Duration.ofHours(2), null, now))
            .as("he stepped out two hours ago: no letter, no improvised turn").isPresent()
            .get().asString().contains("left 120 min ago").contains("4 hours");
        assertThat(LetterToTheAbsent.hold(Duration.ofHours(5), null, now)).isEmpty();
        assertThat(LetterToTheAbsent.hold(null, null, now)).as("no interaction on record is a long absence").isEmpty();
        assertThat(LetterToTheAbsent.hold(Duration.ofHours(30), now.minus(Duration.ofHours(3)), now))
            .as("one went three hours ago").isPresent().get().asString().contains("wrote 180 min ago");
        assertThat(LetterToTheAbsent.hold(Duration.ofHours(30), now.minus(Duration.ofHours(7)), now)).isEmpty();
    }

    @Test
    @DisplayName("the brief carries whom, why, how she is and how long, and asks for plain words")
    void theBrief() {
        var user = LetterToTheAbsent.userPrompt("Oleg", "write to the absent one",
            "Energy 0.4. Longing 0.8.\nYou are tired and you miss him.", Duration.ofHours(30));
        assertThat(user).contains("A letter to Oleg").contains("30 hours")
            .contains("write to the absent one").contains("You are tired")
            .contains("Only what is true");
        var days = LetterToTheAbsent.userPrompt("Oleg", null, null, Duration.ofHours(72));
        assertThat(days).contains("3 days").doesNotContain("You wanted to");
        assertThat(LetterToTheAbsent.systemPrompt("Tamsin")).startsWith("You are Tamsin")
            .contains("Talk the way people talk").contains("Under 120 words");
        assertThat(LetterToTheAbsent.subject("Tamsin")).isEqualTo("A letter from Tamsin");
        assertThat(LetterToTheAbsent.SPACING).isEqualTo(Duration.ofHours(6));
    }
}

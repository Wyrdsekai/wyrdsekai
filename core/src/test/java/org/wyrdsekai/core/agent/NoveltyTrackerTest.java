package org.wyrdsekai.core.agent;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Curiosity grounding (2026-06-02): novel perception → high novelty → SEEKING;
 * familiar repeats habituate. Pins the math + the event-signature semantics.
 */
class NoveltyTrackerTest {

    @Test
    void firstSightingIsFullyNovelThenHabituates() {
        var nt = new NoveltyTracker();
        assertThat(nt.observe("a")).isEqualTo(1.0);        // unseen → fully novel
        assertThat(nt.observe("a")).isEqualTo(0.5);        // 2nd → half
        assertThat(nt.observe("a")).isCloseTo(1.0 / 3, within(1e-9)); // 3rd → third
    }

    @Test
    void distinctSignaturesAreIndependentlyNovel() {
        var nt = new NoveltyTracker();
        assertThat(nt.observe("a")).isEqualTo(1.0);
        assertThat(nt.observe("b")).isEqualTo(1.0);        // a different thing is still novel
    }

    @Test
    void blankOrNullSignatureIsNotNovelAndNotRecorded() {
        var nt = new NoveltyTracker();
        assertThat(nt.observe(null)).isEqualTo(0.0);
        assertThat(nt.observe("")).isEqualTo(0.0);
        assertThat(nt.observe("a")).isEqualTo(1.0);        // not polluted by the blanks
    }

    @Test
    void evictionMakesAnOldSignatureNovelAgain() {
        var nt = new NoveltyTracker(2);                    // tiny window
        nt.observe("a");                                   // a seen
        nt.observe("b");                                   // b seen (window full: a,b)
        nt.observe("c");                                   // c evicts a (LRU)
        assertThat(nt.observe("a")).isEqualTo(1.0);        // a forgotten → novel again
    }

    @Test
    void signatureSeparatesSpeakerAndTopicButIgnoresTimestamp() {
        var said1 = new WorldEvent.Said("room", Instant.now(), "alice", "Alice", "hello there friend");
        var said2 = new WorldEvent.Said("room", Instant.parse("2020-01-01T00:00:00Z"),
            "alice", "Alice", "hello there friend");
        var said3 = new WorldEvent.Said("room", Instant.now(), "alice", "Alice", "a brand new topic");
        var said4 = new WorldEvent.Said("room", Instant.now(), "bob", "Bob", "hello there friend");

        // Same speaker + same topic → same signature regardless of timestamp (habituates).
        assertThat(NoveltyTracker.signatureFor(said1)).isEqualTo(NoveltyTracker.signatureFor(said2));
        // New topic from same speaker → different signature (registers as novel).
        assertThat(NoveltyTracker.signatureFor(said1)).isNotEqualTo(NoveltyTracker.signatureFor(said3));
        // Same topic from a new speaker → different signature (registers as novel).
        assertThat(NoveltyTracker.signatureFor(said1)).isNotEqualTo(NoveltyTracker.signatureFor(said4));
    }

    // ── Production signatures (2026-06-02 open-loop fix — own outputs satisfy drives) ──

    @Test
    void productionSignatureNamespaceNeverCollidesWithPerception() {
        // A produced "hello there friend" must not share the recency slot with a SAID one,
        // else hearing a line would pre-satisfy writing it (or vice versa).
        var said = NoveltyTracker.signatureFor(
            new WorldEvent.Said("room", Instant.now(), "alice", "Alice", "hello there friend"));
        var made = NoveltyTracker.signatureForProduction("journal", "hello there friend");
        assertThat(made).isNotEqualTo(said);
        assertThat(made).startsWith("produce|");
    }

    @Test
    void reproducingTheSameContentHabituates_butGenuinelyNewWorkStaysNovel() {
        var nt = new NoveltyTracker();
        var entry = "today I worked through the proof of the curiosity loop and it finally closed";
        double first = nt.observe(NoveltyTracker.signatureForProduction("journal", entry));
        double again = nt.observe(NoveltyTracker.signatureForProduction("journal", entry));
        assertThat(first).isEqualTo(1.0);   // genuinely new → satisfies
        assertThat(again).isEqualTo(0.5);   // re-journaling the same → habituates (anti-wirehead)
        // A different entry is fully novel again — real production keeps satisfying.
        double fresh = nt.observe(NoveltyTracker.signatureForProduction(
            "journal", "a wholly different thought about something else entirely"));
        assertThat(fresh).isEqualTo(1.0);
    }

    @Test
    void sameContentUnderDifferentKindsIsDistinct() {
        // Reflecting on X and journaling X are different acts — neither pre-satisfies the other.
        var a = NoveltyTracker.signatureForProduction("journal", "the same exact words here now");
        var b = NoveltyTracker.signatureForProduction("reflect", "the same exact words here now");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void blankProductionContentHasNoSignature() {
        assertThat(NoveltyTracker.signatureForProduction("journal", "")).isNull();
        assertThat(NoveltyTracker.signatureForProduction("journal", null)).isNull();
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}

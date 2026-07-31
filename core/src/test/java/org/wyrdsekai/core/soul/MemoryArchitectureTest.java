package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the memory architecture extensions:
 * 1. Significance buffer (remember/note/forget)
 * 2. Confidence scores on fragments
 * 3. Eviction summary
 * 4. Contradiction detection
 * 5. Bi-temporal facts
 */
class MemoryArchitectureTest {

    // --- 1. Significance Buffer ---

    @Nested
    class SignificanceBufferTests {

        @Test
        void remember_adds_entry() {
            var buf = new SignificanceBuffer();
            buf.remember("User prefers Earl Grey", 0.8f);
            assertEquals(1, buf.size());
            var entries = buf.peek();
            assertEquals(SignificanceBuffer.Source.AGENT_REMEMBER, entries.getFirst().source());
            assertEquals(0.8f, entries.getFirst().importance(), 0.01);
        }

        @Test
        void note_adds_lower_importance() {
            var buf = new SignificanceBuffer();
            buf.note("User asks about gardening on weekends");
            assertEquals(0.4f, buf.peek().getFirst().importance(), 0.01);
            assertEquals(SignificanceBuffer.Source.AGENT_NOTE, buf.peek().getFirst().source());
        }

        @Test
        void forget_marks_superseded() {
            var buf = new SignificanceBuffer();
            buf.forget("works at Mercari", "no longer true");
            var entry = buf.peek().getFirst();
            assertTrue(entry.superseded());
            assertEquals("works at Mercari", entry.target());
            assertEquals(SignificanceBuffer.Source.AGENT_FORGET, entry.source());
        }

        @Test
        void consume_clears_buffer() {
            var buf = new SignificanceBuffer();
            buf.remember("fact 1", 0.9f);
            buf.note("fact 2");
            assertEquals(2, buf.size());

            var consumed = buf.consumeAll();
            assertEquals(2, consumed.size());
            assertEquals(0, buf.size()); // cleared
        }

        @Test
        void overflow_drops_oldest() {
            var buf = new SignificanceBuffer();
            for (int i = 0; i < 60; i++) {
                buf.remember("fact " + i, 0.5f);
            }
            assertEquals(50, buf.size()); // capped at MAX_ENTRIES
        }

        @Test
        void importance_clamped() {
            var buf = new SignificanceBuffer();
            buf.remember("over", 1.5f);
            buf.remember("under", -0.5f);
            assertEquals(1.0f, buf.peek().get(0).importance(), 0.01);
            assertEquals(0.0f, buf.peek().get(1).importance(), 0.01);
        }
    }

    // --- 2. Confidence Scores ---

    @Nested
    class ConfidenceTests {

        @Test
        void new_fragment_has_default_confidence() {
            var f = SoulFragment.unembedded("test", "personality", "test", "content");
            assertEquals(0.5f, f.confidence(), 0.01);
            assertEquals(0, f.reinforcementCount());
        }

        @Test
        void reinforcement_increases_confidence() {
            var f = SoulFragment.unembedded("test", "personality", "test", "content");
            f = f.reinforce();
            assertTrue(f.confidence() > 0.5f);
            assertEquals(1, f.reinforcementCount());

            f = f.reinforce().reinforce().reinforce();
            assertTrue(f.confidence() > 0.6f, "4 reinforcements should raise confidence above 0.6");
            assertTrue(f.confidence() <= 0.95f, "Should not exceed asymptotic cap");
            assertEquals(4, f.reinforcementCount());
        }

        @Test
        void contradiction_halves_confidence() {
            var f = SoulFragment.unembedded("test", "personality", "test", "content");
            f = f.reinforce().reinforce().reinforce(); // build up confidence
            float before = f.confidence();

            f = f.contradict();
            assertEquals(before * 0.5f, f.confidence(), 0.05);
            assertTrue(f.confidence() >= 0.1f); // floor
        }

        @Test
        void confidence_never_drops_below_floor() {
            var f = SoulFragment.unembedded("test", "personality", "test", "content");
            f = f.contradict().contradict().contradict().contradict();
            assertTrue(f.confidence() >= 0.1f);
        }

        @Test
        void confidence_never_exceeds_cap() {
            var f = SoulFragment.unembedded("test", "personality", "test", "content");
            for (int i = 0; i < 100; i++) f = f.reinforce();
            assertTrue(f.confidence() <= 0.95f);
        }

        @Test
        void formative_fragments_start_higher() {
            var f = SoulFragment.formative("test", "first meeting", "We first met...");
            assertEquals(0.8f, f.confidence(), 0.01);
            assertEquals(1, f.reinforcementCount());
        }

        @Test
        void effective_confidence_decays_over_time() {
            // Create a fragment confirmed "40 days ago"
            var f = new SoulFragment("test", "personality", "test", "content",
                null, null, false, 0.8f, 3,
                Instant.now().minus(Duration.ofDays(60)),
                Instant.now().minus(Duration.ofDays(40)), // last confirmed 40 days ago
                null, null, null);

            // 40 days ago = 10 days past the 30-day grace period
            // decay = 10 * 0.002 = 0.02
            float effective = f.effectiveConfidence();
            assertTrue(effective < 0.8f, "Confidence should decay after 30 days");
            assertTrue(effective > 0.7f, "Decay should be gradual");
        }
    }

    // --- 3. Eviction Summary ---

    @Nested
    class EvictionSummaryTests {

        @Test
        void summarize_said_events() {
            var events = List.<WorldEvent>of(
                new WorldEvent.Said("room", Instant.now(),
                    "user1", "Alice", "I'm thinking about sourdough bread"),
                new WorldEvent.Said("room", Instant.now(),
                    "agent1", "Ember", "Let me look that up for you"),
                new WorldEvent.Said("room", Instant.now(),
                    "user1", "Alice", "Also wondering about gardening tips")
            );

            var summary = EvictionSummarizer.summarize(events, "agent1");
            assertNotNull(summary);
            assertTrue(summary.contains("Alice"), "Should mention the speaker");
            assertTrue(summary.contains("3 messages") || summary.contains("discussed"),
                "Should indicate conversation happened");
        }

        @Test
        void summarize_empty_returns_null() {
            assertNull(EvictionSummarizer.summarize(List.of(), "agent1"));
        }

        @Test
        void stack_summaries() {
            var stacked = EvictionSummarizer.stackSummaries(
                List.of("Discussed cooking recipes", "Talked about travel plans"),
                "Recently asked about gardening"
            );
            assertNotNull(stacked);
            assertTrue(stacked.contains("gardening"));
            assertTrue(stacked.contains("cooking") || stacked.contains("Earlier"));
        }
    }

    // --- 4. Contradiction Detection ---

    @Nested
    class ContradictionTests {

        @Test
        void detect_factual_contradiction() {
            assertTrue(ContradictionDetector.hasFactualContradiction(
                "The user is vegetarian and enjoys salads",
                "The user is not vegetarian and eats meat regularly"
            ));
        }

        @Test
        void no_contradiction_for_unrelated() {
            assertFalse(ContradictionDetector.hasFactualContradiction(
                "The user likes gardening",
                "The weather is sunny today"
            ));
        }

        @Test
        void detect_temporal_supersession() {
            assertTrue(ContradictionDetector.hasTemporalSupersession(
                "The user used to work at Mercari but left last year",
                "The user works at Mercari as VP Engineering"
            ));
        }

        @Test
        void no_supersession_without_temporal_indicator() {
            assertFalse(ContradictionDetector.hasTemporalSupersession(
                "The user works at Wyrdsekai",
                "The user is building something cool"
            ));
        }
    }

    // --- 5. Bi-Temporal Facts ---

    @Nested
    class BiTemporalTests {

        @Test
        void new_fragment_is_current() {
            var f = SoulFragment.unembedded("test", "personality", "test", "content");
            assertTrue(f.isCurrent());
            assertFalse(f.isSuperseded());
        }

        @Test
        void superseded_fragment_is_not_current() {
            var f = SoulFragment.unembedded("test", "personality", "test", "User works at Mercari");
            var superseded = f.supersede("new-fragment-id");
            assertFalse(superseded.isCurrent());
            assertTrue(superseded.isSuperseded());
            assertNotNull(superseded.supersededAt());
            assertEquals("new-fragment-id", superseded.supersededBy());
        }

        @Test
        void supersession_preserves_content() {
            var f = SoulFragment.unembedded("test", "personality", "test", "User works at Mercari");
            var superseded = f.supersede("new-id");
            assertEquals("User works at Mercari", superseded.text());
            assertEquals("test", superseded.id());
        }

        @Test
        void validFrom_tracks_when_fact_became_true() {
            var now = Instant.now();
            var f = new SoulFragment("test", "personality", "test", "content",
                null, null, false, 0.5f, 0, now, null, now, null, null);
            assertEquals(now, f.validFrom());
            assertNull(f.supersededAt());
        }
    }
}

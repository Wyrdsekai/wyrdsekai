package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionOutcomeTrackerTest {

    @Test
    void tokenize_drops_stopwords_and_short_tokens() {
        var tokens = PredictionOutcomeTracker.tokenize(
            "The user reads the news about gardening at home");
        assertThat(tokens).contains("user", "reads", "news", "gardening", "home");
        assertThat(tokens).doesNotContain("the", "at");
    }

    @Test
    void matched_topic_token_uses_word_boundaries() {
        var tokens = Set.of("garden");
        // Should match — surrounded by spaces
        assertThat(PredictionOutcomeTracker.matchedTopicToken(tokens, "i love my garden today"))
            .isEqualTo("garden");
        // Should NOT match — embedded in another word
        assertThat(PredictionOutcomeTracker.matchedTopicToken(tokens, "the gardenia is blooming"))
            .isNull();
    }

    @Test
    void matched_dismissal_finds_explicit_phrases() {
        assertThat(PredictionOutcomeTracker.matchedDismissal("not now, busy")).isEqualTo("not now");
        assertThat(PredictionOutcomeTracker.matchedDismissal("ugh, never mind")).isEqualTo("never mind");
        assertThat(PredictionOutcomeTracker.matchedDismissal("just talking about coffee")).isNull();
    }

    @Test
    void track_then_resolve_followed_up_writes_ledger(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var tracker = new PredictionOutcomeTracker(ledger);
        var firedAt = Instant.parse("2026-05-08T10:00:00Z");
        tracker.track("p1", "agent-A", "temporal", "User looks up gardening news", firedAt);

        var resolved = tracker.resolve("agent-A",
            "I was thinking about gardening today", firedAt.plusSeconds(30));

        assertThat(resolved).isNotNull();
        assertThat(resolved.predictionId()).isEqualTo("p1");
        assertThat(tracker.openCount()).isZero();

        var lines = Files.readAllLines(tmp.resolve("m4").resolve("outcomes.jsonl"));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("FOLLOWED_UP").contains("token=gardening");
    }

    @Test
    void resolve_dismissal_writes_dismissed_kind(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var tracker = new PredictionOutcomeTracker(ledger);
        var firedAt = Instant.parse("2026-05-08T10:00:00Z");
        tracker.track("p2", "agent-B", "topic", "Coffee preferences", firedAt);

        var resolved = tracker.resolve("agent-B", "not now please", firedAt.plusSeconds(10));

        assertThat(resolved).isNotNull();
        assertThat(resolved.predictionId()).isEqualTo("p2");
        var lines = Files.readAllLines(tmp.resolve("m4").resolve("outcomes.jsonl"));
        assertThat(lines.get(0)).contains("DISMISSED").contains("phrase=not now");
    }

    @Test
    void followed_up_wins_over_dismissed_when_both_match(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var tracker = new PredictionOutcomeTracker(ledger);
        var firedAt = Instant.parse("2026-05-08T10:00:00Z");
        tracker.track("p3", "agent-C", "topic", "gardening tips", firedAt);

        // Message contains BOTH a topic token and a dismissal phrase.
        var resolved = tracker.resolve("agent-C",
            "yeah gardening, but not now", firedAt.plusSeconds(10));

        var lines = Files.readAllLines(tmp.resolve("m4").resolve("outcomes.jsonl"));
        assertThat(lines.get(0)).contains("FOLLOWED_UP");
        assertThat(resolved.predictionId()).isEqualTo("p3");
    }

    @Test
    void reap_expired_writes_ignored_kind(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var tracker = new PredictionOutcomeTracker(ledger, Duration.ofMinutes(5));
        var firedAt = Instant.parse("2026-05-08T10:00:00Z");
        tracker.track("p4", "agent-D", "topic", "stock market update", firedAt);

        // Sweep at fired+6min — past 5-min window
        var reaped = tracker.reapExpired("agent-D", firedAt.plus(Duration.ofMinutes(6)));

        assertThat(reaped).hasSize(1);
        assertThat(reaped.get(0).predictionId()).isEqualTo("p4");
        var lines = Files.readAllLines(tmp.resolve("m4").resolve("outcomes.jsonl"));
        assertThat(lines.get(0)).contains("IGNORED").contains("expired");
    }

    @Test
    void reap_does_not_touch_unexpired_or_other_agents(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var tracker = new PredictionOutcomeTracker(ledger, Duration.ofMinutes(5));
        var firedAt = Instant.parse("2026-05-08T10:00:00Z");
        tracker.track("fresh", "agent-E", "topic", "current event", firedAt);
        tracker.track("other", "agent-F", "topic", "other topic", firedAt);

        // Only 1 minute past — neither expired
        var reaped = tracker.reapExpired("agent-E", firedAt.plus(Duration.ofMinutes(1)));
        assertThat(reaped).isEmpty();
        assertThat(tracker.openCount()).isEqualTo(2);

        // 6 min past, but reaping only agent-E
        reaped = tracker.reapExpired("agent-E", firedAt.plus(Duration.ofMinutes(6)));
        assertThat(reaped).hasSize(1);
        assertThat(tracker.openCount()).isEqualTo(1); // agent-F still open
    }

    @Test
    void resolve_filters_by_agent_id() {
        var tracker = new PredictionOutcomeTracker(null);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        tracker.track("a", "agent-A", "topic", "alpha word", t);
        tracker.track("b", "agent-B", "topic", "beta word", t);

        // agent-A speech that mentions B's topic must NOT resolve B's pending entry.
        var resolved = tracker.resolve("agent-A", "i was thinking about beta", t.plusSeconds(10));
        assertThat(resolved).isNull();
        assertThat(tracker.openCount()).isEqualTo(2);
    }

    @Test
    void cancel_drops_without_writing_ledger(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var tracker = new PredictionOutcomeTracker(ledger);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        tracker.track("p5", "agent-G", "topic", "anything", t);

        assertThat(tracker.cancel("p5")).isTrue();
        assertThat(tracker.openCount()).isZero();
        // No ledger entry written
        var file = tmp.resolve("m4").resolve("outcomes.jsonl");
        assertThat(Files.exists(file)).isFalse();
        assertThat(tracker.cancel("p5")).isFalse();
    }

    @Test
    void empty_message_does_not_resolve() {
        var tracker = new PredictionOutcomeTracker(null);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        tracker.track("p", "agent-H", "topic", "anything", t);
        assertThat(tracker.resolve("agent-H", "", t.plusSeconds(10))).isNull();
        assertThat(tracker.resolve("agent-H", "   ", t.plusSeconds(10))).isNull();
        assertThat(tracker.openCount()).isEqualTo(1);
    }

    @Test
    void resolve_skips_already_expired_entries() {
        var tracker = new PredictionOutcomeTracker(null, Duration.ofMinutes(5));
        var t = Instant.parse("2026-05-08T10:00:00Z");
        tracker.track("stale", "agent-I", "topic", "anything", t);

        // Resolution attempt past expiry should not match (reaper handles it instead).
        var resolved = tracker.resolve("agent-I", "anything happens here",
            t.plus(Duration.ofMinutes(10)));
        assertThat(resolved).isNull();
        // Entry remains (next reap will sweep it).
        assertThat(tracker.openCount()).isEqualTo(1);
    }
}

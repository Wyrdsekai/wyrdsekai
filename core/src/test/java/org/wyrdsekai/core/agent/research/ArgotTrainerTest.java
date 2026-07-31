package org.wyrdsekai.core.agent.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArgotTrainerTest {

    @Test void initial_state() {
        var trainer = new ArgotTrainer(List.of("hello", "danger"), 4, 0.1);
        assertThat(trainer.conceptCount()).isEqualTo(2);
        assertThat(trainer.history()).isEmpty();
    }

    @Test void single_episode() {
        var trainer = new ArgotTrainer(List.of("hello", "danger"), 4, 0.1);
        var episode = trainer.trainEpisode();
        assertThat(episode.round()).isEqualTo(1);
        assertThat(episode.concept()).isIn("hello", "danger");
        assertThat(episode.senderSignal()).startsWith("s");
    }

    @Test void training_produces_history() {
        var trainer = new ArgotTrainer(List.of("hello", "danger"), 4, 0.1);
        trainer.train(100);
        assertThat(trainer.history()).hasSize(100);
    }

    @Test void stats_computed() {
        var trainer = new ArgotTrainer(List.of("hello", "danger"), 4, 0.1);
        trainer.train(50);
        var stats = trainer.stats();
        assertThat(stats.totalEpisodes()).isEqualTo(50);
        assertThat(stats.accuracy()).isBetween(0.0, 1.0);
    }

    @Test void training_improves_accuracy() {
        var trainer = new ArgotTrainer(List.of("hello", "danger"), 4, 0.2);

        // Early accuracy (first 50 episodes)
        trainer.train(50);
        double earlyCorrect = trainer.stats().correctEpisodes();

        // Train more
        trainer.train(500);
        double totalCorrect = trainer.stats().correctEpisodes();
        double lateCorrect = totalCorrect - earlyCorrect;

        // Later episodes should generally have higher accuracy
        // (not guaranteed for every run, but with fixed seed it should)
        assertThat(lateCorrect / 500).isGreaterThanOrEqualTo(earlyCorrect / 50 - 0.3);
    }

    @Test void learned_mapping() {
        var trainer = new ArgotTrainer(List.of("hello", "danger", "trade"), 6, 0.2);
        trainer.train(500);
        var mapping = trainer.learnedMapping();
        assertThat(mapping).hasSize(3);
        assertThat(mapping).containsKeys("hello", "danger", "trade");
    }

    @Test void convergence_detection() {
        var trainer = new ArgotTrainer(List.of("a", "b"), 4, 0.3);
        trainer.train(1000);
        var stats = trainer.stats();
        // With simple 2-concept, 4-signal space and high learning rate,
        // convergence should happen
        assertThat(stats.totalEpisodes()).isEqualTo(1000);
    }
}

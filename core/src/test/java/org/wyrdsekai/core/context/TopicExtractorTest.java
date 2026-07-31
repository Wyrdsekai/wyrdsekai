package org.wyrdsekai.core.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TopicExtractor} -- keyword-frequency topic extraction.
 */
class TopicExtractorTest {

    @Test
    void extracts_frequent_words() {
        var messages = List.of(
            "We need to discuss the migration plan for the database",
            "The database migration is critical for the next release",
            "Have you started the database migration scripts?"
        );

        var topics = TopicExtractor.extractTopics(messages, 3);

        assertThat(topics).isNotEmpty();
        // "database" and "migration" should be the most frequent
        assertThat(topics).contains("Database", "Migration");
    }

    @Test
    void filters_stop_words() {
        var messages = List.of(
            "this is just about the thing that they were going to make",
            "they were going about their thing again and again"
        );

        var topics = TopicExtractor.extractTopics(messages, 5);

        // Stop words should be excluded -- "this", "just", "about", "that", etc.
        // Note: "again" is 5 chars and not in stop words, so it may appear
        assertThat(topics).doesNotContain("This", "Just", "About", "That", "They",
            "Were", "Going", "Their");
    }

    @Test
    void filters_short_words() {
        var messages = List.of(
            "go to the API and get the XML for me now",
            "API API XML XML get set go for"
        );

        var topics = TopicExtractor.extractTopics(messages, 5);

        // Words < 4 chars should be excluded: go, to, the, API, get, for, me, now, XML, set
        assertThat(topics).isEmpty();
    }

    @Test
    void respects_max_topics_limit() {
        var messages = List.of(
            "alpha bravo charlie delta echo foxtrot",
            "alpha bravo charlie delta echo foxtrot",
            "alpha bravo charlie delta echo foxtrot"
        );

        var topics = TopicExtractor.extractTopics(messages, 2);

        assertThat(topics).hasSize(2);
    }

    @Test
    void empty_input_returns_empty() {
        assertThat(TopicExtractor.extractTopics(null, 5)).isEmpty();
        assertThat(TopicExtractor.extractTopics(List.of(), 5)).isEmpty();
        assertThat(TopicExtractor.extractTopics(List.of("hello world"), 0)).isEmpty();
    }

    @Test
    void capitalizes_results() {
        var messages = List.of(
            "kubernetes deployment kubernetes deployment kubernetes"
        );

        var topics = TopicExtractor.extractTopics(messages, 2);

        assertThat(topics).contains("Kubernetes", "Deployment");
    }

    @Test
    void handles_null_messages_in_list() {
        var messages = new ArrayList<String>();
        messages.add("integration testing is important");
        messages.add(null);
        messages.add("integration testing framework");

        var topics = TopicExtractor.extractTopics(messages, 3);

        assertThat(topics).isNotEmpty();
        assertThat(topics).contains("Integration", "Testing");
    }
}

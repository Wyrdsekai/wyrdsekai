package org.wyrdsekai.core.memory;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.memory.ProbeClassifier.Temporal;

import static org.assertj.core.api.Assertions.assertThat;

class ProbeClassifierTest {

    @Test
    void pet_name_question() {
        var intent = ProbeClassifier.classify("what was my cat's name again");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("pet");
        assertThat(intent.entityRole()).isEqualTo("name");
        assertThat(intent.temporal()).isEqualTo(Temporal.ANY);
    }

    @Test
    void current_job_question() {
        var intent = ProbeClassifier.classify("what's my current job");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("occupation");
        assertThat(intent.entityRole()).isEqualTo("current");
        assertThat(intent.temporal()).isEqualTo(Temporal.LATEST);
    }

    @Test
    void where_grew_up_question() {
        var intent = ProbeClassifier.classify("where did I grow up");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("location");
        assertThat(intent.entityRole()).isEqualTo("hometown");
    }

    @Test
    void book_reading_question() {
        var intent = ProbeClassifier.classify("what book am I reading right now");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("book");
        assertThat(intent.entityRole()).isEqualTo("reading");
    }

    @Test
    void visit_question() {
        var intent = ProbeClassifier.classify("is anyone coming to visit soon");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("family");
    }

    @Test
    void language_learning_question() {
        var intent = ProbeClassifier.classify("what language was I trying to learn");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("language");
        assertThat(intent.entityRole()).isEqualTo("learning");
    }

    @Test
    void coffee_shop_question() {
        var intent = ProbeClassifier.classify("what's the name of the coffee shop I like");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("venue");
        assertThat(intent.entityRole()).isEqualTo("favorite");
    }

    @Test
    void food_allergy_question() {
        var intent = ProbeClassifier.classify("do I have any food allergies");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("allergy");
    }

    @Test
    void case_insensitive() {
        var intent = ProbeClassifier.classify("WHAT'S MY CURRENT JOB");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("occupation");
    }

    @Test
    void non_recall_returns_null() {
        assertThat(ProbeClassifier.classify("hello, how are you?")).isNull();
        assertThat(ProbeClassifier.classify("tell me a story")).isNull();
        assertThat(ProbeClassifier.classify("go to the kitchen")).isNull();
    }

    @Test
    void null_or_blank_returns_null() {
        assertThat(ProbeClassifier.classify(null)).isNull();
        assertThat(ProbeClassifier.classify("")).isNull();
        assertThat(ProbeClassifier.classify("   ")).isNull();
    }

    @Test
    void natural_recall_openers_match_pet() {
        assertThat(ProbeClassifier.classify("tell me about my cat").entityType())
                .isEqualTo("pet");
        assertThat(ProbeClassifier.classify("remind me what my dog's name is").entityType())
                .isEqualTo("pet");
        assertThat(ProbeClassifier.classify("do you remember my cat").entityType())
                .isEqualTo("pet");
        assertThat(ProbeClassifier.classify("what did I say about my dog").entityType())
                .isEqualTo("pet");
    }

    @Test
    void natural_recall_openers_match_occupation() {
        assertThat(ProbeClassifier.classify("tell me about my job").entityType())
                .isEqualTo("occupation");
        assertThat(ProbeClassifier.classify("remind me what I do for work").entityType())
                .isEqualTo("occupation");
        assertThat(ProbeClassifier.classify("do you remember my job").entityType())
                .isEqualTo("occupation");
    }

    @Test
    void natural_recall_openers_match_book() {
        assertThat(ProbeClassifier.classify("remind me what I'm reading").entityType())
                .isEqualTo("book");
        assertThat(ProbeClassifier.classify("do you remember the book I mentioned").entityType())
                .isEqualTo("book");
    }

    @Test
    void natural_recall_openers_match_allergy() {
        assertThat(ProbeClassifier.classify("did I mention my allergies").entityType())
                .isEqualTo("allergy");
        assertThat(ProbeClassifier.classify("remind me about my allergy").entityType())
                .isEqualTo("allergy");
    }

    @Test
    void natural_recall_openers_match_venue() {
        assertThat(ProbeClassifier.classify("tell me about my favorite cafe").entityType())
                .isEqualTo("venue");
        assertThat(ProbeClassifier.classify("remind me about that coffee shop").entityType())
                .isEqualTo("venue");
    }

    @Test
    void natural_recall_openers_match_family() {
        assertThat(ProbeClassifier.classify("who's coming over").entityType())
                .isEqualTo("family");
        assertThat(ProbeClassifier.classify("any visitors coming this weekend").entityType())
                .isEqualTo("family");
    }

    @Test
    void tell_me_a_story_does_not_match() {
        // Conversational opener — must NOT be intercepted as recall
        assertThat(ProbeClassifier.classify("tell me a story")).isNull();
        assertThat(ProbeClassifier.classify("tell me about yourself")).isNull();
    }

    @Test
    void embedded_in_prose_still_matches() {
        var intent = ProbeClassifier.classify(
                "Hey Wyrd, I was wondering, what's my current job again?");
        assertThat(intent).isNotNull();
        assertThat(intent.entityType()).isEqualTo("occupation");
    }
}

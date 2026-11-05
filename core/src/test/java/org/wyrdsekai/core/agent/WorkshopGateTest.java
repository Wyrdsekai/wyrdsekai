package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A question is the library's and reading is hers; neither is a job for the workshop. */
class WorkshopGateTest {

    @Test
    void a_request_to_research_is_a_question_for_the_library() {
        var d = "Research the history of Zen Buddhism and the different philosophical changes across each school. Cover: origins, Chan, Rinzai and Soto.";
        assertThat(WorkshopGate.isAQuestion(d)).isTrue();
        assertThat(WorkshopGate.theQuestion(d)).isEqualTo("the history of Zen Buddhism and the different philosophical changes across each school");
        assertThat(WorkshopGate.isAQuestion("Look into how tide pools form")).isTrue();
        assertThat(WorkshopGate.theQuestion("Look into how tide pools form")).isEqualTo("how tide pools form");
        assertThat(WorkshopGate.isAQuestion("What is the difference between p(x) and p(y|x)?")).isTrue();
    }

    @Test
    void a_thing_to_build_is_not_a_question() {
        assertThat(WorkshopGate.isAQuestion("Build a scrying crystal that shows the room's mood")).isFalse();
        assertThat(WorkshopGate.isAQuestion("A small listening tool that can check what is happening in any room")).isFalse();
        assertThat(WorkshopGate.isAQuestion("Fix the backup vault so snapshots persist across reloads")).isFalse();
        assertThat(WorkshopGate.isAQuestion("Research assistant: a tool that takes a question and searches the shelves")).isFalse();
    }

    @Test
    void reading_the_next_part_is_her_reading() {
        var d = "Read next part of Tomczak's Deep Generative Modeling (part 13), focusing on the p(y|x) vs p(x,y) distinction and what it means.";
        assertThat(WorkshopGate.isReading(d)).isTrue();
        assertThat(WorkshopGate.isAQuestion(d)).as("reading is not a question").isFalse();
        assertThat(WorkshopGate.thePart(d)).contains(13);
        assertThat(WorkshopGate.theBook(d)).contains("Tomczak's Deep Generative Modeling");
        assertThat(WorkshopGate.isReading("Read the book where we left off")).isTrue();
        assertThat(WorkshopGate.isReading("Read the room's mood and build a tool for it")).isFalse();
        assertThat(WorkshopGate.thePart("Read the next part")).isEmpty();
    }
}

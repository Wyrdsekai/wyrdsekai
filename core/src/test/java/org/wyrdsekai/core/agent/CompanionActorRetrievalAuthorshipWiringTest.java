package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The night's write trains on the speak rows the trail keeps under her name and leaves out a row
 * marked as the product's or a tool's words. A recall's findings ("Looking back, I find: mia said:
 * ...") and the book text of a Study read went out through plain {@code speak}, unmarked, and were
 * taken for her words (2026-09-22). A reading on her subject is an act of hers, written under the
 * id her night reads her rows by, so a result she reports just after it is not taken for a made-up
 * one.
 */
class CompanionActorRetrievalAuthorshipWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private static String between(String src, String from, String to) {
        int start = src.indexOf(from);
        assertThat(start).as("%s is in CompanionActor", from).isGreaterThanOrEqualTo(0);
        int end = src.indexOf(to, start + from.length());
        assertThat(end).as("%s follows %s", to, from).isGreaterThan(start);
        return src.substring(start, end);
    }

    @Test
    void a_recalls_findings_go_out_as_the_tools_words() throws Exception {
        var body = between(Files.readString(SRC), "private void handleRecall(", "} catch (Exception e)");
        assertThat(body).contains("speakFindings(sb.toString())");
        assertThat(body).doesNotContainPattern("\\bspeak\\(sb\\.toString\\(\\)\\)");
    }

    @Test
    void a_study_reads_book_text_goes_out_as_the_tools_words() throws Exception {
        var body = between(Files.readString(SRC),
            "if (\"study\".equals(action.source()))", "if (\"library\".equals(action.source()))");
        assertThat(body).contains("speakFindings(body");
        assertThat(body).doesNotContainPattern("\\bspeak\\(body");
    }

    @Test
    void a_reading_on_her_subject_is_written_under_her_entity_id() throws Exception {
        var body = between(Files.readString(SRC),
            "private Behavior<Command> onSubjectReadingDone(", "return this;\n    }");
        assertThat(body).contains("\"read_on_subject\"");
        assertThat(body).contains("var agentId = profile.entityId();");
    }
}

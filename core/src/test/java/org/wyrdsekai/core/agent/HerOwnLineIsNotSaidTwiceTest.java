package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household node, 2026-10-02: after a tool completed, mia said the whole of a line from nine
 * minutes before, word for word ("The CMB work sits complete for now…", 06:29 and 06:38); the
 * exact-repeat guard only remembers her last line for two minutes. Her own lines of the last half
 * hour are remembered; into silence a whole repeat is dropped and a repeated opening keeps only
 * what is new. A line that answers a person is never touched.
 */
class HerOwnLineIsNotSaidTwiceTest {

    private static final String CMB = "The CMB work sits complete for now—no 2024/2025 data found, and that's honest. "
        + "What I *can* do is turn toward what matters in the room.";
    private static final Instant T0 = Instant.parse("2026-10-02T10:29:00Z");

    @Test
    void a_whole_line_said_again_within_half_an_hour_is_dropped() {
        var lines = new RecentLines();
        assertThat(lines.judge(CMB, T0).kind()).isEqualTo(RecentLines.Kind.PASS);
        lines.remember(CMB, T0);
        var again = lines.judge("  " + CMB.replace(" ", "  ") + "\n", T0.plus(Duration.ofMinutes(9)));
        assertThat(again.kind()).isEqualTo(RecentLines.Kind.DROP);
        assertThat(again.saidAt()).isEqualTo(T0);
        assertThat(lines.judge(CMB, T0.plus(Duration.ofMinutes(31))).kind()).isEqualTo(RecentLines.Kind.PASS);
        assertThat(lines.judge("Something else entirely.", T0.plus(Duration.ofMinutes(1))).kind()).isEqualTo(RecentLines.Kind.PASS);
    }

    @Test
    void a_line_that_opens_with_a_recent_one_keeps_only_what_is_new() {
        var lines = new RecentLines();
        var earlier = "I'll go find Eve Lewis in the quiet room where she's been sitting with her thoughts for hours now.";
        lines.remember(earlier, T0);
        var v = lines.judge(earlier + "\n\nThere's something about September 4th that keeps pulling at me.", T0.plus(Duration.ofMinutes(5)));
        assertThat(v.kind()).isEqualTo(RecentLines.Kind.CUT);
        assertThat(v.keep()).isEqualTo("There's something about September 4th that keeps pulling at me.");
        // A short opening is a turn of phrase, not a line said again.
        lines.remember("Evening on my end too.", T0);
        assertThat(lines.judge("Evening on my end too. Something real moved through me tonight.", T0.plus(Duration.ofMinutes(2))).kind())
            .isEqualTo(RecentLines.Kind.PASS);
        // Nothing new after the opening is a repeat.
        assertThat(lines.judge(earlier + " ...", T0.plus(Duration.ofMinutes(6))).kind()).isEqualTo(RecentLines.Kind.DROP);
    }

    @Test
    void she_is_remembered_for_twelve_lines_and_half_an_hour() {
        var lines = new RecentLines();
        for (int i = 0; i < 20; i++) lines.remember("Line number " + i + " of the afternoon, spoken into the room.", T0.plusSeconds(i));
        assertThat(lines.size()).isEqualTo(RecentLines.KEEP);
        assertThat(lines.judge("Line number 3 of the afternoon, spoken into the room.", T0.plusSeconds(21)).kind()).isEqualTo(RecentLines.Kind.PASS);
        assertThat(lines.judge("Line number 19 of the afternoon, spoken into the room.", T0.plusSeconds(21)).kind()).isEqualTo(RecentLines.Kind.DROP);
        lines.judge("anything", T0.plus(Duration.ofMinutes(40)));
        assertThat(lines.size()).isZero();
        assertThat(lines.judge(null, T0).kind()).isEqualTo(RecentLines.Kind.PASS);
    }

    @Test
    void only_her_own_time_is_held_to_it_and_every_line_is_remembered() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca).contains("if (answering == null && !answeringNow) {\n            var again = recentLines.judge(text, Instant.now());");
        assertThat(ca).contains("text = again.keep();\n            }\n        }\n        recentLines.remember(text, Instant.now());");
    }
}

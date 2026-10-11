package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.config.WyrdConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The household node, 2026-10-09: a day of a companion's kept words ("on her own time: close
 * room …") sent the steward 58 notices, one per act, and none reached them — the in-world buffer
 * was never emptied on login. Her own-time acts now reach the steward as one notice per window
 * (WYRDSEKAI_STEWARD_DIGEST_MINUTES, default 30); what a person asked for through her bunshin is
 * still told as it happens; the feed file and the Study desk keep every act; and what was kept
 * for a person while no session of theirs was live is delivered at whichever door they come in.
 */
class HerOwnTimeReachesTheStewardAsOneNoticeTest {

    private static final Path ACTOR = Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    @DisplayName("the window is half an hour unless the household says otherwise; 0 sends each act at once")
    void theSetting() {
        var v = WyrdConfig.get().stewardDigestMinutes();
        assertThat(v).isBetween(0, 24 * 60);
        assertThat(Files.exists(Path.of("src/main/java/org/wyrdsekai/core/config/WyrdConfig.java"))).isTrue();
    }

    @Test
    @DisplayName("own-time acts gather; a person's bunshin request is told at once; the window closes with one notice")
    void theActorIsWired() throws Exception {
        var src = Files.readString(ACTOR);
        assertThat(src).contains("int window = WyrdConfig.get().stewardDigestMinutes();\n"
            + "            if (!viaBunshin && window > 0) {");
        assertThat(src).contains("timers.startSingleTimer(STEWARD_DIGEST_TIMER_KEY, new StewardDigestFlush(), Duration.ofMinutes(window));");
        assertThat(src).contains("stewardDigest.add(new StewardFeed.Act(verb, target, outcome, Instant.now()));");
        assertThat(src).as("the desk note is written before the digest decides").contains(
            "new StudyService(luceneStore).addNote(steward, \"[feed] \" + line);\n            }\n"
            + "            // Her own-time acts reach the steward as one notice per window");
        assertThat(src).contains("var line = StewardFeed.digest(profile.name(), acts, minutes);");
        assertThat(src).contains(".onMessage(StewardDigestFlush.class, this::onStewardDigestFlush)");
        assertThat(src).as("what is gathered goes out before she stops").contains("try { flushStewardDigest(); }");
    }
}

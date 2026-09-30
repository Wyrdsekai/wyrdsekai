package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tell voice pass takes its default from {@code WyrdConfig.voicePass()}, the one place that
 * knows the serving profile. Household node, 2026-09-23: the gate defaulted to
 * {@code voiceEnabled()}, a single-sparse node still carried WYRDSEKAI_VOICE_ENABLED=true from its
 * two-model install, and a companion's question to another companion went out as an answer.
 */
class TheTellVoicePassFollowsTheServingProfileTest {

    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    void theGateAsksTheConfigNotTheVoiceKey() throws IOException {
        var src = Files.readString(ACTOR);
        var at = src.indexOf("private boolean shouldRunVoicePass(String message)");
        assertThat(at).as("shouldRunVoicePass not found").isPositive();
        var end = src.indexOf("if (message == null || message.isBlank()) return false;", at);
        assertThat(end).as("the gate ends before the message checks").isGreaterThan(at);
        var gate = src.substring(at, end);
        assertThat(gate).contains("WyrdConfig.get().voicePass()");
        assertThat(gate).as("a second copy of the default would ignore the serving profile")
            .doesNotContain("voiceEnabled()");
    }
}

package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ("SUSPECTED flags decay to NONE after 90 days if no signals"):
 * {@code ProtectionFlagTracker.decayStaleFlags} did this and was tested, but no production
 * code called it, so a flag once raised never lifted. The sweep now runs once per sleep,
 * before the trackers are saved. The sweep's own rules are in {@code ProtectionFlagDecayTest}.
 */
class ProtectionFlagsLiftAtSleepTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    void every_sleep_sweeps_stale_flags_before_saving_them() throws Exception {
        var src = Files.readString(SRC);
        int start = src.indexOf("private void completeSleep(");
        int end = src.indexOf("\n    private Behavior<Command> onRegisterRoomImprints", start + 100);
        var body = src.substring(start, end > 0 ? end : src.length());
        int sweep = body.indexOf("protectionFlags.decayStaleFlags(");
        int save = body.indexOf("persistSubstrateTrackers();");
        assertThat(sweep).as("completeSleep runs the §8.2 sweep").isGreaterThan(0);
        assertThat(save).isGreaterThan(sweep);
    }
}

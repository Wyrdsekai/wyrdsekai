package org.wyrdsekai.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node has one forge, event-sourced. On the household node it stopped twice (2026-10-02,
 * 2026-10-11) when a store failed inside one of its side effects, and nothing brought it back
 * until the next restart. It is spawned under a restart-with-backoff supervisor now.
 */
class TheForgeIsSupervisedTest {

    @Test
    @DisplayName("Main spawns the forge under a supervisor that restarts it")
    void supervised() throws Exception {
        var main = Files.readString(Path.of("src/main/java/org/wyrdsekai/server/Main.java"));
        assertThat(main).contains("Behaviors.supervise(ForgeActor.create(soulStore))\n"
            + "                .onFailure(SupervisorStrategy.restartWithBackoff(");
        assertThat(main).doesNotContain("ForgeActor.create(soulStore), \"forge-actor\", Props.empty());");
    }
}

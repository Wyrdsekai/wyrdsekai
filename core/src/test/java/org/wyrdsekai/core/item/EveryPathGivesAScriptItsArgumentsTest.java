package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every path that runs an item script must hand it the same parameters.
 *
 * <h2>Why this is a source scan and not a unit test</h2>
 * The items-as-tools contract promises {@code params.args}. Five different places invoke
 * an item script, and on 2026-08-21 each one built its own parameter map:
 *
 * <ul>
 *   <li><b>SSH shell</b> — fixed in the morning, and a test written that proved it.</li>
 *   <li><b>WebSocket</b> — fixed alongside it.</li>
 *   <li><b>Telnet</b> — had no carried-item path at all until that day.</li>
 *   <li><b>RoomActor, coding-item</b> — set {@code query} alone, so a backend-authored
 *       item used from the FLOOR got no arguments. Live: {@code use weather_lookup
 *       cambridge ma} answered "(no arguments supplied)".</li>
 *   <li><b>VirtualSessionHandler</b> — relay and phone visitors; set
 *       {@code target}/{@code entityId}/{@code entityName} and no {@code args}.</li>
 * </ul>
 *
 * <p>Each was found by a person using the product and hitting the one path nobody had
 * touched. Fixing them one at a time is what made the day feel like a loop: a unit test
 * covering one route through a fork proves only that route, and there was never anything
 * that knew how many routes existed.
 *
 * <p>So this counts them. Add a sixth invocation site and it fails until that site uses
 * the shared builder — which is the only version of this that stops the cycle.
 */
class EveryPathGivesAScriptItsArgumentsTest {

    /** Where an item script is actually run, outside tests and the smoke harness. */
    private static final List<String> INVOCATION_SITES = List.of(
        "core/src/main/java/org/wyrdsekai/core/room/RoomActor.java",
        "server/src/main/java/org/wyrdsekai/server/ssh/WyrdShellCommand.java",
        "server/src/main/java/org/wyrdsekai/server/ws/WyrdWebSocket.java",
        "server/src/main/java/org/wyrdsekai/server/telnet/TelnetSession.java",
        "server/src/main/java/org/wyrdsekai/server/session/VirtualSessionHandler.java");

    @Test
    void every_invocation_site_builds_its_params_from_the_shared_builder() throws Exception {
        var offenders = new ArrayList<String>();
        for (var rel : INVOCATION_SITES) {
            var path = find(rel);
            var src = Files.readString(path);
            if (!src.contains("CarriedItemUse.params")) {
                offenders.add(path.getFileName() + " runs item scripts without "
                    + "CarriedItemUse.params — it will build its own and forget something");
            }
        }
        assertThat(offenders)
            .as("one builder, so `what does a script receive` has one answer")
            .isEmpty();
    }

    /**
     * And nothing hand-rolls the key the contract promises. A site that puts "args" in a
     * map itself has, by definition, stopped sharing the builder.
     */
    @Test
    void no_site_hand_rolls_the_args_key() throws Exception {
        var offenders = new ArrayList<String>();
        for (var rel : INVOCATION_SITES) {
            var path = find(rel);
            for (var line : Files.readAllLines(path)) {
                var code = line.strip();
                if (code.startsWith("*") || code.startsWith("//")) continue;
                if (code.contains("params.put(\"args\"")) {
                    offenders.add(path.getFileName() + ": " + code);
                }
            }
        }
        assertThat(offenders)
            .as("use CarriedItemUse.params — a second definition drifts from the first")
            .isEmpty();
    }

    /** The builder itself keeps the promise the contract makes. */
    @Test
    void the_shared_builder_supplies_every_spelling() {
        var params = CarriedItemUse.params("who", "salt almanac");
        assertThat(params).containsKeys("args", "target", "query", "entityId");
        assertThat(params.get("args")).isEqualTo("salt almanac");
    }

    private static Path find(String repoRelative) {
        for (var candidate : List.of(repoRelative, "../" + repoRelative,
                repoRelative.replaceFirst("^core/", ""))) {
            var p = Path.of(candidate);
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException("not found from " + System.getProperty("user.dir")
            + ": " + repoRelative + " — this guard must never silently pass");
    }
}

package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the person sees in the room must be what {@code use} accepts.
 *
 * <h2>What went wrong</h2>
 * Told its item name broke the loader's rule, goose wrote a corrected second file and left
 * the first. Registration takes the first file that PASSES the gates; the room object was
 * named from the first file that merely PARSED. Live on staging 2026-08-22 the Study
 * showed {@code mediamisc-organizer} — the rejected file — while the item that registered
 * was {@code organize_media_mirrors}. Typing the visible name did nothing; the working
 * tool had no object at all. The steward had asked for a tool and was left with no name
 * that worked.
 */
class TheObjectAndTheToolShareANameTest {

    private static String item(String name, boolean valid) {
        return """
            exports.manifest = {
              name: "%s",
              version: "1.0.0",
              description: "Sorts things.",
              author: "did:wyrd:test",
              capabilities: [],
              %s
              commands: [{ label: "Sort", args: "" }]
            };
            function invoke(params) { return { ok: true, summary: "sorted" }; }
            """.formatted(name, valid
                ? "embodiment: { silent: false, emits: [\"body_language\"], "
                    + "descriptor_template: \"{actor} sorts\" },"
                : "embodiment: { silent: false, emits: [\"body_language\"], "
                    + "descriptor_template: \"{actor} sorts\" },");
    }

    private static SourceArtifact artifact(Path ws, String... files) {
        return new SourceArtifact(UUID.randomUUID(), "goose", "task-1", ws.toString(),
            List.of(files), null, Instant.now(), Map.of());
    }

    @Test
    @DisplayName("the object is named for the file that will register, not the abandoned one")
    void theRegisteredNameWins(@TempDir Path ws) throws Exception {
        // The backend's first attempt breaks the loader's name rule; its second is clean.
        Files.writeString(ws.resolve("media-organizer.js"), item("media-organizer", true));
        Files.writeString(ws.resolve("organize_media.js"), item("organize_media", true));

        var chosen = CodingTaskItemBridge.manifestNameOf(
            artifact(ws, ws.resolve("media-organizer.js").toString(),
                         ws.resolve("organize_media.js").toString()));

        assertThat(chosen)
            .as("the room must show the name `use` will accept")
            .contains("organize_media");
    }

    @Test
    @DisplayName("with nothing clean, the object still gets the item's own name")
    void aBrokenItemIsStillNamedForItself(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("media-organizer.js"), item("media-organizer", true));

        assertThat(CodingTaskItemBridge.manifestNameOf(
                artifact(ws, ws.resolve("media-organizer.js").toString())))
            .as("a task that produced only a broken item is still placed, and still says "
                + "what it was meant to be")
            .contains("media-organizer");
    }

    /**
     * Registration must agree with naming. goose wrote the same tool three times;
     * declared order put the broken one first, it was rejected, and the two working
     * files beside it were never tried. The object was named for one file and the
     * registry held none.
     */
    @Test
    @DisplayName("registration tries compliant files before broken ones, whatever order they were declared")
    void registrationIsComplianceFirst() throws Exception {
        var src = java.nio.file.Files.readString(java.nio.file.Path.of(
            java.nio.file.Files.exists(java.nio.file.Path.of("core/src/main/java"))
                ? "core/src/main/java/org/wyrdsekai/core/coding/CodingTaskItemBridge.java"
                : "../core/src/main/java/org/wyrdsekai/core/coding/CodingTaskItemBridge.java"));
        var sort = src.indexOf("candidates.sort(Comparator.comparingInt");
        var loop = src.indexOf("for (var p : candidates) {");
        assertThat(sort).isGreaterThan(0);
        assertThat(sort).as("the sort must precede the registration loop").isLessThan(loop);
    }
}

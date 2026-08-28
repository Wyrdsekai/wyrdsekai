package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding the object a task placed, by the link that was stamped rather than by prose.
 *
 * <h2>The join that broke</h2>
 * The dispatch hand-off in {@code CompanionActor} found the freshly placed artifact by
 * scanning the room for an object whose DESCRIPTION contained the task id. That only ever
 * worked because the description was the codex boilerplate —
 * {@code "A goose codex containing 1 file(s) for task <uuid>"}.
 *
 * <p>On 2026-08-20 the description was replaced with what the item says about itself,
 * which was the right change and is what a person actually needs to read. The task uuid
 * went with it. Live 2026-08-21, seconds apart in the same log:
 *
 * <pre>
 *   06:46:53 Placed 1 goose item(s) in room nexus (task c0854c10-…)
 *   06:47:01 Dispatch hand-off: nothing placed for task c0854c10-… after 4 looks
 * </pre>
 *
 * <p>She had made the thing and could not give it to the person who asked for it.
 *
 * <p>Making a description prettier must not be able to sever a lookup. The bridge already
 * stamps the registry with the link at placement time; that is what the hand-off reads
 * now.
 */
class SheCanFindWhatSheJustMadeTest {

    @BeforeEach
    void setUp() {
        CodingItemRegistry.get().clear();
    }

    @Test
    void the_registry_answers_which_objects_a_task_placed() {
        CodingItemRegistry.get().stamp(new CodingItemMetadata(
            "codex-aaa", "goose", "task-1", UUID.randomUUID(), "codex"));
        CodingItemRegistry.get().stamp(new CodingItemMetadata(
            "codex-bbb", "goose", "task-1", UUID.randomUUID(), "codex"));
        CodingItemRegistry.get().stamp(new CodingItemMetadata(
            "codex-ccc", "goose", "task-2", UUID.randomUUID(), "codex"));

        assertThat(CodingItemRegistry.get().roomObjectsForTask("task-1"))
            .containsExactlyInAnyOrder("codex-aaa", "codex-bbb");
        assertThat(CodingItemRegistry.get().roomObjectsForTask("task-2"))
            .containsExactly("codex-ccc");
    }

    /**
     * The whole point: the description no longer carries the task id, and the lookup has
     * to keep working anyway.
     */
    @Test
    void the_lookup_does_not_depend_on_the_description() {
        CodingItemRegistry.get().stamp(new CodingItemMetadata(
            "codex-ee2f19e2", "goose", "c0854c10", UUID.randomUUID(), "codex"));
        var description = "Queries the library and speaks the result as a story.";
        assertThat(description).doesNotContain("c0854c10");
        assertThat(CodingItemRegistry.get().roomObjectsForTask("c0854c10"))
            .containsExactly("codex-ee2f19e2");
    }

    @Test
    void an_unknown_task_places_nothing() {
        assertThat(CodingItemRegistry.get().roomObjectsForTask("nobody")).isEmpty();
        assertThat(CodingItemRegistry.get().roomObjectsForTask(null)).isEmpty();
        assertThat(CodingItemRegistry.get().roomObjectsForTask("  ")).isEmpty();
    }
}

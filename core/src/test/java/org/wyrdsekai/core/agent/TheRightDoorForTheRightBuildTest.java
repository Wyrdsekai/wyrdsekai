package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three build doors are not interchangeable.
 *
 * <p>Offering all of them is how one request got answered by the wrong one three times
 * running on the household node: delegated to the coding backend with no publish so the
 * result was dropped (2026-08-19), crafted from a {@code mailbox} template into a
 * behaviourless container (2026-08-20), and finally declined after {@code list_templates}
 * showed nothing that fit (2026-08-20).
 *
 * <p>The correction is a routing one, and it reverses an earlier mistake of mine. The
 * coding backend is the item AUTHOR: every dispatched task is prepended with the
 * items-as-tools contract, the backend emits one {@code .js} carrying
 * {@code exports.manifest} and {@code invoke()}, and {@link
 * org.wyrdsekai.core.coding.CodingTaskItemBridge} validates it, smoke-tests it, registers
 * it so {@code use} works, and places it in the room. Asking the local drive model to
 * write that script instead was the wrong door — proven live: narrowed to the workbench
 * with {@code tool_choice=required}, told the exact APIs in a tool result, and with
 * {@code script} promoted into the schema's {@code required} array, the 9B still called
 * {@code craft_from_template} with no script every time.
 */
class TheRightDoorForTheRightBuildTest {

    private static final String BEHAVIOUR_REQUEST =
        "so can you make me a tool / item that allows me to query the library and then "
        + "whatever it finds it speaks out lout to the room a story based on what it found";

    @Test
    void a_thing_that_must_do_something_goes_to_the_coding_backend() {
        var doors = CompanionActor.buildFirstToolsFor(BEHAVIOUR_REQUEST);
        assertThat(doors).contains("dispatch_task");
        assertThat(doors)
            .as("a template cannot hold behaviour — offering it invites the wrong answer")
            .doesNotContain("craft_from_template");
    }

    @Test
    void a_thing_that_merely_is_something_stays_on_the_template_path() {
        // A template holds these whole; sending them to a coding backend is overkill.
        for (var plain : new String[] {"make me a book", "can you craft a lantern"}) {
            assertThat(CompanionActor.buildFirstToolsFor(plain))
                .as(plain)
                .contains("craft_from_template")
                .doesNotContain("dispatch_task");
        }
    }

    @Test
    void a_place_rides_the_room_builder() {
        var doors = CompanionActor.buildFirstToolsFor("how about making us a greenhouse room");
        assertThat(doors).contains("create_room_from_template");
        assertThat(doors).doesNotContain("craft_from_template", "dispatch_task");
    }

    @Test
    void every_door_still_allows_a_refusal() {
        // A force may compel a choice, never an assent.
        for (var req : new String[] {
                BEHAVIOUR_REQUEST, "make me a book", "build us a greenhouse room", ""}) {
            assertThat(CompanionActor.buildFirstToolsFor(req))
                .as("refusal must survive every routing branch")
                .contains("decline_with_reason");
        }
    }

    @Test
    void every_routed_door_is_a_real_build_tool() {
        // The dispatch narrows by set membership; a name outside BUILD_FIRST_TOOLS would
        // narrow the surface to nothing and ship an empty tool list.
        for (var req : new String[] {BEHAVIOUR_REQUEST, "make me a book", "build a room"}) {
            assertThat(CompanionActor.BUILD_FIRST_TOOLS)
                .as(req)
                .containsAll(CompanionActor.buildFirstToolsFor(req));
        }
    }

    @Test
    void the_backend_is_described_as_able_to_make_a_world_item() {
        // This description is the only account she has of what the tool can do. It used
        // to say "It cannot make something in the world", which was false and is exactly
        // why she stopped choosing the one door that works.
        var desc = ActionToolBuilder.descriptionFor("dispatch_task");
        assertThat(desc).isNotNull();
        assertThat(desc.toLowerCase())
            .contains("behaviour")
            .doesNotContain("cannot make something in the world");
    }
}

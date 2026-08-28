package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request to build must reach the workbench, not the code backend.
 *
 * <p>Build-first was given teeth on the direct path — narrowed to the build tools with
 * {@code tool_choice=required}, because the greenhouse failure showed a model will happily
 * DESCRIBE the thing instead of making it. On the ReAct path it only PINNED a build tool
 * into the menu and left everything else beside it.
 *
 * <p>Live on the household node 2026-08-19. Asked for "a tool/item that queries the
 * library … and speaks a story out loud", routing worked perfectly — library-first armed,
 * build-first correctly won, build-first armed — and then the surface offered both
 * {@code craft_from_template} and {@code dispatch_task}. She chose dispatch_task: "I'll
 * hand it to goose". Goose is the CODING backend; it reported {@code touching 0 file(s)}
 * because an in-world item is not a source edit. With nothing built and the steward asking
 * for his tool, she handed over an existing possession of her own and said "Here it is" —
 * an object with no script, which answered {@code use} with {@code not_found}.
 *
 * <p>Nothing in that chain was the companion misbehaving. The bench was on the menu the
 * whole time, next to a tool that sounded like delegating the work.
 */
class BuildRequestOpensTheBenchTest {

    @Test
    void the_request_that_went_to_goose_is_a_build_request() {
        var asked = "can you create me a tool/item that queries the library with whatever "
            + "keyword i would like to query and then whatever it finds in the library it "
            + "speaks out loud in the room a story about what it found?";
        assertThat(CompanionActor.asksForAnArtifact(asked)).isTrue();
        assertThat(CompanionActor.looksLikeBuildRequest(asked)).isTrue();
    }

    @Test
    void the_build_tool_set_carries_every_door_and_routing_picks_one() {
        // REVERSED 2026-08-20. This used to assert dispatch_task must NEVER be what a
        // "make me a thing" request narrows to. That was wrong, and it is the belief that
        // kept her away from the only path that can produce an item with behaviour: the
        // coding backend authors the .js, and CodingTaskItemBridge validates, smoke-tests,
        // registers and places it. The set carries all three doors; buildFirstToolsFor()
        // picks exactly one per request, which is what BuildRequestOpensTheBench is
        // really about — she must not be handed a menu and left to guess.
        assertThat(CompanionActor.BUILD_FIRST_TOOLS)
            .contains("craft_from_template", "create_room_from_template", "dispatch_task");
        assertThat(CompanionActor.BUILD_FIRST_TOOLS)
            .as("a bunshin is a helper, not a build door; add_script edits an existing item")
            .doesNotContain("dispatch_bunshin", "add_script");
    }

    @Test
    void declining_stays_available_so_a_force_never_compels_assent() {
        // A force may compel a CHOICE, never an agreement — she must always be able to
        // say no to building something.
        assertThat(CompanionActor.BUILD_FIRST_TOOLS).contains("decline_with_reason");
    }

    @Test
    void a_room_request_still_rides_the_room_builder_not_the_bench() {
        var asked = "can we have a room for the plants";
        assertThat(CompanionActor.looksLikeBuildRequest(asked)).isTrue();
        assertThat(CompanionActor.asksForAnArtifact(asked))
            .as("a room is not an artifact — it must keep riding create_room_from_template")
            .isFalse();
    }

    @Test
    void my_change_only_widens_what_counts_as_building_never_narrows_it() {
        // The artifact clause is additive: anything that matched before still matches.
        assertThat(CompanionActor.looksLikeBuildRequest("make us a room")).isTrue();
        assertThat(CompanionActor.looksLikeBuildRequest("we need a space to sit")).isTrue();
        // And things that were never build requests still are not.
        assertThat(CompanionActor.looksLikeBuildRequest("what did the library say")).isFalse();
        assertThat(CompanionActor.looksLikeBuildRequest("make me a list from my books"))
            .as("a lookup wearing a making-verb stays a lookup")
            .isFalse();
    }
}

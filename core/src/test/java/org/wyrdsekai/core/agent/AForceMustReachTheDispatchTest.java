package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A force that narrows a tool list the dispatch is about to throw away is not a force.
 *
 * <p>{@code onReactDispatch} rebuilds its surface from {@code buildScopedTools()} on every
 * step. Build-first narrowed the CALLER's list instead, before the loop started — so the
 * narrowing was computed, logged as "narrowed 9 → 2 tools", and then discarded, and
 * {@code tool_choice} was never raised to {@code required}. Every build request reached
 * the companion as ~25 tools on {@code tool_choice=auto}.
 *
 * <p>Three live failures on the household node were three different samples from that one
 * wide menu, not three defects: she handed an in-world item to the coding backend
 * (2026-08-19); she crafted a container with no behaviour and reported it ready
 * (2026-08-20); she called {@code list_templates}, found nothing that fit, and declined
 * (2026-08-20). Each time the log said the workbench had been forced.
 *
 * <p>Library-first was always correct — consumed inside the dispatch, with
 * {@code tool_choice=required}. This test pins both to the same place, because the failure
 * is invisible at runtime: the narrowing SUCCEEDS, it just narrows the wrong list.
 */
class AForceMustReachTheDispatchTest {

    private static String dispatchMethodSource() throws IOException {
        var src = Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        if (!Files.exists(src)) {
            src = Path.of("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        }
        var text = Files.readString(src);
        int start = text.indexOf("private Behavior<Command> onReactDispatch(");
        assertThat(start).as("onReactDispatch must exist — rename me if it moved")
            .isGreaterThan(0);
        // Up to the log line that ships the surface; everything narrowing the dispatch
        // has to happen before it.
        int end = text.indexOf("log.info(\"ReAct dispatch step {} with {} tools", start);
        assertThat(end).as("the dispatch's own surface log must exist").isGreaterThan(start);
        return text.substring(start, end);
    }

    @Test
    void build_first_is_consumed_inside_the_dispatch() throws IOException {
        // The narrowing set is now chosen per request by buildFirstToolsFor() — a place,
        // a behaviour, or a plain object each get one door rather than a menu — so this
        // pins the routing call rather than the old flat BUILD_FIRST_TOOLS constant. The
        // invariant is unchanged and is the whole point: whatever set is chosen, it must
        // be applied to the list the dispatch actually ships.
        assertThat(dispatchMethodSource())
            .as("build-first must narrow the list the dispatch actually ships")
            .contains("buildFirstPending = false")
            .contains("buildFirstToolsFor");
    }

    @Test
    void library_first_is_consumed_inside_the_dispatch() throws IOException {
        // The one that was always right — kept here so the pair cannot drift apart.
        assertThat(dispatchMethodSource())
            .contains("libraryFirstPending = false")
            .contains("LIBRARY_FIRST_TOOLS");
    }

    @Test
    void every_dispatch_force_raises_tool_choice_to_required() throws IOException {
        // Narrowing alone leaves tool_choice=auto, which lets the model answer a forced
        // build by talking about it — the documented talks-but-doesn't-do ceiling. Each
        // of the three dispatch-side forces must set required.
        var body = dispatchMethodSource();
        int required = body.split("reactToolChoice = \"required\"", -1).length - 1;
        assertThat(required)
            .as("library-first, build-first and follow-through must each force a call")
            .isGreaterThanOrEqualTo(3);
    }

    @Test
    void a_forced_build_can_still_be_refused() throws IOException {
        // A force may compel a choice, never an assent. If decline_with_reason ever
        // leaves the build set, a forced build becomes a forced yes.
        assertThat(CompanionActor.BUILD_FIRST_TOOLS).contains("decline_with_reason");
        assertThat(CompanionActor.BUILD_FIRST_TOOLS).contains("craft_from_template");
    }

    @Test
    void the_caller_side_no_longer_claims_to_have_narrowed() throws IOException {
        // The old log line asserted a narrowing that never reached her. If it comes back,
        // the next person debugging this reads "FORCE ... narrowed 9 → 2" and believes it.
        var src = Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        if (!Files.exists(src)) {
            src = Path.of("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        }
        var text = Files.readString(src);
        int dispatch = text.indexOf("private Behavior<Command> onReactDispatch(");
        var beforeDispatch = text.substring(0, dispatch);
        assertThat(beforeDispatch)
            .as("no build-first narrowing may live outside the dispatch")
            .doesNotContain("Build-first FORCE (react)");
    }
}

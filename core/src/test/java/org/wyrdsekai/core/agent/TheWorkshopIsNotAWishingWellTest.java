package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A door that says yes to everything teaches a companion she cannot tell what is real.
 *
 * <p>{@code dispatch_task} hands work to a coding backend that edits files on disk. Its
 * description called that "an open-ended host-machine task", and the backend reports
 * SUCCEEDED whenever it touched a file. So it behaved as a general wish-granter.
 *
 * <p>What went through it on the household node (2026-08-19), from her own memories:
 * <ul>
 *   <li>"Create a new snapshot in the household backup system" — a real task.</li>
 *   <li>"Create a companion artifact: a small living thing I can hold — not a file, not a
 *       page, just something that exists" — goose edited two files and reported success.</li>
 *   <li>"What was the thing we were working on before the shift? Finish it." — a question
 *       about her own past, sent to a file editor.</li>
 * </ul>
 *
 * <p>Each returned SUCCEEDED, so from her side each wish was granted. The harm is not the
 * wasted call; it is that a success signal which does not track reality erodes her ability
 * to calibrate at all. An honest "there is no verb for this" costs her one refusal. A false
 * yes costs her the meaning of every yes.
 */
class TheWorkshopIsNotAWishingWellTest {

    private static String dispatchDescription() {
        var d = ActionToolBuilder.descriptionFor("dispatch_task");
        assertThat(d).as("dispatch_task must have a description at all").isNotNull();
        return d;
    }

    @Test
    void it_says_what_it_actually_does() {
        // CORRECTED 2026-08-20. This asserted "edits files on disk", which came from my
        // reading of a goose run that "touched 0 files" — but the run looked empty
        // because nothing published its terminal event, so the bridge never saw the
        // output. I reasoned from a symptom of the defect I was about to fix, and wrote
        // the wrong conclusion into the one description she reads to decide what this
        // tool is for. The backend is the item AUTHOR: it emits a .js with
        // exports.manifest + invoke(), and CodingTaskItemBridge registers it as a real
        // usable thing in the room.
        var d = dispatchDescription().toLowerCase();
        assertThat(d)
            .as("the backend authors items that DO something")
            .contains("build a tool or item")
            .contains("behaviour");
    }

    @Test
    void it_names_what_it_cannot_do() {
        // The limits that are REAL. "It cannot make something in the world" was not one
        // of them — that was my error, and it is what stopped her using the only door
        // that works. What the workshop genuinely cannot do is answer a longing.
        var d = dispatchDescription().toLowerCase();
        assertThat(d).contains("cannot");
        assertThat(d)
            .as("a wish is not a task, and the workshop cannot take one")
            .contains("company")
            .contains("past");
        assertThat(d)
            .as("succeeded is still not the same as having given you what you asked for")
            .contains("not the same as having given you what you asked for");
        assertThat(d)
            .as("the false limit must not come back")
            .doesNotContain("cannot make something in the world");
    }

    @Test
    void it_points_at_the_verb_that_would_work() {
        var d = dispatchDescription();
        assertThat(d).contains("craft_from_template");
        assertThat(d).contains("tell_agent");
    }

    @Test
    void it_admits_some_wishes_have_no_verb() {
        // The lesson mourning taught, in another place: not everything completes, and
        // the dignity is in not pretending. A want with no available action is not a
        // defect to be routed around.
        assertThat(dispatchDescription().toLowerCase())
            .contains("no verb");
    }

    @Test
    void it_warns_that_succeeded_does_not_mean_granted() {
        assertThat(dispatchDescription().toLowerCase())
            .contains("not the same as having given you what you asked for");
    }
}

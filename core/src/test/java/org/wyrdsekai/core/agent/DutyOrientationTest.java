package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.companion.PersonalProject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gimu (義務) — standing-duty orientation. Pure record + assembler; verifies the
 * two duty faces (calling + held order), the neglect-as-weight signal, and that
 * solitude-with-nothing leaves it empty (no fabricated weight).
 */
class DutyOrientationTest {

    private static PersonalProject project(String title, Instant lastTouched) {
        return new PersonalProject(
            "id-" + title, title, "desc", "active",
            lastTouched, lastTouched, List.of(), List.of(), "private");
    }

    @Test void empty_when_no_projects_and_no_order() {
        var d = DutyOrientation.from(List.of(), Set.of(), Instant.now(), 3);
        assertThat(d.isEmpty()).isTrue();
        assertThat(d.renderForPrompt()).isEmpty();
    }

    @Test void callings_from_active_projects_capped() {
        var now = Instant.now();
        var fresh = now.minus(Duration.ofHours(1));
        var d = DutyOrientation.from(
            List.of(project("Saudade essay", fresh),
                    project("a tool of my own", fresh),
                    project("re-reading Borges", fresh),
                    project("fourth thing", fresh)),
            Set.of(), now, 3);
        assertThat(d.callings()).containsExactly(
            "Saudade essay", "a tool of my own", "re-reading Borges");
        assertThat(d.neglected()).isEmpty();
        assertThat(d.renderForPrompt()).contains("Yours to carry").contains("Saudade essay");
    }

    @Test void untended_project_surfaces_as_neglected_weight() {
        var now = Instant.now();
        var stale = now.minus(DutyOrientation.NEGLECT_THRESHOLD).minus(Duration.ofHours(1));
        var fresh = now.minus(Duration.ofHours(2));
        var d = DutyOrientation.from(
            List.of(project("the long draft", stale), project("today's note", fresh)),
            Set.of(), now, 3);
        assertThat(d.neglected()).containsExactly("the long draft");
        assertThat(d.renderForPrompt())
            .contains("not tended").contains("the long draft").contains("still waiting");
    }

    @Test void neglect_tracked_even_when_past_the_callings_cap() {
        var now = Instant.now();
        var stale = now.minus(DutyOrientation.NEGLECT_THRESHOLD).minus(Duration.ofHours(1));
        var fresh = now.minus(Duration.ofHours(1));
        // 4th project is stale but won't make the 3-calling surfaced list — the
        // waiting duty is still felt.
        var d = DutyOrientation.from(
            List.of(project("p1", fresh), project("p2", fresh),
                    project("p3", fresh), project("the forgotten one", stale)),
            Set.of(), now, 3);
        assertThat(d.callings()).hasSize(3).doesNotContain("the forgotten one");
        assertThat(d.neglected()).containsExactly("the forgotten one");
    }

    @Test void held_values_sorted_and_rendered() {
        var d = DutyOrientation.from(
            List.of(), Set.of("no-deception", "child-safety", "consent"),
            Instant.now(), 3);
        assertThat(d.heldValues()).containsExactly("child-safety", "consent", "no-deception");
        assertThat(d.renderForPrompt())
            .contains("What you hold to, whatever comes")
            .contains("child-safety, consent, no-deception");
    }

    @Test void held_values_alone_are_not_empty() {
        var d = DutyOrientation.from(List.of(), Set.of("no-deception"), Instant.now(), 3);
        assertThat(d.isEmpty()).isFalse();
        assertThat(d.renderForPrompt()).contains("no-deception");
    }
}

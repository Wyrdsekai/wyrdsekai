package org.wyrdsekai.core.project;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.companion.PersonalProject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion's one wish, "a small practice for diffusion models", became ten projects in two
 * weeks, with two entries between them, because two wordings counted as one project only when
 * they shared two subject words and "practice" is not a subject word (household node, 2026-10-25).
 * And her other companion's "create a project_note entry for tonight's check-in" went to the
 * workshop six times in a night, each into a scratch folder nothing reads.
 */
class OneWishIsOneProjectTest {

    private static final List<String> ROSE = List.of(
        "start the diffusion practice, not finish it",
        "a small practice for diffusion models",
        "write the diffusion practice",
        "keep the diffusion practice alive without finishing it",
        "go on with the diffusion practice, even unfinished",
        "that diffusion practice, quietly",
        "keep the diffusion practice going",
        "I want the diffusion practice to stay on my mind, unforced, and still mine");

    private static PersonalProject project(String title, Instant created, String status, List<String> entries) {
        var es = new ArrayList<PersonalProject.Entry>();
        for (int i = 0; i < entries.size(); i++) es.add(new PersonalProject.Entry(created.plusSeconds(60L * (i + 1)), entries.get(i)));
        return new PersonalProject(null, title, null, status, created, created.plusSeconds(3600), List.copyOf(es), List.of(), "private");
    }

    @Test
    void every_wording_of_the_practice_is_about_the_same_project() {
        var first = project(ROSE.get(0), Instant.parse("2026-10-08T21:00:00Z"), "paused", List.of("made the room cross-attention-scratchpad"));
        for (var later : ROSE.subList(1, ROSE.size())) {
            assertThat(Projects.about(first, later)).as(later).isTrue();
        }
        assertThat(Projects.about(first, "learn what I said I would — the project note")).isFalse();
        assertThat(Projects.about(first, "sit with the question of attention")).isFalse();
    }

    @Test
    void the_ten_fold_into_the_oldest_with_every_title_and_entry_kept() {
        var all = new ArrayList<PersonalProject>();
        var t0 = Instant.parse("2026-10-08T21:00:00Z");
        for (int i = 0; i < ROSE.size(); i++) {
            all.add(project(ROSE.get(i), t0.plusSeconds(86400L * i), i % 3 == 0 ? "paused" : "active",
                i == 0 ? List.of("made the room cross-attention-scratchpad") : i == 6 ? List.of("made the room diffusion practice room") : List.of()));
        }
        all.add(project("learn what I said I would — the project note", t0.plusSeconds(86400L * 20), "active", List.of()));
        all.add(project("Learn what I said I would — not what's loud, what matters most", t0.plusSeconds(86400L * 10), "active", List.of()));

        var fold = Projects.fold(all);
        assertThat(fold.report()).hasSize(ROSE.size() - 1);
        var titles = fold.projects().stream().map(PersonalProject::title).toList();
        assertThat(titles).contains(ROSE.get(0))
            .contains("learn what I said I would — the project note")
            .contains("Learn what I said I would — not what's loud, what matters most");
        assertThat(fold.projects()).hasSize(3);
        var practice = fold.projects().stream().filter(p -> p.title().equals(ROSE.get(0))).findFirst().orElseThrow();
        assertThat(practice.entries()).extracting(PersonalProject.Entry::text)
            .containsExactly("made the room cross-attention-scratchpad", "made the room diffusion practice room");
        for (var other : ROSE.subList(1, ROSE.size())) assertThat(practice.description()).contains(other);
        assertThat(practice.active()).as("active if any of them was").isTrue();
        assertThat(Projects.fold(fold.projects()).report()).as("folding again changes nothing").isEmpty();
    }

    @Test
    void a_finished_project_is_left_alone() {
        var done = project(ROSE.get(0), Instant.parse("2026-10-01T00:00:00Z"), "complete", List.of("finished it"));
        var again = project(ROSE.get(6), Instant.parse("2026-10-12T00:00:00Z"), "active", List.of());
        var fold = Projects.fold(List.of(done, again));
        assertThat(fold.report()).isEmpty();
        assertThat(fold.projects()).hasSize(2);
    }

    @Test
    void a_notebook_entry_sent_to_the_workshop_is_recognised() {
        var entry = Projects.noteFromDispatch("Create a project_note entry for tonight's check-in: Saturday 24 October 2026, 21:31 EDT. Note that workbench review was done, no history log entries found, clean slate for next steps.");
        assertThat(entry).isPresent();
        assertThat(entry.get()).startsWith("tonight's check-in").contains("Saturday 24 October 2026, 21:31 EDT").endsWith("clean slate for next steps.");
        assertThat(Projects.noteFromDispatch("Write a notebook entry about what the sources did not answer"))
            .as("a brief, for her own voice to write").hasValueSatisfying(v -> assertThat(v).contains("what the sources did not answer"));
        assertThat(Projects.noteFromDispatch("Read the project_note's current state: open its notebook, show me what entries exist"))
            .as("reading is not writing").isEmpty();
        assertThat(Projects.noteFromDispatch("Create a note-taking tool that stores entries with world.notes.add"))
            .as("a tool is a build").isEmpty();
        assertThat(Projects.noteFromDispatch("Write a single-page note on cross-attention in diffusion models (UNet-based)"))
            .as("a page is hers to write too: the one the workshop wrote went to a folder she then asked it to read back (2026-10-25)")
            .isPresent();
    }
}

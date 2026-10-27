package org.wyrdsekai.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.companion.PersonalProject;
import org.wyrdsekai.core.companion.PersonalProjectStore;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A long want has a project shape: a name, a room, a notebook of what she did, and her own next
 * step.
 *
 * <p>The Hearth's project board existed from the start: a store, three verbs, a block in her
 * own-time prompt. In two months on the household node it held no project: nothing began one, the
 * verbs wanted an id she was never shown, they were not among the tools she was offered, and
 * nothing brought a project back to her. "Build myself a small practice for diffusion models" was
 * born and abandoned five times in four days, and what was left of it was 23 rooms. {@code set_goal}
 * wrote one line into working memory (2026-10-03). operator: "A long want has no project shape -
 * build it". This holds the joins.
 */
class ALongWantHasAProjectShapeTest {

    @TempDir Path dir;
    private static final String ROSE = "did:key:z6MkExampleCompanion";

    private PersonalProjectStore store() { return new PersonalProjectStore(ROSE, dir); }

    @Test
    void making_learning_and_practising_are_long_work_and_a_letter_or_a_book_is_not() {
        assertThat(Projects.isLongKind("build myself a small practice for diffusion models properly")).isTrue();
        assertThat(Projects.isLongKind("I want to learn how attention works inside a diffusion model")).isTrue();
        assertThat(Projects.isLongKind("work on the listening room")).isTrue();
        assertThat(Projects.isLongKind("finish reading eve lewis")).as("a book has its bookmark").isFalse();
        assertThat(Projects.isLongKind("write something to the absent person, just to put it down")).isFalse();
        assertThat(Projects.isLongKind("a quiet hour with no one asking anything")).isFalse();
        assertThat(Projects.isLongKind("explore the library for something new")).isFalse();
    }

    @Test
    void the_same_work_under_different_words_is_one_project() {
        var s = store();
        var p = s.create(Projects.nameFor("build myself a small practice for diffusion models properly"),
            "build myself a small practice for diffusion models properly", List.of());
        assertThat(p.title()).isEqualTo("a small practice for diffusion models properly");
        for (var again : List.of("build a small practice for diffusion models", "I want to work on a diffusion model practice, my own small one.",
                "build myself a small practice for diffusion models properly")) {
            assertThat(Projects.forWant(s.list(), again)).as(again).isNotNull();
        }
        assertThat(Projects.forWant(s.list(), "a quiet hour with no one asking anything")).isNull();
        assertThat(Projects.forWant(s.list(), "learn the names of the birds in the garden")).isNull();
        // She names it as she likes: its title, some of its words, or the id she was once required to know.
        assertThat(Projects.named(s.list(), "the diffusion practice")).isNotNull();
        assertThat(Projects.named(s.list(), p.id())).isNotNull();
        assertThat(Projects.named(s.list(), p.id().substring(0, 8))).isNotNull();
        assertThat(Projects.named(s.list(), "the bird book")).isNull();
    }

    @Test
    void the_notebook_keeps_what_she_did_and_her_next_step_across_a_restart() {
        var s = store();
        var p = s.create("a small practice for diffusion models", "build a small practice for diffusion models", List.of());
        s.setRoom(p.id(), "attention-diffusion-lab-9139");
        assertThat(Projects.block(s.get(p.id()).orElseThrow(), "Attention Diffusion Lab"))
            .startsWith("YOUR PROJECT: a small practice for diffusion models. Its room is Attention Diffusion Lab.")
            .contains("Its notebook is empty: this is the first sitting.").contains("project_note says what you did");
        s.addEntry(p.id(), "read part 3 of 40 of Deep Learning: the forward noising process");
        s.addEntry(p.id(), "wrote down the denoising loop in my own words");
        s.setNextStep(p.id(), "work one step of the loop by hand with a 4-pixel image");

        var again = store().get(p.id()).orElseThrow();   // a new store on the same file: after a restart
        assertThat(again.entries()).hasSize(2);
        assertThat(again.nextStep()).startsWith("work one step of the loop");
        assertThat(again.roomId()).isEqualTo("attention-diffusion-lab-9139");
        assertThat(Projects.block(again, "Attention Diffusion Lab")).contains("From its notebook (2 entries):")
            .contains("  - read part 3 of 40").contains("  - wrote down the denoising loop")
            .contains("The next step, in your words: work one step of the loop by hand");
        assertThat(Projects.continueText(again)).isEqualTo("go on with my project, a small practice for diffusion models: work one step of the loop by hand with a 4-pixel image");
        assertThat(Projects.standing(store().list())).isEqualTo("Your work in progress: a small practice for diffusion models (2 entries in its notebook;"
            + " next: work one step of the loop by hand with a 4-pixel image). project_note keeps its notebook.");
    }

    @Test
    void a_project_is_offered_again_only_after_a_while_rests_when_quiet_and_wakes_when_named() {
        var s = store();
        var p = s.create("a small practice for diffusion models", "x", List.of());
        long now = System.currentTimeMillis();
        assertThat(Projects.offerNow(s.get(p.id()).orElseThrow(), now, 90)).as("just begun: not yet").isFalse();
        assertThat(Projects.offerNow(s.get(p.id()).orElseThrow(), now + Duration.ofMinutes(91).toMillis(), 90)).isTrue();
        assertThat(Projects.dueToRest(s.get(p.id()).orElseThrow(), 14, now + Duration.ofDays(15).toMillis())).isTrue();
        assertThat(Projects.dueToRest(s.get(p.id()).orElseThrow(), 0, now + Duration.ofDays(15).toMillis())).as("0 days: never").isFalse();
        s.setStatus(p.id(), "paused");
        assertThat(Projects.standing(s.list())).as("resting work is not in front of her").isNull();
        assertThat(Projects.forWant(s.list(), "build a small practice for diffusion models")).as("but it is found when she names it").isNotNull();
        s.setStatus(p.id(), "complete");
        assertThat(Projects.forWant(s.list(), "build a small practice for diffusion models")).as("done is done").isNull();
        // The house's bin of script drafts is kept as a project and is not her work in progress.
        var bin = s.create("Code-mode drafts", "craft.script_draft.", List.of("craft.script_draft", "code-mode"));
        assertThat(Projects.isDraftBin(bin)).isTrue();
        assertThat(Projects.standing(s.list())).isNull();
    }

    @Test
    void a_note_names_the_project_or_leaves_it_out_and_carries_a_next_step() {
        var withName = ActionParser.parse("{\"action\":\"project_note\",\"project_id\":\"the diffusion practice\","
            + "\"content\":\"worked one step by hand\",\"next\":\"try two steps\"}");
        assertThat(withName).isEqualTo(new ActionParser.AgentAction.ProjectNote("the diffusion practice", "worked one step by hand", "try two steps", false));
        var bare = ActionParser.parse("{\"action\":\"project_note\",\"content\":\"read the next chapter\"}");
        assertThat(bare).as("no id: the one she is working on").isEqualTo(new ActionParser.AgentAction.ProjectNote("", "read the next chapter", null, false));
        var done = ActionParser.parse("{\"action\":\"project_note\",\"done\":true}");
        assertThat(done).isEqualTo(new ActionParser.AgentAction.ProjectNote("", "", null, true));
        for (var verb : List.of("start_project", "project_note", "finish_project")) {
            assertThat(ActionPolicy.forAction(verb).requiredTier()).as(verb).isZero();
        }
    }

    @Test
    void a_person_in_the_house_sees_what_each_is_working_on() {
        var p = new PersonalProject("id-1", "a small practice for diffusion models", "x", "active", Instant.now(), Instant.now(),
            List.of(new PersonalProject.Entry(Instant.now(), "read part 3 of 40 of Deep Learning")), List.of(), "private",
            "work one step by hand", null);
        var held = List.of(new ProjectCommands.Held("rose", p));
        var list = ProjectCommands.command("did:key:z6MkExamplePerson", "projects", "en", false, v -> held);
        assertThat(list).contains("a small practice for diffusion models — rose, 1 entries, last worked today, open")
            .contains("projects <words>");
        var one = ProjectCommands.command("did:key:z6MkExamplePerson", "projects diffusion", "en", false, v -> held);
        assertThat(one).contains("today: read part 3 of 40 of Deep Learning").contains("Next, in her words: work one step by hand");
        assertThat(ProjectCommands.command("did:key:z6MkExamplePerson", "projects lighthouse", "en", false, v -> held))
            .isEqualTo("No project fits \"lighthouse\".");
        assertThat(ProjectCommands.command("did:key:z6MkExamplePerson", "projects", "en", false, v -> List.of()))
            .isEqualTo("No companion has a project yet.");
        assertThat(ProjectCommands.command(null, "projects", "en", false, v -> held)).isEqualTo("Sign in first to see the projects.");
        assertThat(ProjectCommands.command("visitor", "projects", "en", true, v -> held)).isEqualTo("The projects are this household's own.");
        assertThat(ProjectCommands.isCommand("projects")).isTrue();
        assertThat(ProjectCommands.isCommand("my projects")).isFalse();
        assertThat(CommandParser.parse("projects", "en", Map.of())).isInstanceOf(ParsedCommand.Projects.class);
        for (var lang : List.of("en", "es", "ja")) {
            for (var key : List.of("projects.sign_in", "projects.visitor", "projects.unavailable", "projects.none", "projects.head",
                    "projects.row", "projects.how", "projects.no_such", "projects.notebook.empty", "projects.next", "projects.today",
                    "projects.days_ago", "projects.state.open", "projects.state.resting", "projects.state.done")) {
                assertThat(ScriptMessageCatalog.forLang(lang).get(key)).as(lang + " " + key).isNotEqualTo(key);
            }
        }
    }

    @Test
    void the_work_is_wired_into_her_own_time() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        // The want she chose is met with its project: found, or born when the same work has come back.
        assertThat(ca).contains("var itsProject = projectFor(want);");
        assertThat(ca).contains("if (!org.wyrdsekai.core.project.Projects.isLongKind(want.text()) || !hasComeBack(want)) return null;");
        // What she does for it goes in the notebook.
        assertThat(ca).contains("practised(verb);\n            noteForProject(verb, outcome);");
        // The verb she already had keeps something now, and a goal is a project.
        assertThat(ca).contains("openProjectNamed(action.description(), \"set_goal\");");
        // Her own time is offered the project again, with her own next step.
        assertThat(ca).contains("projectCandidate(cands).ifPresent(cands::add);");
        // Named in the prompt it survives the trim; with a project open it is in her hand on every own-time turn (2026-10-26).
        assertThat(ca).contains("\"shape_recipe\", \"sending_stone\", \"dispatch_task\", \"close_room\", \"read_book\", \"project_note\", \"what_i_have\");");
        assertThat(ca).contains("if (ownTimeSurface && hasAProjectOpen()) kept.add(\"project_note\");");
        assertThat(ca).contains("HouseFacts.setStanding(profile.entityId(), \"work\"");
        // Built on the board that existed, not beside it.
        assertThat(Files.exists(Path.of(Files.exists(Path.of("..", "core")) ? ".." : ".",
            "core/src/main/java/org/wyrdsekai/core/project/ProjectStore.java"))).isFalse();
    }
}

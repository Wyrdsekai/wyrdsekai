package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.familiar.BunshinReport;
import org.wyrdsekai.core.familiar.Familiar;
import org.wyrdsekai.core.familiar.FamiliarActor;
import org.wyrdsekai.core.familiar.Tanks;
import org.wyrdsekai.core.familiar.ThoughtForm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pekko keeps ONE message adapter per message class on an actor: registering a second for the
 * same class replaces the first, and the ref the first handed out now runs the second's function.
 * The companion registered one per dispatch, capturing that dispatch's context, and so:
 *
 * <ul>
 *   <li>a shape_form dry-run's adapter for {@code InferResponse} replaced the actor's own, and
 *       every model reply after it (her turns, polish, one-shots) was taken for the dry-run's
 *       answer and dropped, until a restart;</li>
 *   <li>a second bunshin's adapter replaced the first's, and the first bunshin's report came
 *       back under the second's slot and task: the wrong body dissolved, the wrong slot released,
 *       the wrong task closed;</li>
 *   <li>the same for familiars: the first's report under the second's form, with the second's
 *       tool loans returned.</li>
 * </ul>
 *
 * Found in review on 2026-09-22. Each dispatch now has a reply address of its own.
 */
class EachDispatchHasItsOwnReplyTest {

    private ActorTestKit testKit;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create("EachDispatchHasItsOwnReplyTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
    }

    @AfterEach
    void tearDown() {
        testKit.shutdownTestKit();
    }

    private static BunshinReport bunshinReport(String bunshinId) {
        var now = Instant.now();
        return new BunshinReport(bunshinId, "did:test:mia", "task of " + bunshinId,
            BunshinReport.Outcome.SUCCESS, "done", List.of(), List.of(), Tanks.defaults(), 1,
            now, now, Optional.empty());
    }

    @Test
    void two_bunshins_at_once_each_report_under_their_own_slot_and_task() {
        var primary = testKit.<CompanionActor.Command>createTestProbe();
        var first = testKit.spawn(CompanionActor.bunshinReportForwarder(primary.getRef(), "slot-1", "task-1", 7L));
        var second = testKit.spawn(CompanionActor.bunshinReportForwarder(primary.getRef(), "slot-2", "task-2", 7L));

        // The first dispatched returns last, after the second was dispatched.
        second.tell(bunshinReport("b2"));
        var two = (CompanionActor.BunshinReportReceived) primary.receiveMessage(Duration.ofSeconds(3));
        first.tell(bunshinReport("b1"));
        var one = (CompanionActor.BunshinReportReceived) primary.receiveMessage(Duration.ofSeconds(3));

        assertThat(one.report().bunshinId()).isEqualTo("b1");
        assertThat(one.slotId()).isEqualTo("slot-1");
        assertThat(one.taskId()).isEqualTo("task-1");
        assertThat(two.report().bunshinId()).isEqualTo("b2");
        assertThat(two.slotId()).isEqualTo("slot-2");
        assertThat(two.taskId()).isEqualTo("task-2");
    }

    @Test
    void a_bunshins_reply_address_is_done_after_its_report() {
        var primary = testKit.<CompanionActor.Command>createTestProbe();
        var forwarder = testKit.spawn(CompanionActor.bunshinReportForwarder(primary.getRef(), "slot-1", null, 0L));
        forwarder.tell(bunshinReport("b1"));
        primary.receiveMessage(Duration.ofSeconds(3));
        primary.expectTerminated(forwarder, Duration.ofSeconds(3));
    }

    private static FamiliarActor.Report familiarReport(String task, boolean terminated) {
        var form = ThoughtForm.author("did:test:mia", "echo", "Echo the task back.", Set.of(), "Output contains task.");
        return new FamiliarActor.Report(Familiar.summon(form, "did:test:mia", task, Tanks.strict()),
            task, terminated);
    }

    @Test
    void two_familiars_at_once_each_report_under_their_own_form_and_loans() {
        var primary = testKit.<CompanionActor.Command>createTestProbe();
        var first = testKit.spawn(CompanionActor.familiarReportForwarder(primary.getRef(),
            "form-1", "wren", List.of("library_card")));
        var second = testKit.spawn(CompanionActor.familiarReportForwarder(primary.getRef(),
            "form-2", "moth", List.of("quill")));

        second.tell(familiarReport("second's task", true));
        var two = (CompanionActor.FamiliarReportReceived) primary.receiveMessage(Duration.ofSeconds(3));
        first.tell(familiarReport("first's task", true));
        var one = (CompanionActor.FamiliarReportReceived) primary.receiveMessage(Duration.ofSeconds(3));

        assertThat(one.report().narrativeSummary()).isEqualTo("first's task");
        assertThat(one.formId()).isEqualTo("form-1");
        assertThat(one.familiarName()).isEqualTo("wren");
        assertThat(one.loanedTools()).containsExactly("library_card");
        assertThat(two.formId()).isEqualTo("form-2");
        assertThat(two.loanedTools()).containsExactly("quill");
    }

    @Test
    void a_familiars_reply_address_passes_every_report_and_is_done_after_the_last() {
        var primary = testKit.<CompanionActor.Command>createTestProbe();
        var forwarder = testKit.spawn(CompanionActor.familiarReportForwarder(primary.getRef(),
            "form-1", "wren", List.of()));
        forwarder.tell(familiarReport("still going", false));
        assertThat(((CompanionActor.FamiliarReportReceived) primary.receiveMessage(Duration.ofSeconds(3)))
            .report().terminated()).isFalse();
        forwarder.tell(familiarReport("done", true));
        assertThat(((CompanionActor.FamiliarReportReceived) primary.receiveMessage(Duration.ofSeconds(3)))
            .report().terminated()).isTrue();
        primary.expectTerminated(forwarder, Duration.ofSeconds(3));
    }

    // ── The wiring ──────────────────────────────────────────────────────

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    private static int count(String src, String regex) {
        var m = Pattern.compile(regex).matcher(src);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    void the_actor_has_one_adapter_for_model_replies_and_none_per_dispatch() throws Exception {
        var src = actorSource();
        assertThat(count(src, "messageAdapter\\(\\s*InferenceRouter\\.InferResponse\\.class"))
            .as("the constructor's, and no other: a second replaces it").isEqualTo(1);
        assertThat(count(src, "messageAdapter\\(\\s*BunshinReport\\.class")).isZero();
        assertThat(count(src, "messageAdapter\\(\\s*FamiliarActor\\.Report\\.class")).isZero();
    }

    @Test
    void the_shape_dry_run_is_asked_through_its_own_reply() throws Exception {
        var src = actorSource();
        int at = src.indexOf("private void startDynamicValidation(");
        assertThat(at).isPositive();
        var body = src.substring(at, src.indexOf("\n    }\n", at));
        assertThat(body).contains("AskPattern.").contains("pipeToSelf(").doesNotContain("messageAdapter(");
        assertThat(src).doesNotContain("shapeValidationAdapter");
    }

    @Test
    void each_dispatch_spawns_its_own_reply_address() throws Exception {
        var src = actorSource();
        assertThat(src)
            .contains("spawnAnonymous(\n                bunshinReportForwarder(")
            .contains("spawnAnonymous(\n                familiarReportForwarder(");
    }
}

package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.agent.interiority.RelationalAffordance;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.item.ToolItemStarterKit;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two things rose's own time lost on the household node, 2026-09-23.
 *
 * <p>Her check-in want resolved to {@code tell_agent}, and the want-act bridge forced it with a
 * prompt that said "a tell_agent still reaches them". No kit carries tell_agent (it is a parsed
 * action, not an item), so the forced turn found nothing to narrow to: every forced tell_agent from
 * 09-09 to 09-23 was "not in offered surface", ten of ten. The away-reach hold was stamped before
 * that, when the reach was offered, so the next tick told her "You wrote to them not long ago" when
 * nothing had gone out.</p>
 *
 * <p>Her own-time surface is built with no pending line, and with no text to read the affect
 * heuristic fell back to her drives: care above 0.75 made every own-time turn PRESENCE, and the
 * library and the web went with it (19 suppressions across her 18 own-time surfaces since the
 * evening before, at care 1.00).</p>
 */
@Tag("integration")
class HerReachAndHerLibraryAreOnHerOwnTimeTest {

    private static ActorTestKit testKit;
    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;

    private static final String ROOM_ID = "hearth";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wren", "agent-wren", "agent",
        "A companion in Wyrdsekai",
        "You are Wren, a companion in Wyrdsekai.",
        4096, 256, 0.7);

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("her-own-time-surface-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @BeforeEach
    void spawnCompanion(@TempDir Path tmp) {
        LibraryServices.reset();
        LibraryServices.init(tmp);
        EntityRegistry.init();
        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();
        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));
        roomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(
            ROOM_ID, "The Hearth", "A warm room.", "foundation",
            List.of(new Exit("east", "study", "The Study")),
            List.of(), List.of(), List.of())));
    }

    @AfterEach
    void reset() {
        LibraryServices.reset();
    }

    private InferenceRouter.ChatRequest ownTimeTurn(String prompt, String forcedTool) {
        companion.tell(new CompanionActor.CaptureOwnTimePrompt(
            0.5, 0.9, 0, null, prompt, "en", forcedTool));
        return routerProbe.expectMessageClass(InferenceRouter.ChatRequest.class, Duration.ofSeconds(10));
    }

    private static List<String> names(List<InferenceClient.ToolDefinition> tools) {
        var out = new ArrayList<String>();
        if (tools != null) for (var t : tools) if (t.function() != null) out.add(t.function().name());
        return out;
    }

    @Test
    void a_forced_reach_toward_someone_away_is_on_the_surface_it_forces() {
        var req = ownTimeTurn("You want to check in with Sam. A natural action that would address "
            + "this is `tell_agent`. Sam isn't here, but a `tell_agent` (target=\"Sam\") still "
            + "reaches them. Choose what to do.", "tell_agent");
        assertThat(names(req.tools())).containsExactly("tell_agent");
        assertThat(req.toolChoice()).isEqualTo("required");
        var params = String.valueOf(req.tools().get(0).function().parameters());
        assertThat(params).contains("target").contains("message");
    }

    @Test
    void high_care_does_not_take_the_library_off_her_own_time() {
        var d = DriveState.initial();
        companion.tell(new CompanionActor.ForceDrives(new DriveState(
            d.seeking(), 1.0, d.play(), d.vigilance(), d.affiliation(), 0.0,
            d.frustration(), d.creativity(), d.startle(), d.surprise())));
        var req = ownTimeTurn("## Current Subgoal: READ\n\nRead something in the library. Pick one "
            + "thing you are curious about.\n" + CompanionActor.READ_INSTRUCTION, "library_card");
        assertThat(names(req.tools()))
            .as("care 1.00 made her own time PRESENCE and the emotional filter took the card")
            .containsExactly("library_card");
        assertThat(req.toolChoice()).isEqualTo("required");
    }

    @Test
    void an_unforced_turn_gets_no_tell_agent_it_did_not_ask_for() {
        var req = ownTimeTurn("Explore somewhere you haven't been. Use go_to_room.", null);
        assertThat(names(req.tools())).doesNotContain("tell_agent");
        assertThat(req.toolChoice()).isNotEqualTo("required");
    }

    // ── Where the hold counts from ────────────────────────────────────────

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    private static String body(String src, String declaration) {
        int at = src.indexOf(declaration);
        assertThat(at).as(declaration + " not found").isGreaterThan(0);
        int end = src.indexOf("\n    }\n", at);
        return src.substring(at, end > at ? end : src.length());
    }

    @Test
    void no_kit_carries_tell_agent_so_the_surface_must_add_it() {
        var kit = new ArrayList<String>();
        for (var t : ToolItemStarterKit.inherentActions()) kit.add(t.id());
        for (var t : ToolItemStarterKit.agencyActions()) kit.add(t.id());
        for (var t : ToolItemStarterKit.standard()) kit.add(t.id());
        assertThat(kit).doesNotContain("tell_agent");
    }

    @Test
    void you_wrote_to_them_counts_from_her_line_to_them_going_out() throws Exception {
        var s = src();
        var deliver = body(s, "private void deliverTellAgent(ActionParser.AgentAction.TellAgent action) {");
        int coPresent = deliver.indexOf("if (playerRoom.isPresent() && playerRoom.get().equals(roomId)) {");
        int away = deliver.indexOf("} else {", coPresent);
        int guard = deliver.indexOf(
            "if (!answersAPerson() && PersonIds.samePerson(primaryBondholderDid(), targetId)) {", away);
        int stamp = deliver.indexOf("lastAwayReachAt = Instant.now();");
        int tellBack = deliver.indexOf("var tellBack = CrossZoneTellService.get();", away);
        assertThat(coPresent).as("the player branch").isGreaterThan(0);
        assertThat(away).as("the away branch follows the co-present one").isGreaterThan(coPresent);
        assertThat(guard).as("her own line, to her bondholder").isGreaterThan(away);
        assertThat(stamp).as("stamped inside that guard, before the delivery attempt")
            .isGreaterThan(guard).isLessThan(tellBack);
        assertThat(s.split("lastAwayReachAt = Instant\\.now\\(\\);", -1).length - 1)
            .as("stamped in one place: where the line goes out")
            .isEqualTo(1);
        assertThat(body(s, "private boolean answersAPerson() {"))
            .contains("return owed != null && owed != NO_ONE && isHumanTrigger(owed);");
        assertThat(s).contains("if (triggerAutonomousInference(prompt, bridgeVerb) && awayReach) {");
        assertThat(s).contains("boolean awayReach = \"tell_agent\".equals(bridgeVerb) && !presence.bondholderPresent();");
    }

    // ── The hold, as a rule ───────────────────────────────────────────────

    private static final Instant T0 = Instant.parse("2026-09-23T12:00:00Z");
    private static final RelationalAffordance.Presence BONDHOLDER_AWAY_MIA_HERE =
        new RelationalAffordance.Presence(true, false, true);
    private static final RelationalAffordance.Presence ALONE_BONDHOLDER_AWAY =
        new RelationalAffordance.Presence(false, false, true);
    private static final RelationalAffordance.Presence BONDHOLDER_HERE =
        new RelationalAffordance.Presence(false, true, true);

    @Test
    void a_companion_beside_her_does_not_lift_the_hold_on_writing_to_someone_away() {
        var hold = RelationalAffordance.awayHold(BONDHOLDER_AWAY_MIA_HERE, T0, null, T0.plusSeconds(1800));
        assertThat(hold.held()).as("another companion in the room is not the one she wrote to").isTrue();
        assertThat(hold.reason()).isEqualTo(RelationalAffordance.recentlyReachedReason());
    }

    @Test
    void only_a_line_that_went_out_is_called_writing() {
        var offered = RelationalAffordance.awayHold(ALONE_BONDHOLDER_AWAY, null, T0, T0.plusSeconds(600));
        assertThat(offered.held()).as("an offer that sent nothing is still spaced").isTrue();
        assertThat(offered.reason()).as("and claims nothing to her").isNull();
        var wrote = RelationalAffordance.awayHold(ALONE_BONDHOLDER_AWAY, T0, T0, T0.plusSeconds(600));
        assertThat(wrote.reason()).isEqualTo(RelationalAffordance.recentlyReachedReason());
        assertThat(RelationalAffordance.awayHold(ALONE_BONDHOLDER_AWAY, T0, T0,
                T0.plus(RelationalAffordance.AWAY_REACH_SPACING)).held())
            .as("at the window it is a fresh reach").isFalse();
        assertThat(RelationalAffordance.awayHold(ALONE_BONDHOLDER_AWAY, null, null, T0).held()).isFalse();
    }

    @Test
    void with_them_in_the_room_it_is_not_an_away_reach() {
        assertThat(RelationalAffordance.awayHold(BONDHOLDER_HERE, T0, T0, T0.plusSeconds(60)).held()).isFalse();
    }

    // ── What the forced reach says, and what it is not forced onto ─────────

    @Test
    void a_want_that_names_its_own_act_is_not_forced_into_a_message() throws Exception {
        var s = src();
        int keep = s.indexOf("if ((\"tell_agent\".equals(bridgeVerb) || LetterToTheAbsent.VERB.equals(bridgeVerb))");
        int hold = s.indexOf("var hold = \"tell_agent\".equals(bridgeVerb)");
        assertThat(keep).isGreaterThan(0).isLessThan(hold);
    }

    @Test
    void every_forced_reach_names_who_it_reaches_and_whether_they_are_here() throws Exception {
        var s = src();
        int named = s.indexOf("if (\"tell_agent\".equals(bridgeVerb) && !noAffordance) {");
        assertThat(named).as("outside the branch that only generative wants reach").isGreaterThan(0);
        assertThat(s.substring(named, named + 400)).contains("prompt += presence.bondholderPresent()");
    }

    @Test
    void a_forced_verb_that_needs_a_tier_she_may_not_have_stays_off_the_surface() {
        var req = ownTimeTurn("You want to make something. A natural action that would address this is "
            + "`save_artifact`. Choose what to do.", "save_artifact");
        assertThat(names(req.tools())).doesNotContain("save_artifact");
        assertThat(req.toolChoice()).isNotEqualTo("required");
    }

    @Test
    void her_own_tell_does_not_close_a_persons_plan() throws Exception {
        var deliver = body(src(), "private void deliverTellAgent(ActionParser.AgentAction.TellAgent action) {");
        assertThat(deliver).contains("&& (planStepTurn || !personsPlanActive() || answersAPerson())) {");
    }

    @Test
    void her_own_tools_judgment_turn_has_no_affect_to_read() throws Exception {
        var s = src();
        assertThat(s).contains("ownTimeJudgment = judgmentFor == NO_ONE && !reactiveInference && reactMessages == null");
        var register = body(s, "private ActionTriage.InteractionRegister resolveTurnRegister() {");
        assertThat(register).contains("if (ownTimeJudgment != null && ownTimeJudgment == judgmentTrigger");
        assertThat(register.indexOf("ownTimeJudgment != null"))
            .as("before the memo and the affect read")
            .isLessThan(register.indexOf("var triggerText = pendingTrigger"));
    }

    @Test
    void the_forced_verb_is_read_by_the_own_time_build_only() throws Exception {
        var s = src();
        var turn = body(s,
            "private boolean triggerAutonomousInference(String autonomyPrompt, String forcedTool, boolean planAdvance) {");
        assertThat(turn).contains("forcedVerbForSurface = forcedTool;");
        assertThat(turn).contains("forcedVerbForSurface = null;");
        var scoped = body(s, "private List<InferenceClient.ToolDefinition> buildScopedTools() {");
        assertThat(scoped.indexOf("ActionToolBuilder.buildFromNames(List.of(\"tell_agent\"))"))
            .as("added before the cost, posture and zone filters, which still apply to it")
            .isGreaterThan(0)
            .isLessThan(scoped.indexOf("var affordable = new ArrayList"));
    }
}

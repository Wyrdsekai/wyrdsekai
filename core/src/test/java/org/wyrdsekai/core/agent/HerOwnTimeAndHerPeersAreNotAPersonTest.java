package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Her own time is not a person's turn, and another companion is not a person.
 *
 * <h2>What went wrong</h2>
 * Household node, 2026-09-22. {@code isHumanDirectedReply} asked only whether the trigger's sender
 * was an agent standing in this room. The own-time prompt is a Said from {@code "system"}, which
 * is not, so every own-time turn counted as a person's: the BOUNDED posture's cloud verbs were
 * "restored by bondholder request" on nearly every own-time turn with nobody connected, and a
 * hush did not hold on her own time. The room hush used the same room-only test, and rose's
 * "…the quiet math behind the voice" set mia to SOFT, logged as a present-human request.
 *
 * <p>The check also read {@code lastReactTrigger}, which is still the previous turn while a new
 * turn's tools are built and all through a ReAct loop until its first built-in action. Counting
 * "system" as a person had been covering for that: a person's first step after her own time, and
 * a tell answered in the loop's first words, passed only because the turn before was hers.</p>
 */
class HerOwnTimeAndHerPeersAreNotAPersonTest {

    private static final String SELF = "companion-mia";

    /** {@code CompanionActor.isHumanTrigger}'s rule for a room with no co-present peer
     *  (checked against the source below). */
    private static final Predicate<WorldEvent.Said> SPOKEN_BY_PERSON = said -> {
        if (said == null || said.entityId() == null) return false;
        var id = said.entityId();
        if (id.equals(SELF)) return false;
        return !(id.startsWith("agent-") || id.startsWith("companion-") || id.equals("system"));
    };

    private static WorldEvent.Said said(String id, String name, String text) {
        return new WorldEvent.Said("nexus", Instant.parse("2026-09-22T17:00:00Z"), id, name, text);
    }

    private static final WorldEvent.Said OWN_TIME =
        said("system", "system", "(own time) A seeking pull moves in you right now.");
    private static final WorldEvent.Said GREETING =
        said("system", "operator", "[operator enters the room]");
    private static final WorldEvent.Said PERSON = said("did:key:z6MkPersonExample", "operator",
        "[message from operator: find me research on attention layers]");
    private static final WorldEvent.Said PEER = said("companion-rose", "rose",
        "yes, i'm wired for it. attention layers, the quiet math behind the voice");
    private static final WorldEvent.Said HAND_BACK = said(SELF, "mia",
        "[Tool completed] three results\n[Share the substance with the user in your own words — "
        + "never repeat this bracketed status text aloud.]");
    private static final WorldEvent.Said MUSING =
        said(SELF, "mia", "I think I'll read on attention tonight.");

    private static WorldEvent.Said answers(WorldEvent.Said loop, WorldEvent.Said pending,
                                           boolean reactive, WorldEvent.Said last,
                                           WorldEvent.Said pinned) {
        return CompanionActor.personTheTurnAnswers(loop, pending, reactive, last, pinned,
            SPOKEN_BY_PERSON);
    }

    // ── Building a turn's tools (the posture's consent) ─────────────────

    @Test
    @DisplayName("her own time answers no one, even right after a person spoke")
    void herOwnTimeAnswersNoOne() {
        assertThat(answers(null, null, false, OWN_TIME, null)).isNull();
        // Own time builds its tools before it sets its trigger: lastReactTrigger and the pin
        // are still the person's from the turn before. That is not consent for her own time.
        assertThat(answers(null, null, false, PERSON, PERSON)).isNull();
    }

    @Test
    @DisplayName("a person's first step after her own time is theirs")
    void aPersonsFirstStepAfterHerOwnTimeIsTheirs() {
        assertThat(answers(null, PERSON, true, OWN_TIME, PERSON)).isSameAs(PERSON);
    }

    @Test
    @DisplayName("a person's line about to run decides, whatever the flag was left at")
    void aPendingPersonsLineDecides() {
        // The phone bud's delegation never sets reactiveInference.
        assertThat(answers(null, PERSON, false, OWN_TIME, null)).isSameAs(PERSON);
    }

    @Test
    @DisplayName("another companion is not a person, even when the room snapshot misses her")
    void aPeerIsNotAPerson() {
        assertThat(answers(null, PEER, true, PERSON, PEER)).isNull();
        assertThat(answers(null, null, true, PEER, PEER)).isNull();
        var remote = said("agent-lulu", "lulu", "find me something on transformers");
        assertThat(answers(null, remote, true, null, remote)).isNull();
    }

    @Test
    @DisplayName("the greeting scaffold carries a name but is not a person")
    void theGreetingIsNotAPerson() {
        assertThat(answers(null, null, true, GREETING, null)).isNull();
    }

    @Test
    @DisplayName("her tool's result carries the person's request, and only a person's")
    void herToolResultCarriesThePersonsRequest() {
        assertThat(answers(null, HAND_BACK, true, PERSON, PERSON)).isSameAs(PERSON);
        assertThat(answers(null, HAND_BACK, true, OWN_TIME, null)).isNull();   // her own time
        assertThat(answers(null, HAND_BACK, true, PEER, PEER)).isNull();       // a peer's turn
    }

    @Test
    @DisplayName("her own musing does not borrow the pinned request")
    void herMusingDoesNotBorrowThePin() {
        assertThat(answers(null, MUSING, true, PERSON, PERSON)).isNull();
    }

    @Test
    @DisplayName("a live loop answers the request it was opened for")
    void aLiveLoopAnswersItsRequest() {
        // A peer's line landed in pendingTrigger between steps, and an own-time plan tick set
        // reactiveInference false over the loop: the person's loop is still theirs.
        assertThat(answers(PERSON, PEER, false, OWN_TIME, PERSON)).isSameAs(PERSON);
        // An own-time plan loop has no requester: it answers no one.
        assertThat(answers(null, OWN_TIME, false, OWN_TIME, null)).isNull();
    }

    // ── Speaking (the hush and the trail): pending is not read ──────────

    @Test
    @DisplayName("a tell answered in the loop's first words is a reply to the person")
    void aReplyAtTheLoopsFirstStepIsToThePerson() {
        // No built-in action has run yet, so lastReactTrigger is still her own-time turn.
        assertThat(answers(PERSON, null, true, OWN_TIME, PERSON)).isSameAs(PERSON);
    }

    @Test
    @DisplayName("a reply after its trigger was consumed is to the person")
    void aConsumedTriggersReplyIsToThePerson() {
        assertThat(answers(null, null, true, PERSON, PERSON)).isSameAs(PERSON);
    }

    @Test
    @DisplayName("an own-time line is to no one")
    void anOwnTimeLineIsToNoOne() {
        assertThat(answers(null, null, false, OWN_TIME, null)).isNull();
        // Own-time loop, first step: lastReactTrigger can still be a person's from before.
        assertThat(answers(null, null, false, PERSON, null)).isNull();
    }

    // ── The wiring ──────────────────────────────────────────────────────

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    private static String body(String src, String signature) {
        int i = src.indexOf(signature);
        assertThat(i).as(signature + " exists").isGreaterThan(-1);
        return src.substring(i, src.indexOf("\n    }", i));
    }

    @Test
    @DisplayName("the predicate above is isHumanTrigger's rule")
    void thePredicateIsIsHumanTriggersRule() throws Exception {
        // The rule lives in isPersonEntityId since 2026-09-26, shared by speech and arrival.
        var src = actorSource();
        assertThat(body(src, "private boolean isHumanTrigger(WorldEvent.Said trig)"))
            .contains("return isPersonEntityId(trig.entityId());");
        assertThat(body(src, "private boolean isPersonEntityId(String id)"))
            .contains("id.equals(profile.entityId())")
            .contains("id.startsWith(\"agent-\") || id.startsWith(\"companion-\")")
            .contains("id.equals(\"system\")");
    }

    @Test
    @DisplayName("only a person can hush or un-hush her in the room")
    void onlyAPersonHushes() throws Exception {
        assertThat(actorSource())
            .contains("if (hushReq != null && isHumanTrigger(said) && hushIsForMe(said.text())) {")
            .doesNotContain("!isAgentEntity(said.entityId()) && hushIsForMe");
    }

    @Test
    @DisplayName("a reply is to a person by the turn's line, not lastReactTrigger alone")
    void theReplyCheckUsesTheTurnsLine() throws Exception {
        var src = actorSource();
        assertThat(body(src, "private boolean isHumanDirectedReply()"))
            .contains("return personThisReplyAnswers() != null;");
        assertThat(body(src, "private WorldEvent.Said personThisReplyAnswers()"))
            .as("speech is judged without pendingTrigger — it can hold the next line")
            .contains("personTheTurnAnswers(reactMessages != null ? reactRequester : null, null,");
    }

    @Test
    @DisplayName("the BOUNDED consent is judged by the turn being built")
    void consentReadsTheTurnBeingBuilt() throws Exception {
        var src = actorSource();
        assertThat(src)
            .contains("boolean directRequestConsent = aPersonAskedForThisTurn()")
            .doesNotContain("boolean directRequestConsent = isHumanDirectedReply()");
        assertThat(body(src, "private boolean aPersonAskedForThisTurn()"))
            .contains("personTheTurnAnswers(reactMessages != null ? reactRequester : null, pendingTrigger,");
    }

    @Test
    @DisplayName("an answer owed to a waiting bridge caller is not withheld by the hush")
    void theHushDeliversAnOwedAnswer() throws Exception {
        var src = actorSource();
        int g = src.indexOf("bondholderHush != HushLevel.NONE && answering == null");
        assertThat(g).as("the hush reads who the line answers, decided when it was spoken").isGreaterThan(-1);
        assertThat(src.substring(g, src.indexOf("{", g))).contains("&& (ownedByNoOne || !answersTheWaitingAsk())");
        assertThat(body(src, "private boolean answersTheWaitingAsk()"))
            .as("only the ask's own turn, not any line while an ask waits")
            .contains("pendingAskSenderId.equals(line.entityId())")
            .contains("!reactiveInference");
        assertThat(src).as("a stale ask expires").contains("new BridgeAskExpired(), BRIDGE_ASK_LIFETIME");
    }

    @Test
    @DisplayName("the trail names the person the line answers")
    void theTrailNamesThePerson() throws Exception {
        assertThat(actorSource())
            .contains("var answered = answering;")
            .doesNotContain("isHumanDirectedReply() ? freshReactTrigger()");
    }

    @Test
    @DisplayName("the vision detour rejoins as a reactive turn, like the detect and translate hops")
    void theVisionRejoinIsReactive() throws Exception {
        var b = body(actorSource(), "private Behavior<Command> onVisionAnalysisReceived(");
        assertThat(b.indexOf("reactiveInference = true;"))
            .isGreaterThan(-1)
            .isLessThan(b.indexOf("runIdentityInference();"));
    }

    @Test
    @DisplayName("the own-time autonomy gate is skipped only for a turn that answers a person")
    void theAutonomyGateAsksWhoTheTurnAnswers() throws Exception {
        var b = body(actorSource(), "private String builtinAutonomyDenial(String builtin)");
        assertThat(b)
            .contains("boolean servingAPerson = personThisReplyAnswers() != null")
            .as("the reactive flag alone is set on her own time after her own tool call")
            .doesNotContain("boolean servingAPerson = reactiveInference");
    }

    @Test
    @DisplayName("a person's plan answers them on its own steps only, and a model-written plan grants nothing")
    void aPersonsPlanKeepsTheirConsent() throws Exception {
        var src = actorSource();
        assertThat(body(src, "private boolean aPersonAskedForThisTurn()")).contains("return planStepTurn && personsPlanRequest != null && isHumanTrigger(personsPlanRequest);");
        assertThat(body(src, "private boolean personsPlanActive()")).contains("personsPlanId.equals(activePlan.planId())");
        assertThat(src).contains("personsPlanId = isHumanTrigger(syntheticSaid) ? activePlan.planId() : null;");
        assertThat(body(src, "private void handleCreateTaskPlan("))
            .as("a task_plan the model wrote names its own requester; it never grants consent")
            .doesNotContain("personsPlanId");
        // Only the plan-advance turn is a step of the plan (review of 2026-09-23).
        var trigger = body(src, "private boolean triggerAutonomousInference(String autonomyPrompt, String forcedTool, boolean planAdvance)");
        assertThat(trigger).contains("boolean planStep = planAdvance && activePlan != null")
            .contains("planStepTurn = planStep && personsPlanActive();")
            .contains("reactRequester = planStepTurn ? personsPlanRequest : null;");
        assertThat(src).contains("triggerAutonomousInference(planPrompt, null, true)");
        assertThat(body(src, "private void pinTurnAndArmFirstDoors()")).contains("planStepTurn = false;");
    }

    // ── Who a line answers, when it is spoken (review of 2026-09-22) ─────

    @Test
    @DisplayName("who a line answers is decided when it is spoken, and carried through the polish")
    void theLineCarriesWhoItAnswers() throws Exception {
        var b = body(actorSource(), "private void speak(String text, Map<String, String> preserveFacts, String authoredBy,\n");
        int decided = b.indexOf("final var answering = owedTo == NO_ONE ? NO_ONE");
        assertThat(decided).isGreaterThan(-1).isLessThan(b.indexOf("polishVoiceAsync("));
        assertThat(b).contains(": owedTo != null && isHumanTrigger(owedTo) ? owedTo : personThisReplyAnswers();")
            .contains("askPolishDone(forAsk); speakDirect(t, authoredBy, answering); }")
            .doesNotContain("t -> speakDirect(t, authoredBy));");
    }

    @Test
    @DisplayName("a turn served for a person is theirs after the loop closes while the pin is fresh; their plan on its own steps")
    void aServedTurnStaysThePersons() throws Exception {
        assertThat(body(actorSource(), "private WorldEvent.Said personThisReplyAnswers()"))
            .contains("var pinned = pinnedTurnRequest();")
            .contains("if (planStepTurn && personsPlanRequest != null && isHumanTrigger(personsPlanRequest)) return personsPlanRequest;")
            .as("unbounded, a greeting two minutes after a \"shh\" was that person's answer")
            .doesNotContain("if (turnIsHuman && turnHumanRequest != null && isHumanTrigger(turnHumanRequest))");
    }

    @Test
    @DisplayName("her own time begins wherever it starts, and every start waits for a turn in flight")
    void herOwnTimeIsMarkedWhereItBegins() throws Exception {
        var src = actorSource();
        assertThat(body(src, "private boolean aTurnIsInFlight()"))
            .contains("state != State.IDLE || reactMessages != null || pendingTrigger != null")
            .contains("someoneIsWaiting() || personsToolInFlight()");
        var enact = body(src, "private String enactInteriorityWant(");
        assertThat(enact).contains("reactiveInference = false;").contains("turnIsHuman = false;")
            .contains("if (aTurnIsInFlight())");
        assertThat(enact.indexOf("turnIsHuman = false;"))
            .as("after the gates that hold her own time for someone else's turn")
            .isGreaterThan(enact.indexOf("if (isSleeping) return \"skipped:sleeping\";"));
        var proactive = body(src, "private void executeProactiveAction(ProactiveAction action)");
        assertThat(proactive).contains("if (aTurnIsInFlight())")
            .as("the emote never passes through speak(); clearing the person's turn for it ended their slow tool's turn")
            .doesNotContain("turnIsHuman = false;");
        var trigger = body(src, "private boolean triggerAutonomousInference(String autonomyPrompt, String forcedTool, boolean planAdvance)");
        assertThat(trigger.indexOf("if (held)"))
            .as("held before anything is touched or sent")
            .isGreaterThan(-1).isLessThan(trigger.indexOf("reactiveInference = false;"));
        assertThat(trigger).contains(": aTurnIsInFlight();");
        assertThat(trigger).doesNotContain("yielding the turn to the person");
        assertThat(body(src, "private Behavior<Command> onGreetPlayer(")).contains("if (aTurnIsInFlight()) return this;")
            .contains("turnIsHuman = false;");
        assertThat(src).contains("if (!aTurnIsInFlight() && !isSleeping\n");
    }

    @Test
    @DisplayName("the action tier and consent gate use the person rule, and a model-written plan grants nothing")
    void thePolicyGateUsesThePersonRule() throws Exception {
        var b = body(actorSource(), "private boolean enforceActionPolicy(ActionParser.AgentAction action)");
        assertThat(b).contains("boolean humanDirected = personThisReplyAnswers() != null")
            .contains("|| (planStepTurn && personsPlanRequest != null && isHumanTrigger(personsPlanRequest));")
            .doesNotContain("activePlan.requesterId() != null")
            .doesNotContain("boolean humanDirected = reactiveInference");
        assertThat(body(actorSource(), "private String builtinAutonomyDenial(String builtin)"))
            .contains("&& isHumanTrigger(reactRequester));")
            .doesNotContain("isHumanRequest(reactRequester)");
    }

    @Test
    @DisplayName("answers that come back outside a turn carry the person they are for")
    void answersOutsideATurnArePersons() throws Exception {
        var src = actorSource();
        int sc = src.indexOf("shortCircuitFor = isHumanTrigger(syntheticSaid) ? syntheticSaid : NO_ONE;");
        assertThat(sc).as("the short-circuit carries who asked").isGreaterThan(-1);
        assertThat(src.substring(sc, sc + 900))
            .as("not pinned onto whatever turn is in flight")
            .doesNotContain("turnHumanRequest = syntheticSaid;");
        assertThat(src).contains("final var asked = shortCircuitFor;")
            .contains("speakDirect(composed, null, shortCircuitFor);")
            .contains("tellOwedTo = msg.asked();");
        // A scripted tool, a workshop task, a familiar, a bunshin, an outcome voice, a voice-passed tell.
        assertThat(src).contains("final var toolFor = reactMessages == null ? orNoOne(personThisReplyAnswers()) : null;")
            .contains("ToolResultEnvelope.normalize(itemId, result), dispatchGen, toolFor, loopAtSend));")
            .contains("speakFindings(spoken, forPerson);")
            .contains("new DispatchTaskCompleted(taskDescription, result, failure, taskFor)")
            .contains("speak(narration, null, ActivityLogger.AUTHORED_REPORT, msg.requester());")
            .contains("return new OutcomeVoiceReady(response, outcomeFact, requiredValue, owedTo);")
            .contains("return new VoicePassReady(action, response, requiredEntities, owedTo);");
        assertThat(body(src, "private Behavior<Command> onBunshinReportReceived("))
            .contains("speakDirect(narration, ActivityLogger.AUTHORED_REPORT, owedTo);");
        assertThat(src).contains("reactRequester = planStepTurn ? personsPlanRequest : null;");
    }

    @Test
    @DisplayName("a loop ends with the line it was opened for, on every way it ends")
    void aLoopConsumesItsTrigger() throws Exception {
        var src = actorSource();
        assertThat(body(src, "private void closeReactLoop()"))
            .contains("if (pendingTrigger != null && pendingTrigger == reactOpenedFor)")
            .contains("pendingTrigger = null;")
            .contains("promoteDeferredTrigger(");
        assertThat(src).contains("reactOpenedFor = pendingTrigger;")
            .contains("if (openedLoop) reactOpenedFor = autonomyEvent;");
        // goal_done, the spoken end, the iteration cap, the apology, follow-through, a stuck turn.
        int closes = 0;
        for (int i = src.indexOf("closeReactLoop();"); i >= 0; i = src.indexOf("closeReactLoop();", i + 1)) closes++;
        assertThat(closes).isGreaterThanOrEqualTo(6);
    }

    // ── Review of the rework itself (2026-09-23) ─────────────────────────

    @Test
    @DisplayName("a line heard while a loop is open waits for the loop; a line left pending is not left stranded")
    void aLineDuringALoopWaitsForIt() throws Exception {
        var src = actorSource();
        assertThat(src).contains("if (state == State.IDLE && !isSleeping && reactMessages == null) {")
            .contains("if (state == State.IDLE && !isSleeping && !peerDuringActivePlan && reactMessages == null) {");
        assertThat(body(src, "private void closeReactLoop()"))
            .contains("var stray = pendingTrigger;")
            .contains("deferredTriggers.addFirst(stray);");
    }

    @Test
    @DisplayName("a loop never waits for good: an inline builtin goes on with it, and a loop gone silent is closed")
    void aLoopNeverWaitsForGood() throws Exception {
        var src = actorSource();
        assertThat(src).contains("if (scriptedToolSends == sendsBefore && reactMessages != null")
            .contains("scriptedToolSends++;")
            .contains("THINKING_TIMEOUT.multipliedBy(2)) > 0) {");
        var capture = src.substring(src.indexOf("if (rolloutCaptureSink.captureOnly()) {"));
        assertThat(capture.substring(0, capture.indexOf("return true;")))
            .as("a capture-only pass sent nothing, so it ends the turn it opened")
            .contains("state = State.IDLE;");
    }

    @Test
    @DisplayName("a companion's line is answered, not made a person's plan's request; a held plan step comes back")
    void aPeerLineIsNotAPlanStep() throws Exception {
        var src = actorSource();
        assertThat(src).contains("if (activePlan != null && activePlan.isActive() && !aCompanionsLine(pendingTrigger)) {")
            .contains("if (!triggerAutonomousInference(planPrompt, null, true)) {")
            .contains("if (attended) clearConsumedEvents(salientEvents);");
        var trigger = body(src, "private boolean triggerAutonomousInference(String autonomyPrompt, String forcedTool, boolean planAdvance)");
        assertThat(trigger).contains("|| aPersonIsWaiting() || personsToolInFlight()");
    }

    @Test
    @DisplayName("a judgment turn and think_deeply's follow-up carry their person, however long they wait")
    void aJudgmentTurnCarriesItsPerson() throws Exception {
        var src = actorSource();
        assertThat(src).contains("judgmentTrigger = syntheticEvent;")
            .contains("judgmentFor = forPerson != null ? forPerson : orNoOne(personThisReplyAnswers());")
            .contains("judgmentFor = orNoOne(thinkDeeplyFor);");
        var answers = body(src, "private WorldEvent.Said personThisReplyAnswers()");
        assertThat(answers.indexOf("if (onTheJudgmentTurn(false))"))
            .as("the carrier answers before the pin can be borrowed")
            .isGreaterThan(-1).isLessThan(answers.indexOf("personTheTurnAnswers("));
        assertThat(body(src, "private boolean onTheJudgmentTurn(boolean building)"))
            .contains("return freshReactTrigger() == judgmentTrigger;");
        assertThat(src).contains("|| !isHumanTrigger(turnHumanRequest))) {");
    }

    @Test
    @DisplayName("work her own time sent out answers no one when it lands")
    void herOwnWorkAnswersNoOne() throws Exception {
        var src = actorSource();
        assertThat(src).contains("static final WorldEvent.Said NO_ONE = new WorldEvent.Said(null, Instant.EPOCH, \"system\"")
            .contains("orNoOne(bunshinFor)")
            .contains("final var taskFor = orNoOne(personThisReplyAnswers());")
            .contains("final boolean ownedByNoOne = answering == NO_ONE;");
    }

    @Test
    @DisplayName("a bridge ask gets its kept line when its turn settles, never ahead of its polished answer")
    void theBridgeAskSettles() throws Exception {
        var src = actorSource();
        assertThat(body(src, "private Behavior<Command> onBridgeAskSettle(BridgeAskSettle msg)"))
            .contains("askAnswersInPolish > 0")
            .contains("deliverAskFallback();");
        assertThat(body(src, "private void closeReactLoop()")).contains("scheduleAskSettle();")
            .doesNotContain("deliverAskFallback();");
    }

    @Test
    @DisplayName("queued extras are gated when they run, a person's carries them, and her acts are not their plan's")
    void extrasAndPlanBooking() throws Exception {
        var src = actorSource();
        assertThat(src).contains("extraOwedTo = next.forPerson();")
            .contains("if (!enforceActionPolicy(next.action())) return;")
            .contains("&& (planStepTurn || !personsPlanActive() || personThisReplyAnswers() != null)")
            .contains("speakDirect(blockMsg, ActivityLogger.AUTHORED_PRODUCT, NO_ONE);");
    }

    // ── Second review of the rework (2026-09-23) ───────────────────────────

    @Test
    @DisplayName("a result feeds only the loop that sent it; one that lands in another loop is parked")
    void aResultFeedsOnlyItsLoop() throws Exception {
        var src = actorSource();
        assertThat(src).contains("boolean ourLoop = reactMessages != null && msg.loopGen() != 0L && msg.loopGen() == reactLoopGen;")
            .contains("parkedToolResults.addLast(new ParkedToolResult(env, msg.forPerson()));")
            .contains("dispatchGen, toolFor, loopAtSend));");
        assertThat(body(src, "private Behavior<Command> onReleaseParkedResults(ReleaseParkedResults msg)"))
            .as("a parked result waits for the turns in flight, a waiting line first")
            .contains("if (!aTurnIsInFlight()) {");
    }

    @Test
    @DisplayName("one step of a loop is out at a time, and no waiting line starts a turn inside a loop")
    void oneStepAndNoTurnInsideALoop() throws Exception {
        var src = actorSource();
        assertThat(body(src, "private Behavior<Command> onReactDispatch(ReactDispatch msg)"))
            .contains("if (reactMessages != null && reactStepInFlight) {");
        assertThat(body(src, "private Behavior<Command> onProcessEvents(ProcessEvents msg)"))
            .contains("if (reactMessages != null || reinjectingLoopStep) {");
        assertThat(body(src, "private void promoteDeferredTrigger(Duration delay)"))
            .contains("if (reactMessages != null || reinjectingLoopStep) {");
        assertThat(src).contains("pendingTrigger == null && reactMessages == null) {")
            .contains("reinjectingLoopStep = true;");
    }

    @Test
    @DisplayName("the loop hears what a builtin did, and the room that exists pays the room debt")
    void theLoopHearsWhatHappened() throws Exception {
        var src = actorSource();
        assertThat(src).contains("lastBuiltinOutcome = \"Refused, not done: \" + authoringDenial;")
            .contains("roomStillOwedAfterBuild = false;\n                roomOwedByTask.clear();")
            .contains("timers.startSingleTimer(\"plan-advance\", new AutonomyCheck(), Duration.ofSeconds(3));");
    }

    @Test
    @DisplayName("handlers quote the request this turn serves; a line owed to no one gets no hush exemption")
    void theRequestServedAndTheHush() throws Exception {
        var src = actorSource();
        assertThat(body(src, "private WorldEvent.Said requestThisTurnServes()"))
            .contains("extraOwedTo").contains("onTheJudgmentTurn(true)").contains("pinnedTurnRequest()");
        assertThat(src).contains("var pinnedReq = requestThisTurnServes();")
            .contains("var dispatchReq = requestThisTurnServes();")
            .contains("&& (ownedByNoOne || !answersTheWaitingAsk())) {");
    }
}

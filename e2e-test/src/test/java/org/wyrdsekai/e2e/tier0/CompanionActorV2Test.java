package org.wyrdsekai.e2e.tier0;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.agent.*;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.item.StarterKitProvisioner;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.skill.*;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;
import org.wyrdsekai.e2e.infra.TestActorSystem;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 0 integration tests for CompanionActor v2 capability wiring.
 * Uses Pekko TestProbe to inject canned LLM responses containing action blocks,
 * then verifies the CompanionActor executes the actions correctly.
 *
 * ActionParser format: actions in ```json ... ``` blocks with:
 *   equip/doff/consume: {"action":"equip","item":"..."}
 *   skill_execute: {"action":"skill_execute","skill_name":"...","params":{}}
 *   delegate_chain: {"action":"delegate_chain","goal":"...","steps":[...]}
 */
@Tag("integration")
class CompanionActorV2Test {

    private static final String AGENT_DID = "did:key:z6MkV2Actor";
    private static final String FAMILY_ID = "family-v2-actor";
    private static final String ROOM_ID = "nexus";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Kiko", "agent-kiko", "agent", "A helpful companion",
        "You are Kiko, a helpful companion in Wyrdsekai.",
        4096, 512, 0.7, AGENT_DID);

    private static ActorTestKit testKit;

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private FamilyLocker locker;
    private WorkbenchSkillExecutor workbenchExecutor;
    private SkillUsageTracker usageTracker;

    @BeforeAll
    static void setup() {
        testKit = TestActorSystem.create("companion-v2-test");
        // Warm the classifier encoder HERE, not inside a 3s assertion window:
        // the first trigger otherwise pays ~6-7s of one-time DJL native-lib +
        // MiniLM load on the actor dispatcher (cold JVM), and the equip tests'
        // expectMessage timeouts lose the race. Same convention as the
        // Lucene/ORT/GraalJS warmups elsewhere in the harness.
        try {
            var enc = EmbeddingService.classifierEncoder();
            if (enc != null) enc.embed("warmup");
        } catch (Exception ignored) {
            // No encoder available → runtime falls back the same way in-test.
        }
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void spawnCompanion() {
        var bud = SoulBud.original(AGENT_DID, "z6MkPubV2A", FAMILY_ID,
            "locker://test", "test-node", "qwen2.5:7b");
        locker = FamilyLocker.create(FAMILY_ID, "locker://test", bud);
        workbenchExecutor = new WorkbenchSkillExecutor(locker, AGENT_DID);
        usageTracker = new SkillUsageTracker();

        StarterKitProvisioner.provision(AGENT_DID, 8192, locker);

        var caps = new CompanionCapabilities(
            locker, null, workbenchExecutor, null, false, 0, null, true,
            null, usageTracker);

        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();

        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null,
            null, null, null, null, caps));

        var subscribe = roomProbe.expectMessageClass(RoomCommand.Subscribe.class,
            Duration.ofSeconds(5));
        subscriberRef = subscribe.subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(RoomCommand.LookRoom.class,
            Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    // --- Equip action ---

    @Test
    void equip_action_speaks_confirmation() {
        triggerInference("Switch to focused mode");
        var chatReq = captureInferenceRequest();

        chatReq.replyTo().tell(new InferenceRouter.InferOk(chatReq.requestId(),
            actionResponse("Let me put on my Focused Mode.", "equip", "Focused Mode"),
            20, 30));

        // First: prose, Second: equip confirmation
        var say1 = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say1.text()).contains("Focused Mode");
        var say2 = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say2.text()).contains("Equipped");
    }

    // --- Doff action ---

    @Test
    void doff_action_after_equip() {
        // Equip first
        triggerInference("Equip focused mode");
        var req1 = captureInferenceRequest();
        req1.replyTo().tell(new InferenceRouter.InferOk(req1.requestId(),
            actionResponse("Equipping.", "equip", "Focused Mode"), 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Now doff
        triggerInference("Take off focused mode");
        var req2 = captureInferenceRequest();
        req2.replyTo().tell(new InferenceRouter.InferOk(req2.requestId(),
            actionResponse("Removing it.", "doff", "Focused Mode"), 20, 30));

        var prose = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(prose.text()).contains("Removing");
        var doffMsg = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(doffMsg.text()).contains("Removed");
    }

    // --- Consume action ---

    @Test
    void consume_action_speaks_confirmation() {
        triggerInference("Drink the draught");
        var chatReq = captureInferenceRequest();
        chatReq.replyTo().tell(new InferenceRouter.InferOk(chatReq.requestId(),
            actionResponse("I'll drink this.", "consume", "Restoring Draught"), 20, 30));

        var prose = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(prose.text()).contains("drink");
        var consume = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(consume.text()).contains("Consumed");
    }

    // --- Skill execution with workbench ---

    @Test
    void skill_execute_action_runs_workbench_skill() {
        var def = SkillItemCodec.create("graaljs",
            "function execute(p) { return 'Hello ' + (p.name || 'world'); }",
            null, "Greet someone", null, null);
        var item = SkillItemCodec.toSoulItem("greet", def, AGENT_DID);
        workbenchExecutor.register("greet", item, def);

        triggerInference("Use greet skill");
        var chatReq = captureInferenceRequest();
        chatReq.replyTo().tell(new InferenceRouter.InferOk(chatReq.requestId(),
            skillResponse("Running the skill.", "greet", "{\"name\":\"Alice\"}"), 20, 30));

        var prose = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(prose.text()).contains("Running");
        var result = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(result.text()).contains("Done");

        assertThat(usageTracker.totalInvocations()).isEqualTo(1);
    }

    // --- Skill gap recording ---

    @Test
    void unavailable_skill_records_gap() {
        triggerInference("Check the weather");
        var chatReq = captureInferenceRequest();
        chatReq.replyTo().tell(new InferenceRouter.InferOk(chatReq.requestId(),
            skillResponse("Let me check.", "weather-check", "{}"), 20, 30));

        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        var gap = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(gap.text()).contains("don't have the tools");
    }

    // --- Equipment context in prompt ---

    @Test
    void equipment_context_injected_into_prompt_after_equip() {
        // Equip Focused Mode
        triggerInference("Equip focused mode");
        var req1 = captureInferenceRequest();
        req1.replyTo().tell(new InferenceRouter.InferOk(req1.requestId(),
            actionResponse("Sure.", "equip", "Focused Mode"), 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Second inference — prompt should include equipment context
        triggerInference("What are you wearing?");
        var req2 = captureInferenceRequest();

        var systemMessages = req2.messages().stream()
            .filter(m -> "system".equals(m.role()))
            .map(m -> m.content())
            .toList();
        assertThat(systemMessages).anyMatch(s ->
            s.contains("Focused Mode") && s.contains("Attire"));

        req2.replyTo().tell(new InferenceRouter.InferOk(req2.requestId(),
            "I'm wearing my Focused Mode right now.", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // --- Delegation chain ---

    @Test
    void delegation_chain_executes_multiple_steps() {
        var def = SkillItemCodec.create("graaljs",
            "function execute(p) { return 'step done'; }",
            null, "Do a step", null, null);
        workbenchExecutor.register("step-a", SkillItemCodec.toSoulItem("step-a", def, AGENT_DID), def);
        workbenchExecutor.register("step-b", SkillItemCodec.toSoulItem("step-b", def, AGENT_DID), def);

        triggerInference("Do a multi-step task");
        var chatReq = captureInferenceRequest();
        chatReq.replyTo().tell(new InferenceRouter.InferOk(chatReq.requestId(),
            chainResponse("I'll do this in steps.", "Multi-step task",
                "[{\"skill\":\"step-a\",\"params\":{},\"description\":\"First\"},{\"skill\":\"step-b\",\"params\":{},\"description\":\"Second\"}]"),
            20, 50));

        // Collect all spoken messages
        var msgs = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var say = roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
            msgs.add(say.text());
        }
        assertThat(msgs).anyMatch(s -> s.contains("Starting chain"));
        assertThat(msgs).anyMatch(s -> s.contains("Chain complete"));
    }

    // --- No capabilities graceful degradation ---

    @Test
    void equip_without_locker_speaks_degraded() {
        var bareRoomProbe = testKit.<RoomCommand>createTestProbe();
        var bareRouterProbe = testKit.<InferenceRouter.Command>createTestProbe();
        var bareCompanion = testKit.spawn(CompanionActor.create(
            PROFILE, bareRoomProbe.ref(), "bare-room", bareRouterProbe.ref(), null));

        var sub = bareRoomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        bareRoomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = bareRoomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));

        var said = new WorldEvent.Said(
            "bare-room", Instant.now(), "player-test", "Test", "Equip something");
        sub.subscriber().tell(new RoomNotification(said));

        var chatReq = bareRouterProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(chatReq.requestId(),
            actionResponse("Sure.", "equip", "anything"), 20, 30));

        var prose = bareRoomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        var degraded = bareRoomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(degraded.text()).contains("don't have access");
    }

    // --- Starter kit present in locker ---

    @Test
    void starter_kit_items_available_in_locker() {
        var aspects = locker.byCategory("aspect", AGENT_DID);
        var reagents = locker.byCategory("reagent", AGENT_DID);

        assertThat(aspects).isNotEmpty();
        assertThat(reagents).isNotEmpty();
        assertThat(aspects).anyMatch(i -> i.label().contains("Focused"));
        assertThat(aspects).anyMatch(i -> i.label().contains("Social"));
        assertThat(aspects).anyMatch(i -> i.label().contains("Garb"));
    }

    // --- Helpers ---

    private void triggerInference(String text) {
        var said = new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-alice", "Alice", text);
        subscriberRef.tell(new RoomNotification(said));
    }

    /**
     * Next DRIVE ChatRequest, answering any interleaved voice-polish requests
     * (requestId "polish-…") verbatim — same pattern as CompanionActorTest.
     * Without this, a multi-cycle test captures the previous turn's polish
     * request instead of the next drive request and the flow deadlocks.
     */
    private InferenceRouter.ChatRequest captureInferenceRequest() {
        while (true) {
            var req = routerProbe.expectMessageClass(
                InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
            if (req.requestId() != null && req.requestId().startsWith("polish-")) {
                var draft = req.messages().isEmpty() ? ""
                    : req.messages().get(req.messages().size() - 1).content();
                req.replyTo().tell(new InferenceRouter.InferOk(req.requestId(), draft, 5, 5));
                continue;
            }
            return req;
        }
    }

    /** Build an LLM response with an equip/doff/consume action block. */
    private String actionResponse(String prose, String action, String itemName) {
        return prose + "\n```json\n{\"action\":\"" + action + "\",\"item\":\"" + itemName + "\"}\n```";
    }

    /** Build an LLM response with a skill_execute action block. */
    private String skillResponse(String prose, String skillName, String paramsJson) {
        return prose + "\n```json\n{\"action\":\"skill_execute\",\"skill_name\":\"" +
            skillName + "\",\"params\":" + paramsJson + "}\n```";
    }

    /** Build an LLM response with a delegate_chain action block. */
    private String chainResponse(String prose, String goal, String stepsJson) {
        return prose + "\n```json\n{\"action\":\"delegate_chain\",\"goal\":\"" +
            goal + "\",\"steps\":" + stepsJson + "}\n```";
    }

    private RoomSnapshot testSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub of connections.",
            "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of());
    }
}

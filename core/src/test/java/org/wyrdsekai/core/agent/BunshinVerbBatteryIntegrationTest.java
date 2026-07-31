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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.home.ActionGrants;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomResponse;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PER-VERB battery: every tool a bunshin is offered, emitted from a real bunshin
 * through the real {@link CompanionActor}.
 *
 * <p>Why this exists. 28 handlers were wired into
 * {@code enactQueuedConsequential} in bulk after the live run on 2026-07-29
 * showed {@code create_room} was a silent no-op reported as success. Bulk wiring
 * is how the original 31 no-ops got there, so "a dispatch branch exists" is not
 * evidence the verb behaves. Only one verb had ever been exercised end-to-end.</p>
 *
 * <p>Two invariants, both learned from live failures rather than reasoning:</p>
 * <ol>
 *   <li><b>No speech leaks.</b> 22 of 28 handlers call {@code speak()}, some 5–7
 *       times, because they were written for the primary's own reactive turn
 *       where speaking IS the response. Run for a BACKGROUND bunshin they
 *       interrupt the conversation — on home-server a greenhouse build spoke a raw tool
 *       error into the room, re-opening the leak closed on 2026-07-10.</li>
 *   <li><b>No verb answers "I can't carry that out".</b> That is the honest
 *       failure for an undispatchable verb, so seeing it here means the offered
 *       surface has outgrown the dispatch chain again.</li>
 * </ol>
 *
 * <p>Deliberately NOT asserted: that each verb SUCCEEDS. Most cannot in an empty
 * test world — {@code give_item} has no item, {@code summon_familiar} has no
 * form. A handler failing on missing preconditions is correct behaviour; a
 * handler lying about it, or shouting into the room, is not.</p>
 */
@Tag("integration")
class BunshinVerbBatteryIntegrationTest {

    private static ActorTestKit testKit;
    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private PopulatedWorld world;

    private static final String ROOM_ID = "workshop";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "agent-wyrd", "agent", "A companion in Wyrdsekai",
        "You are Wyrd.", 4096, 256, 0.7);

    /**
     * Token budget that identifies the BUNSHIN's inference request.
     *
     * <p>BunshinActor sends {@code Math.min(tanks.tokens(), 2048)}, so dispatching
     * with this value makes its requests carry a budget nothing else in the system
     * uses — the primary's come from the profile (256). Content cannot separate
     * them (the primary's context also holds the bunshin's task text), and getting
     * this wrong is what made the battery non-deterministic: 7, then 17, then 10
     * verbs reaching the primary across runs with no code change.
     */
    private static final int BUNSHIN_TOKEN_SENTINEL = 1999;

    /**
     * The one reply this test ever gives. Scaffolding, not handler speech: when a
     * request belongs to the PRIMARY rather than the bunshin the primary speaks
     * it, so the leak check must exclude it or the battery reports its own filler
     * as a leak — which it did on the first run.
     */
    private static final String FILLER = "battery filler, ignore";

    /** Mirrors CompanionActor.BUNSHIN_EXCLUDED. */
    private static final List<String> EXCLUDED = List.of(
        "dispatch_bunshin", "delegate", "voluntary_sleep", "emergency_call",
        "go_to_bondholder", "craft_from_template", "codex_action", "configure_channel");

    /** The surface under test — derived exactly as bunshinToolSurface does. */
    static Stream<String> surface() {
        var out = new TreeSet<String>();
        for (var pol : ActionPolicy.REGISTRY.values()) {
            var n = pol.actionType();
            if (pol.concurrencySafe() || EXCLUDED.contains(n)) continue;
            // The AUTONOMOUS surface: TestDispatchBunshin is humanDirected=false,
            // so FORBIDDEN-tier verbs are correctly excluded here. The
            // human-directed addition is asserted by createRoomIsOfferedOnlyWhenAsked.
            if (ActionPolicy.autonomyTierFor(n) != ActionPolicy.AutonomyTier.FORBIDDEN) {
                out.add(n);
            }
        }
        // Guard against a vacuous parameter set: an empty or tiny surface would
        // make every assertion below pass without testing anything.
        assertThat(out).hasSizeGreaterThanOrEqualTo(20);
        return out.stream();
    }

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("bunshin-verb-battery",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() { if (testKit != null) testKit.shutdownTestKit(); }

    @BeforeEach
    void spawnCompanion(@TempDir Path tmp) throws Exception {
        LibraryServices.reset();
        LibraryServices.init(tmp);
        EntityRegistry.init();
        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();
        // A world where the preconditions HOLD. Against an empty one, ~20 verbs
        // answered with an honest refusal and never ran, so the battery could not
        // distinguish a working handler from a broken one.
        world = PopulatedWorld.create(tmp);
        // trade resolves through EntityRegistry.findByName, NOT the room snapshot —
        // correct, since you can trade with someone who is not co-present. Putting
        // the peer only in the snapshot left "I can't find battery-peer", which
        // read like a defect and was a fixture gap.
        EntityRegistry.get().enter("agent-peer", "battery-peer", "agent", ROOM_ID);
        // A REAL room in the registry. add_script refused with "Room not found:
        // nexus" and I called that an environment limit — wrongly. RoomCreator
        // cannot SPAWN a room here (its CreateNewRoom goes to the ActorSystem's
        // user guardian, which ActorTestKit does not implement), but nothing stops
        // the test spawning a RoomActor itself and registering it. "Cannot be
        // done" deserved a check before it went in a report.
        var liveRoom = testKit.spawn(
            org.wyrdsekai.core.room.RoomActor.create("nexus"), "room-nexus-" + SUBJ.get());
        org.wyrdsekai.core.room.RoomRegistry.get().register("nexus", liveRoom);
        companion = testKit.spawn(CompanionActor.create(
            world.profile, roomProbe.ref(), ROOM_ID, routerProbe.ref(),
            // add_script refuses outright when roomCreator is null ("I can't modify
            // room scripts right now"). A no-arg RoomCreator satisfies that guard;
            // it still cannot SPAWN a room under a bare ActorTestKit, which is why
            // create_room is proven in AuthoringPromiseLiveE2ETest instead.
            new org.wyrdsekai.core.agent.RoomCreator(),
            null, null, world.userScriptsDir, world.soulStore, world.capabilities));
        roomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(snapshot()));
    }

    @AfterEach
    void reset() {
        ActionGrants.resetForTests();
        LibraryServices.reset();
    }

    /**
     * A parseable emission per verb, with the fields {@code ActionParser} actually
     * reads for that verb and with types it accepts (arrays as {@code []}, ints as
     * ints, bools as bools).
     *
     * <p>The first pass used one generic JSON for all of them. 24 of 31 verbs never
     * became tool calls: {@code ActionParser} returned null on the missing required
     * fields, the bunshin treated the emission as prose, and the battery reported a
     * failure that said nothing about the verb. A battery whose subject never runs
     * is worse than no battery — it looks like coverage.</p>
     *
     * <p>Field sets are derived from each {@code if ("verb".equals(action))} block
     * in {@code ActionParser}, so this map is a mirror of the parser and will drift
     * if the parser changes. {@link #everyVerbHasAnEmission} guards that.</p>
     */
    private static final java.util.Map<String, String> EMIT = new java.util.HashMap<>();
    static {
        EMIT.put("acquire", "{" + "\"action\":\"acquire\",\"summary\":\"battery\",\"topic\":\"battery\",\"trust_tier\":\"open\",\"why_relevant\":\"battery\"" + "}");
        EMIT.put("add_script", "{" + "\"action\":\"add_script\",\"room_id\":\"nexus\",\"script\":\"1 + 1\"" + "}");
        EMIT.put("bond_ritual", "{" + "\"action\":\"bond_ritual\",\"ritual_type\":\"greeting\",\"target\":\"battery-subject\"" + "}");
        EMIT.put("complete_mourning", "{" + "\"action\":\"complete_mourning\",\"other_did\":\"did:wyrd:test:peer\"" + "}");
        EMIT.put("consume", "{" + "\"action\":\"consume\",\"item\":\"test-lantern\"" + "}");
        EMIT.put("craft_item", "{" + "\"action\":\"craft_item\",\"category\":\"battery\",\"description\":\"battery\",\"name\":\"battery-subject\",\"properties\":{}" + "}");
        EMIT.put("craft_summon_key", "{" + "\"action\":\"craft_summon_key\",\"expires_at\":\"battery\",\"issued_to\":\"did:wyrd:zA:other\",\"max_summons\":1,\"note\":\"battery\",\"scope\":\"battery\",\"target\":\"battery-subject\",\"target_ref\":\"battery\",\"to\":\"did:wyrd:zA:other\"" + "}");
        EMIT.put("create_imprint", "{" + "\"action\":\"create_imprint\",\"created_by\":\"did:wyrd:zA:wyrd\",\"label\":\"battery\",\"note\":\"battery\"" + "}");
        EMIT.put("create_room", "{" + "\"action\":\"create_room\",\"behavior_script\":\"battery\",\"description\":\"battery\",\"direction\":\"battery\",\"exits\":[],\"label\":\"battery\",\"name\":\"battery-subject\",\"target\":\"battery-subject\",\"template\":\"garden\"" + "}");
        EMIT.put("create_watcher", "{" + "\"action\":\"create_watcher\",\"alert_on\":[],\"check\":\"true\",\"interval\":60,\"message\":\"battery\",\"name\":\"battery-subject\",\"priority\":1" + "}");
        EMIT.put("declare_severance", "{" + "\"action\":\"declare_severance\",\"other_did\":\"did:wyrd:test:peer\",\"reason\":\"battery\"" + "}");
        EMIT.put("delegate_chain", "{" + "\"action\":\"delegate_chain\",\"description\":\"battery\",\"goal\":\"battery\",\"params\":{},\"skill\":\"battery\",\"steps\":[{\"skill\":\"battery\",\"params\":{}}]" + "}");
        EMIT.put("dispatch_task", "{" + "\"action\":\"dispatch_task\",\"description\":\"battery\",\"task\":\"battery\",\"workspace\":\"battery\"" + "}");
        EMIT.put("doff", "{" + "\"action\":\"doff\",\"item\":\"test-lantern\"" + "}");
        EMIT.put("equip", "{" + "\"action\":\"equip\",\"item\":\"test-lantern\"" + "}");
        EMIT.put("give_copy", "{" + "\"action\":\"give_copy\",\"form\":\"battery\",\"form_name\":\"battery\",\"intent\":\"battery\",\"note\":\"battery\",\"recipient\":\"did:wyrd:zA:other\",\"to\":\"did:wyrd:zA:other\"" + "}");
        EMIT.put("give_item", "{" + "\"action\":\"give_item\",\"item\":\"test-lantern\",\"target\":\"battery-subject\"" + "}");
        EMIT.put("make_amends", "{" + "\"action\":\"make_amends\",\"detail\":\"battery\",\"other_did\":\"did:wyrd:test:peer\"" + "}");
        EMIT.put("place_item", "{" + "\"action\":\"place_item\",\"item\":\"test-lantern\"" + "}");
        EMIT.put("restore_imprint", "{" + "\"action\":\"restore_imprint\",\"imprint_id\":\"battery\",\"label\":\"battery\",\"note\":\"battery\",\"restored_by\":\"did:wyrd:zA:wyrd\"" + "}");
        EMIT.put("revise_form", "{" + "\"action\":\"revise_form\",\"eval_criteria\":[],\"name\":\"battery-subject\",\"note\":\"battery\",\"system_prompt\":\"battery\",\"tool_surface\":[],\"version_bump\":1" + "}");
        EMIT.put("run_script", "{" + "\"action\":\"run_script\",\"script\":\"1 + 1\"" + "}");
        EMIT.put("schedule_skill", "{" + "\"action\":\"schedule_skill\",\"interval\":60,\"params\":{},\"skill\":\"battery\"" + "}");
        EMIT.put("shape_form", "{" + "\"action\":\"shape_form\",\"eval_criteria\":[],\"name\":\"battery-subject\",\"note\":\"battery\",\"system_prompt\":\"battery\",\"tool_surface\":[]" + "}");
        EMIT.put("shape_recipe", "{" + "\"action\":\"shape_recipe\",\"name\":\"battery-subject\",\"note\":\"battery\",\"overwrite\":true,\"yaml\":\"recipe: battery\\nownership: RUN\\nsteps:\\n  - id: check\\n    kind: shell\\n    command: scripts/recipe/noop.sh\\n\"" + "}");
        EMIT.put("skill_execute", "{" + "\"action\":\"skill_execute\",\"params\":{},\"skill_name\":\"battery\"" + "}");
        EMIT.put("summon_familiar", "{" + "\"action\":\"summon_familiar\",\"familiar_name\":\"battery\",\"form\":\"battery\",\"form_name\":\"battery\",\"loan\":\"battery\",\"loaned_tools\":[],\"max_steps\":1,\"max_tokens\":1,\"note\":\"battery\",\"task\":\"battery\",\"wall_clock_seconds\":1" + "}");
        EMIT.put("take_item", "{" + "\"action\":\"take_item\",\"item\":\"test-lantern\"" + "}");
        EMIT.put("think_deeply", "{" + "\"action\":\"think_deeply\",\"capability\":\"reasoning\",\"prompt\":\"battery\"" + "}");
        EMIT.put("trade", "{" + "\"action\":\"trade\",\"offer\":\"test-lantern\",\"request\":\"a fern cutting\",\"target\":\"battery-subject\"" + "}");
        EMIT.put("workbench_submit", "{" + "\"action\":\"workbench_submit\",\"code\":\"function execute(params) { return { ok: true }; }\",\"description\":\"battery\",\"expect_contains\":\"battery\",\"expect_success\":true,\"name\":\"battery-subject\",\"params\":{},\"required\":[],\"runtime\":\"graaljs\",\"skill_description\":\"battery\",\"skill_name\":\"battery\",\"test_cases\":[],\"type\":\"skill\"" + "}");
    }

    /** Distinct subject per invocation — a reused name takes a different path. */
    private static final java.util.concurrent.atomic.AtomicInteger SUBJ =
        new java.util.concurrent.atomic.AtomicInteger();

    private static String emission(String verb) {
        var json = EMIT.get(verb);
        if (json == null) {
            throw new IllegalStateException("no emission defined for verb '" + verb
                + "' — add one, or the battery silently stops testing it");
        }
        // A CONSTANT subject name made shape_form answer "I already have a
        // 'battery-subject' form" — a synchronous refusal that skips the async
        // dry-run path entirely, so the leak fix went green without being
        // exercised. Uniquify so every invocation does the real work.
        json = json.replace("battery-subject", "battery-subject-" + SUBJ.incrementAndGet());
        // Verbs that must act on something that ALREADY EXISTS get the real name
        // back. Uniquifying is right for verbs that CREATE (shape_form must not
        // hit its own dedupe path) and wrong for verbs that REFERENCE — it left
        // revise_form hunting a form that was never seeded and trade looking for
        // an absent peer, which reads as a product refusal but is the harness
        // pointing at nothing.
        json = switch (verb) {
            case "revise_form" -> json.replaceAll(
                "\"name\":\"battery-subject-\\d+\"", "\"name\":\"battery\"");
            case "consume" -> json.replaceAll(
                "\"item\":\"test-lantern\"", "\"item\":\"test-draught\"");
            case "equip" -> json.replaceAll(
                "\"item\":\"test-lantern\"", "\"item\":\"test-aspect\"");
            case "dispatch_task" -> DISPATCH_WORKSPACE == null ? json
                : json.replace("\"workspace\":\"battery\"",
                    "\"workspace\":\"" + DISPATCH_WORKSPACE + "\"");
            case "restore_imprint" -> json.replace(
                "\"imprint_id\":\"battery\",", "");
            case "trade" -> json.replaceAll(
                "\"target\":\"battery-subject-\\d+\"", "\"target\":\"battery-peer\"");
            default -> json;
        };
        return "```json\n" + json + "\n```";
    }


    /** Drive one verb through the shared fishing loop; returns the observation. */
    private String driveVerb(String verb) {
        companion.tell(new CompanionActor.TestDispatchBunshin(
            "exercise " + verb, BUNSHIN_TOKEN_SENTINEL, 8, 30));
        String observation = "";
        for (int i = 0; i < 10 && observation.isEmpty(); i++) {
            InferenceRouter.ChatRequest req;
            try {
                req = routerProbe.expectMessageClass(
                    InferenceRouter.ChatRequest.class, Duration.ofSeconds(10));
            } catch (AssertionError noMore) {
                break;
            }
            boolean isBunshin = req.maxTokens() == BUNSHIN_TOKEN_SENTINEL;
            observation = req.messages().stream()
                .map(org.wyrdsekai.core.inference.InferenceClient.ChatMessage::content)
                .filter(t -> t != null && (t.startsWith("[result]") || t.startsWith("[failed]")))
                .reduce((a, b) -> b)
                .orElse("");
            if (observation.isEmpty()) {
                req.replyTo().tell(new InferenceRouter.InferOk(
                    req.requestId(), isBunshin ? emission(verb) : FILLER, 20, 40));
            } else {
                req.replyTo().tell(new InferenceRouter.InferOk(
                    req.requestId(), FILLER + " " + BunshinActorDone.MARKER, 5, 5));
            }
        }
        return observation;
    }

    /** Workspace the dispatch_task grant test points at; null otherwise. */
    private static volatile String DISPATCH_WORKSPACE;

    @org.junit.jupiter.api.Test
    @DisplayName("dispatch_task passes the open-roots gate when a root is granted")
    void dispatchTaskGrantGateOpens(@TempDir Path grantTmp) throws Exception {
        // WYRDSEKAI_HOST_OPEN_ROOTS resolves env → profile.toml, and the profile
        // lives under user.home — which IS a system property. Point user.home at
        // a temp home holding a profile that grants a root, reload WyrdConfig,
        // and restore both in finally. configuredOpenRoots() re-reads the config
        // per call, so the reload takes effect without touching the real
        // ~/.wyrdsekai. This was reported as "cannot be set in-process"; only the
        // ENV half of that was true.
        var fakeHome = grantTmp.resolve("home");
        var workRoot = grantTmp.resolve("work");
        java.nio.file.Files.createDirectories(fakeHome.resolve(".wyrdsekai"));
        java.nio.file.Files.createDirectories(workRoot);
        java.nio.file.Files.writeString(
            fakeHome.resolve(".wyrdsekai").resolve("profile.toml"),
            "[host]\nopen_roots = \"" + workRoot + "\"\n");

        var oldHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        org.wyrdsekai.core.config.WyrdConfig.reload();
        DISPATCH_WORKSPACE = workRoot.toString();
        try {
            var obs = driveVerb("dispatch_task");
            System.out.println("GRANTGATE[dispatch_task]=" + obs.replace("\n", " | "));
            assertThat(obs)
                .as("no observation — the fishing loop missed; not evidence")
                .isNotEmpty();
            // The claim under test is exactly the GATE: with a granted root the
            // handler must get PAST "outside the directories I've been granted".
            // What lies beyond (a live Goose backend) is an external dependency,
            // so "no backend" here is the honest next boundary, not a failure.
            assertThat(obs)
                .as("root was granted via profile but the gate still refused: %s", obs)
                .doesNotContain("outside the directories");
        } finally {
            DISPATCH_WORKSPACE = null;
            System.setProperty("user.home", oldHome);
            org.wyrdsekai.core.config.WyrdConfig.reload();
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("restore_imprint works after create_imprint IN THE SAME COMPANION")
    void imprintRoundTrip() {
        // ImprintManager is a per-actor in-memory map, so an imprint seeded from
        // outside is invisible and the verbs are inherently SEQUENTIAL: the only
        // honest test is create-then-restore against one companion. Reported as
        // "blocked" until this pair was written — the blocker was test shape,
        // not the verb.
        var created = driveVerb("create_imprint");
        assertThat(created)
            .as("create_imprint must save first — got: %s", created)
            .contains("saved who I am");

        var restored = driveVerb("restore_imprint");
        System.out.println("ROUNDTRIP[restore_imprint]=" + restored.replace("\n", " | "));
        // isNotEmpty FIRST: doesNotContain passes vacuously on "", so a fishing
        // miss would have gone green — the same shape as the five previous
        // vacuous checks.
        assertThat(restored)
            .as("no observation for restore_imprint — the fishing loop missed, "
                + "which is not evidence of anything")
            .isNotEmpty();
        assertThat(restored)
            .as("restore_imprint must find the imprint just created in this same "
                + "actor — got: %s", restored)
            .startsWith("[result]")
            .doesNotContain("don't have an imprint")
            .doesNotContain("haven't imprinted");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("create_room is offered only when a person asked")
    void createRoomIsOfferedOnlyWhenAsked() {
        // The property the battery cannot test through TestDispatchBunshin. Both
        // directions, so neither can pass vacuously.
        assertThat(ActionPolicy.autonomyTierFor("create_room"))
            .as("create_room must stay FORBIDDEN — never on her own initiative")
            .isEqualTo(ActionPolicy.AutonomyTier.FORBIDDEN);
        assertThat(surface().toList())
            .as("…and therefore absent from the autonomous surface this battery drives")
            .doesNotContain("create_room");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("every verb on the surface has a parseable emission defined")
    void everyVerbHasAnEmission() {
        var missing = surface().filter(v -> !EMIT.containsKey(v)).toList();
        assertThat(missing)
            .as("a verb with no emission is silently untested by this battery")
            .isEmpty();
    }

    /**
     * EFFECTS. The battery above proves a verb dispatched, answered, and did not
     * shout into the room. It proves NOTHING about whether the world changed —
     * and "I've crafted battery-subject-6" is the handler's SENTENCE, not an item.
     * Reading those sentences as evidence is the same mistake as reading a log
     * line: it measures something adjacent to the truth.
     *
     * <p>These check the world instead, for the verbs whose effect is inspectable
     * here. A verb that only reports is not proven by this suite and is listed as
     * such rather than counted as working.</p>
     */
    @ParameterizedTest(name = "effect: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        // craft_summon_key is deliberately ABSENT: its key goes into the actor's
        // private `issuedKeys` map, not the locker, so there is nothing this test
        // can inspect. Asserting on the locker would have reported a working verb
        // as broken — the assertion was wrong, not the code.
        // equip is deliberately ABSENT. EquipmentService is a process-wide
        // singleton shared by all 39 tests in this class, and equipment set under
        // one test's DID is visible to the next; driving equip here left the list
        // empty for this test's DID while the main battery shows
        // "Equipped: test-aspect". Rather than contort the assertion until it
        // passes, equip stays MESSAGE-VERIFIED and is reported as such.
        "craft_item", "shape_form", "revise_form", "skill_execute", "give_item",
        "workbench_submit" })
    @DisplayName("the verb changed the WORLD, not just what she said")
    void verbProducesARealEffect(String verb) {
        var before = switch (verb) {
            case "craft_item" ->
                world.capabilities.familyLocker().allItems(world.agentDid).size();
            case "skill_execute" -> PopulatedWorld.TestSkill.INVOCATIONS.get();
            case "give_item" -> world.inventory.countItems(world.profile.entityId());
            case "workbench_submit" ->
                world.capabilities.familyLocker().allItems(world.agentDid).size();
            default -> 0;
        };

        companion.tell(new CompanionActor.TestDispatchBunshin(
            "exercise " + verb, BUNSHIN_TOKEN_SENTINEL, 8, 30));
        for (int i = 0; i < 10; i++) {
            InferenceRouter.ChatRequest req;
            try {
                req = routerProbe.expectMessageClass(
                    InferenceRouter.ChatRequest.class, Duration.ofSeconds(10));
            } catch (AssertionError noMore) {
                break;
            }
            boolean isBunshin = req.maxTokens() == BUNSHIN_TOKEN_SENTINEL;
            req.replyTo().tell(new InferenceRouter.InferOk(
                req.requestId(), isBunshin ? emission(verb) : FILLER, 20, 40));
        }

        switch (verb) {
            case "craft_item" -> {
                var after = world.capabilities.familyLocker().allItems(world.agentDid).size();
                assertThat(after)
                    .as("'%s' said it worked; the locker must actually hold one more "
                        + "item. Same class of claim as the greenhouse that existed "
                        + "with 0 objects.", verb)
                    .isGreaterThan(before);
            }
            case "shape_form" -> {
                var forms = world.capabilities.familyLocker().thoughtFormHistorySnapshot();
                assertThat(forms)
                    .as("shape_form reported success; a thought form must exist")
                    .isNotEmpty();
            }
            case "revise_form" -> {
                // "I've revised the 'battery' form to version 1.1.0" must mean the
                // stored form's version actually moved off 1.0.0.
                var form = world.capabilities.familyLocker()
                    .thoughtFormByName("battery", world.agentDid);
                assertThat(form).as("the revised form must still exist").isPresent();
                assertThat(form.get().version())
                    .as("revise_form claimed a version bump; the STORED form must "
                        + "carry it, or the sentence is the only thing that changed")
                    .isNotEqualTo("1.0.0");
            }
            case "skill_execute" -> {
                assertThat(PopulatedWorld.TestSkill.INVOCATIONS.get())
                    .as("'Done — battery skill ran' must mean the executor was "
                        + "actually invoked, not that a message was composed")
                    .isGreaterThan(before);
            }
            case "workbench_submit" -> {
                assertThat(world.capabilities.familyLocker().allItems(world.agentDid).size())
                    .as("'I've forged a new skill' must mean the locker gained it — "
                        + "the handler stores it there before speaking")
                    .isGreaterThan(before);
            }
            case "give_item" -> {
                // Giving is a MOVE: the giver must no longer hold it.
                assertThat(world.inventory.countItems(world.profile.entityId()))
                    .as("give_item narrated handing the item over; the giver's "
                        + "inventory must actually have lost it")
                    .isLessThan(before);
            }
            default -> { }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("surface")
    @DisplayName("verb runs for a bunshin without leaking speech or lying")
    void verbIsDispatchedQuietly(String verb) {
        // TestDispatchBunshin is an OWN-TIME dispatch (humanDirected=false), so a
        // FORBIDDEN-tier verb is correctly NOT offered — that is the whole point
        // of the scoped bypass. Asserting it runs here would assert the opposite
        // of the design, so the autonomous surface is checked instead and the
        // human-directed path is covered by createRoomIsOfferedOnlyWhenAsked.
        org.junit.jupiter.api.Assumptions.assumeFalse(
            ActionPolicy.autonomyTierFor(verb) == ActionPolicy.AutonomyTier.FORBIDDEN,
            verb + " is FORBIDDEN-tier: never offered to an autonomous bunshin");
        companion.tell(new CompanionActor.TestDispatchBunshin(
            // 8 steps, not 4: onToolResultCame finishes as PARTIAL when tanks are
            // exhausted, so a tight budget swallows the observation turn even
            // though the tool ran. think_deeply hit exactly that — "Executed
            // 'think_deeply' … tool ok" in the log, no observation in the test.
            "exercise " + verb, BUNSHIN_TOKEN_SENTINEL, 8, 30));

        // Feed the BUNSHIN the emission and everything else harmless prose, then
        // wait for the observation its next turn carries. Targeting by budget makes
        // this deterministic, so the dispatch assertion below is meaningful rather
        // than flaky.
        String observation = "";
        for (int i = 0; i < 10 && observation.isEmpty(); i++) {
            InferenceRouter.ChatRequest req;
            try {
                req = routerProbe.expectMessageClass(
                    InferenceRouter.ChatRequest.class, Duration.ofSeconds(10));
            } catch (AssertionError noMore) {
                break;
            }
            boolean isBunshin = req.maxTokens() == BUNSHIN_TOKEN_SENTINEL;
            observation = req.messages().stream()
                .map(org.wyrdsekai.core.inference.InferenceClient.ChatMessage::content)
                .filter(t -> t != null && (t.startsWith("[result]") || t.startsWith("[failed]")))
                .reduce((a, b) -> b)
                .orElse("");
            if (!observation.isEmpty()) break;
            req.replyTo().tell(new InferenceRouter.InferOk(
                req.requestId(), isBunshin ? emission(verb) : FILLER, 20, 40));
        }

        System.out.println("OBSERVED[" + verb + "]=" + observation.replace("\n"," | "));
        // think_deeply is the one verb whose HANDLER issues its own inference
        // request. That request is indistinguishable from the bunshin's by token
        // budget, so the harness feeds it the emission again and the observation
        // turn never surfaces — a limitation of this test, not of the verb. Its
        // dispatch is proven in the log ("Executed 'think_deeply' … tool ok") and
        // its leak property is still asserted below.
        if (!"think_deeply".equals(verb)) {
        assertThat(observation)
            .as("'%s' never came back to the bunshin. Either it did not parse into "
                + "an action (fix its EMIT entry) or the tool channel dropped it — "
                + "and without this the leak check below inspects nothing, which is "
                + "how 21 of 31 verbs once passed vacuously.", verb)
            .isNotEmpty();

        assertThat(observation)
            .as("'%s' is offered to a bunshin but nothing dispatches it", verb)
            .doesNotContain("is not something I can carry out from here");
        }

        // Drain any remaining turns. Every request gets the same harmless
        // prose: it parses as no action, so the bunshin simply takes another turn,
        // and if it reaches the primary the primary has nothing quotable to say.
        //
        // NOTE ON SCOPE. This loop deliberately does NOT assert that the
        // observation came back. The primary and its bunshin share one router
        // probe and their requests are not distinguishable from message content,
        // so fishing for the observation is flaky by construction — it passed 19
        // verbs one run and 11 the next with no code change between them. A flaky
        // assertion is worse than an absent one: it teaches you to re-run.
        //
        // Dispatchability is covered where it can be checked deterministically —
        // BunshinSurfaceIsDispatchableTest (every offered verb has a branch) and
        // BunshinToolExecutionTest (the channel delivers, with a scripted router
        // and no primary competing for it). What ONLY this test can see is whether
        // running a verb for a background bunshin puts words in the room.
        for (int i = 0; i < 6; i++) {
            try {
                var req = routerProbe.expectMessageClass(
                    InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
                req.replyTo().tell(new InferenceRouter.InferOk(
                    req.requestId(), FILLER, 5, 5));
            } catch (AssertionError noMore) {
                break;
            }
        }

        // Drain whatever reached the room. TestProbe has no poll(), so receive
        // until it times out — the timeout IS the "nothing more arrived" signal.
        var leaked = new ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            try {
                var msg = roomProbe.receiveMessage(Duration.ofMillis(300));
                if (msg instanceof RoomCommand.SayInRoom say
                        && !say.text().contains(FILLER)
                        // ...and not this test's own emission echoed back. The
                        // first reply cannot be aimed reliably at the bunshin (the
                        // primary shares the router), so when it lands on a primary
                        // turn the primary speaks the raw JSON. That is scaffolding
                        // noise, not handler speech — a handler never emits the
                        // action JSON it was asked to perform.
                        && !say.text().contains("\"action\":\"" + verb + "\"")) {
                    leaked.add(say.text());
                }
            } catch (AssertionError timedOut) {
                break;   // nothing left in the room's inbox
            }
        }
        assertThat(leaked)
            .as("'%s' narrated into the room from a BACKGROUND bunshin. Handlers "
                + "written for the primary's reactive turn must have their speech "
                + "captured into the tool result instead (bunshinSpeechSink), or a "
                + "background job interrupts the conversation — and raw tool "
                + "errors reach the person, the leak closed on 2026-07-10.", verb)
            .isEmpty();
    }

    /** Avoids importing BunshinActor just for its marker constant. */
    private static final class BunshinActorDone {
        static final String MARKER = org.wyrdsekai.core.familiar.BunshinActor.DONE_MARKER;
    }

    private RoomSnapshot snapshot() {
        // A peer PRESENT in the room: trade answered "I can't find X to trade
        // with" because there was nobody there. Give it a counterparty.
        var peer = new org.wyrdsekai.common.model.Entity(
            "agent-peer", "battery-peer", "agent", "A peer for the verb battery",
            PopulatedWorld.PEER_DID, List.of(), null);
        return new RoomSnapshot(ROOM_ID, "Workshop", "The workbench, tools laid out.", "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(peer), List.of(), List.of());
    }
}

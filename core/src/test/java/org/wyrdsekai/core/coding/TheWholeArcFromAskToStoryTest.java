package org.wyrdsekai.core.coding;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.item.CarriedItemUse;
import org.wyrdsekai.core.item.HouseholdItemContent;
import org.wyrdsekai.core.item.ItemProviderRegistry;
import org.wyrdsekai.core.item.ItemScriptResponse;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.core.item.VisitorItemProvider;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.room.RoomActor;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arc the steward has been testing by hand, standing up the real thing.
 *
 * <h2>Why this exists</h2>
 * Every defect on 2026-08-21 — six of them, on one path — was found by a person typing at
 * a live terminal and reading what came back. Three separate items were built, handed
 * over, and failed for three different reasons, each discovered only after a full
 * build-deploy-ask-goose round trip. His words: <i>"this is problematic as it is slow.
 * it's too dependant on a manual test."</i>
 *
 * <p>So: stand up the world. A real {@link RoomActor} with real persistence, a real
 * inventory over a real schema, the real {@link ScriptedItemLoader} over a real items
 * directory, the real contract gates, the real GraalJS sandbox. The item is placed in an
 * actual room, actually taken, and actually used, and the assertions are on the two things
 * a person receives: <b>what the terminal prints</b> and <b>what the room hears</b>.
 *
 * <h2>The one seam that is not stood up, and why</h2>
 * The coding backend and the 9B behind it. Those are a subprocess and a stochastic model:
 * they make a test slow and flaky, which is the complaint. Everything downstream of them
 * is deterministic and is exercised for real here.
 *
 * <p>What replaces them is not a mock that returns what we wish for — it is a
 * <b>corpus of files goose actually produced on the household node</b>, kept under
 * {@code items/corpus/}. Each one passed every check that existed at the time and failed
 * in a person's hands anyway:
 *
 * <ul>
 *   <li>{@code wrapped_iife.js} — {@code invoke} sealed inside
 *       {@code (function (exports) &#123;…&#125;)(exports)}; the textual entrypoint check
 *       said yes and the runtime found nothing to call.</li>
 *   <li>{@code world_from_params.js} — {@code const &#123; world &#125; = params}, so the
 *       item's own sandbox guard fired on every call.</li>
 *   <li>{@code accepted.js} — the one that should work, end to end.</li>
 * </ul>
 *
 * <p>A recorded corpus is the honest substitute for a stochastic generator: it cannot
 * drift, and every entry is a real thing that really shipped. New failure in the field →
 * new file here → the gate that would have caught it.
 */
class TheWholeArcFromAskToStoryTest {

    private static final String ROOM = "arc-nexus";
    private static final String PLAYER = "player-steward";

    private static ActorTestKit testKit;

    @TempDir Path tmp;
    private InventoryService inventory;
    private Path itemsDir;
    private Path workspace;
    private ItemScriptExecutor executor;
    private final List<String> roomHeard = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void bootActorSystem() {
        testKit = ActorTestKit.create("whole-arc-e2e",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void shutdown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @BeforeEach
    void standUpTheWorld() throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        System.setProperty("wyrdsekai.jdbc.url", jdbc);
        inventory = new InventoryService(jdbc);

        // A real household items directory — the place an accepted item is KEPT so it
        // outlives a restart. Registration writes here for real.
        itemsDir = Files.createDirectories(tmp.resolve("items"));
        System.setProperty("wyrdsekai.items.dir", itemsDir.toString());
        ScriptedItemLoader.get().reloadAll();

        workspace = Files.createDirectories(tmp.resolve("coding-workspace"));
        executor = new ItemScriptExecutor();

        CodingItemRegistry.get().clear();
        HouseholdItemContent.register(new FakeHouseholdLibrary());
        ItemProviderRegistry.register(entityId ->
            new VisitorItemProvider("home", "home")
                .withHouseholdContent(HouseholdItemContent.get()));
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.close();
        HouseholdItemContent.resetForTests();
        ItemProviderRegistry.resetForTests();
        CodingItemRegistry.get().clear();
        System.clearProperty("wyrdsekai.items.dir");
        System.clearProperty("wyrdsekai.jdbc.url");
    }

    // ------------------------------------------------------------------
    // The arc
    // ------------------------------------------------------------------

    /**
     * Build → gate → register → keep → place → take → use → the room hears a story.
     *
     * <p>No step is simulated: the room is a real event-sourced actor, the take is a real
     * {@code TakeObject}, the use goes through the same {@link CarriedItemUse} resolution
     * every surface calls, and the script runs in the real sandbox.
     */
    @Test
    void a_recorded_build_becomes_a_story_a_person_can_hear() throws Exception {
        var src = recorded("accepted.js", "library_keeper.js");

        // 1. The gates the bridge applies, on the real file.
        assertThat(CodingTaskItemBridge.willRegister(src))
            .as("the corpus's accepted file must pass every gate")
            .isTrue();

        var name = CodingTaskItemBridge.manifestNameOf(src).orElseThrow();
        var description = CodingTaskItemBridge.manifestDescription(src, name).orElseThrow();
        assertThat(description)
            .as("a person must be told what to type")
            .contains("`use " + name);

        // 2. Registration + keep — the real loader, writing to the real items dir.
        var roomObject = new org.wyrdsekai.common.model.RoomObject(
            "codex-" + UUID.randomUUID().toString().substring(0, 8), name, description,
            true, true);
        CodingTaskItemBridge.tryRegisterScriptedItem(roomObject, src);

        assertThat(ScriptedItemLoader.get().get(name))
            .as("an accepted item must be registered under its manifest name")
            .isPresent();
        assertThat(itemsDir.resolve(name + ".js"))
            .as("and kept where the loader looks, so it survives a restart")
            .exists();

        // 3. Place it in a REAL room and listen to what that room says.
        var room = testKit.spawn(RoomActor.create(ROOM));
        // The room must be in the registry, because that is how an item's voice finds it:
        // CarriedItemUse.attachRoomVoice resolves the room by id at speak time rather than
        // holding a reference. Production registers every room at boot; a test that skips
        // this gets a silent room and no error, which is precisely the failure mode the
        // whole day was made of.
        org.wyrdsekai.core.room.RoomRegistry.get().register(ROOM, room);
        var listener = testKit.<RoomNotification>createTestProbe();
        room.tell(new RoomCommand.Subscribe(listener.ref()));
        room.tell(new RoomCommand.ItemBridgeAction("bridge",
            new RoomCommand.ItemBridgeSubAction.AddObject(
                roomObject.id(), roomObject.name(), roomObject.description(), true)));

        // 4. The person takes it. Real TakeObject, real inventory row — and `take`
        //    copies no script columns, which is exactly why the loader fallback exists.
        var taken = testKit.<RoomResponse>createTestProbe();
        room.tell(new RoomCommand.TakeObject(PLAYER, name, "en", taken.ref()));
        var response = taken.expectMessageClass(RoomResponse.class, Duration.ofSeconds(10));
        assertThat(response)
            .as("the object the bridge placed must be takeable by its manifest name")
            .isInstanceOf(RoomResponse.ObjectTakenOk.class);
        var obj = ((RoomResponse.ObjectTakenOk) response).takenObject();
        inventory.addItem(PLAYER, obj.id(), obj.name(), obj.description(), true, ROOM);

        // 5. `use library_keeper a story` — the resolution every surface shares.
        var resolved = CarriedItemUse.resolve(inventory, PLAYER, name, "salt almanac")
            .orElseThrow(() -> new AssertionError(
                "a carried backend-authored item must resolve to its script — `take` "
                    + "copies no script columns, so this is the loader fallback"));

        var provider = ItemProviderRegistry.forEntity(PLAYER);
        CarriedItemUse.attachRoomVoice(provider, ROOM, PLAYER);
        var result = executor.execute(resolved.item().objectId(), resolved.source(),
            CarriedItemUse.params(PLAYER, resolved.target()), provider,
            CarriedItemUse.capabilitiesFor(resolved.item().objectId()));

        // 6. What the person reads.
        var printed = ItemScriptResponse.extractText(result, name);
        assertThat(printed)
            .as("the item's own answer, not a stock acknowledgment")
            .isNotEqualTo("You use the " + name + ".")
            .doesNotContain("visiting foreign zone")
            .contains("A paragraph about");

        // 7. What the ROOM hears — he asked for a tool that speaks out loud.
        // Bounded poll, not a fixed count: the room emits ObjectAdded and ObjectTaken
        // before the narration, and asserting on "the next N messages" makes the test
        // depend on how many world events happen to precede the one we care about.
        var spoken = listener.fishForMessage(Duration.ofSeconds(15), "the spoken story",
            n -> n.event() instanceof WorldEvent.Said said
                    && said.text() != null && said.text().contains("A paragraph about")
                ? org.apache.pekko.actor.testkit.typed.javadsl.FishingOutcomes.complete()
                : org.apache.pekko.actor.testkit.typed.javadsl.FishingOutcomes.continueAndIgnore());
        assertThat(spoken)
            .as("the story must reach the room, not just the person's terminal")
            .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // The corpus — every file here really shipped and really failed
    // ------------------------------------------------------------------

    /**
     * {@code invoke} sealed inside an IIFE. Passed the manifest, embodiment, commands and
     * the textual entrypoint check; the runtime had nothing to call. Live: the steward was
     * told to type {@code use library_query} and answered "No such object".
     */
    @Test
    void the_wrapped_iife_file_is_refused_before_anyone_is_told_to_use_it() throws Exception {
        var src = recorded("wrapped_iife.js", "library_query.js");
        var problem = ItemContractCheck
            .firstProblem(readCorpus("wrapped_iife.js"), "library_query.js").orElseThrow();
        assertThat(problem).contains("no CALLABLE invoke()");
        assertThat(CodingTaskItemBridge.willRegister(src)).isFalse();
        assertThat(CodingTaskItemBridge.manifestDescription(src, "library_query").orElseThrow())
            .as("a refused item must not tell a person what to type")
            .doesNotContain("`use library_query")
            .contains("unfinished");
    }

    /**
     * {@code const &#123; world &#125; = params} — {@code world} is a global, so the item's
     * own guard fired on every call. Live: handed over with usage lines, and using it fell
     * through to the legacy router.
     */
    @Test
    void the_world_from_params_file_is_refused_before_anyone_is_told_to_use_it()
            throws Exception {
        var src = recorded("world_from_params.js", "library_speaks.js");
        var problem = ItemContractCheck
            .firstProblem(readCorpus("world_from_params.js"), "library_speaks.js")
            .orElseThrow();
        assertThat(problem).contains("GLOBAL");
        assertThat(CodingTaskItemBridge.willRegister(src)).isFalse();
        assertThat(CodingTaskItemBridge.manifestDescription(src, "library_speaks").orElseThrow())
            .doesNotContain("`use library_speaks")
            .contains("unfinished");
    }

    /** A refused file must never be made permanent — the items dir is for what works. */
    @Test
    void a_refused_file_is_never_kept() throws Exception {
        var src = recorded("world_from_params.js", "library_speaks.js");
        var roomObject = new org.wyrdsekai.common.model.RoomObject(
            "codex-refused", "library_speaks", "…", true, true);
        CodingTaskItemBridge.tryRegisterScriptedItem(roomObject, src);
        assertThat(itemsDir.resolve("library_speaks.js"))
            .as("a rejected item must not survive a restart")
            .doesNotExist();
        assertThat(ScriptedItemLoader.get().get("library_speaks")).isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Copy a recorded file into a real workspace and describe it as a run would. */
    private SourceArtifact recorded(String corpusName, String asFile) throws Exception {
        Files.writeString(workspace.resolve(asFile), readCorpus(corpusName));
        return new SourceArtifact(UUID.randomUUID(), GooseBackend.NAME,
            "task-" + UUID.randomUUID(), workspace.toString(), List.of(asFile), null,
            Instant.now(), Map.of());
    }

    private String readCorpus(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/items/corpus/" + name)) {
            if (in == null) {
                throw new IllegalStateException("corpus file missing: " + name
                    + " — this test must never pass for want of its fixtures");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A household that has a library and a model. The content boundary, not the arc. */
    private static final class FakeHouseholdLibrary extends VisitorItemProvider {
        FakeHouseholdLibrary() { super("home", "home"); }

        @Override
        public List<Map<String, Object>> searchKnowledge(String query, int limit) {
            return List.of(Map.of("id", "c1", "title", "The Salt Almanac",
                "text", "A ledger of tides.", "score", 0.9));
        }

        @Override
        public String llmSummarize(String text, String instruction) {
            return "A paragraph about " + text;
        }
    }

    /**
     * The same item, used off the FLOOR instead of out of your hands.
     *
     * <h2>Why this test exists</h2>
     * There are three paths that invoke a scripted item — carried, furnishing, and
     * coding-item — and each builds its own params. On 2026-08-21
     * {@code invokeScriptedCodingItem} set {@code query} alone, so a backend-authored item
     * used from the room got no {@code params.args} at all. The contract PROMISES args, so
     * goose writes against args, and {@code use weather_lookup cambridge ma} answered
     * "(no arguments supplied)" for a command that plainly had some.
     *
     * <p>The arc test above proved the carried path. It said nothing about this one, which
     * is exactly how the gap survived — a test that covers one route through a fork proves
     * only that route.
     */
    @Test
    void an_item_used_from_the_floor_gets_its_arguments_too() throws Exception {
        var src = recorded("accepted.js", "library_keeper.js");
        var name = CodingTaskItemBridge.manifestNameOf(src).orElseThrow();
        var roomObject = new org.wyrdsekai.common.model.RoomObject(
            "codex-floor01", name, "…", true, true);
        CodingTaskItemBridge.tryRegisterScriptedItem(roomObject, src);
        CodingItemRegistry.get().stamp(new CodingItemMetadata(
            roomObject.id(), GooseBackend.NAME, "task-floor", UUID.randomUUID(),
            "codex", name));

        var room = testKit.spawn(RoomActor.create(ROOM + "-floor"));
        org.wyrdsekai.core.room.RoomRegistry.get().register(ROOM + "-floor", room);
        var listener = testKit.<RoomNotification>createTestProbe();
        room.tell(new RoomCommand.Subscribe(listener.ref()));
        room.tell(new RoomCommand.ItemBridgeAction("bridge",
            new RoomCommand.ItemBridgeSubAction.AddObject(
                roomObject.id(), name, "…", true)));

        // Never taken — used where it stands, with arguments. A real replyTo, because
        // the actor answers on it and a null there takes the whole message down.
        var useReply = testKit.<RoomResponse>createTestProbe();
        room.tell(new RoomCommand.UseObject(
            PLAYER, name, "salt almanac", "en", useReply.ref()));

        var narrated = listener.fishForMessage(Duration.ofSeconds(15), "the item's answer",
            n -> n.event() instanceof WorldEvent.Said said && said.text() != null
                    && said.text().contains("A paragraph about")
                ? org.apache.pekko.actor.testkit.typed.javadsl.FishingOutcomes.complete()
                : org.apache.pekko.actor.testkit.typed.javadsl.FishingOutcomes.continueAndIgnore());
        assertThat(narrated)
            .as("a room-placed item must receive params.args and reach the real world")
            .isNotEmpty();
    }
}

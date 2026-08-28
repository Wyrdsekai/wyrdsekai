package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.LocalCommandRouter;
import org.wyrdsekai.core.agent.NamespaceHandler;
import org.wyrdsekai.core.coding.CodingItemMetadata;
import org.wyrdsekai.core.coding.CodingItemRegistry;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;
import org.wyrdsekai.core.item.ScriptedItemLoader;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verifies that {@code use codex-X
 * <verb>} typed in-world reaches {@link LocalCommandRouter} when the
 * RoomObject id is stamped in {@link CodingItemRegistry}.
 *
 * <p>Uses a recording fake handler (not a real backend) so the test
 * runs without Docker / OpenHands. The chain under test is exactly
 * the production one: {@code RoomActor.onUseObject → registry lookup
 * → LocalCommandRouter.execute → fake handler → response narration
 * via WorldEvent.Said}.</p>
 */
@Tag("integration")
class RoomActorCodingItemTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.room.RoomEvent" = jackson-json
              "org.wyrdsekai.core.room.RoomState" = jackson-json
              "org.wyrdsekai.core.room.RoomCommand" = jackson-json
              "org.wyrdsekai.core.room.RoomNotification" = jackson-json
              "org.wyrdsekai.core.room.RoomResponse" = jackson-json
            }
            """).withFallback(EventSourcedBehaviorTestKit.config()));

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> behaviorTestKit;
    private LocalCommandRouter router;
    private CodingItemRegistry itemRegistry;

    @BeforeEach
    void setUp() {
        var moderationService = new ModerationService();
        var sanctionEnforcer = new SanctionEnforcer(moderationService);
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(),
            RoomActor.create("coding-item-test", null, null, null, sanctionEnforcer));

        LocalCommandRouter.resetForTest();
        router = LocalCommandRouter.get();
        itemRegistry = CodingItemRegistry.get();
        itemRegistry.clear();
    }

    @AfterEach
    void tearDownEach() {
        itemRegistry.clear();
        LocalCommandRouter.resetForTest();
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    /** Recording handler — captures (entityId, verb, args, payload). */
    private static final class CapturingHandler implements NamespaceHandler {
        volatile String entityId;
        volatile String verb;
        volatile List<String> args;
        volatile Map<String, String> payload;
        final String narrationText;

        CapturingHandler(String narrationText) { this.narrationText = narrationText; }

        @Override
        public void dispatch(String entityId, String verb, List<String> args,
                             Map<String, String> payload,
                             Consumer<S2CMessage> respond) {
            this.entityId = entityId;
            this.verb = verb;
            this.args = args;
            this.payload = payload;
            respond.accept(new S2CMessage.Prose(0, "system",
                narrationText, List.of(), null, "normal", null));
        }
    }

    private void createRoomAndEnterPlayer(String playerId, String playerName) {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Test Room",
                "A room for coding-item tests.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom(playerId, playerName, "player", "north", ref));
    }

    private void placeCodingItem(String objectId, String name, String description) {
        // Bridge sends ItemBridgeAction(AddObject) to add the item.
        behaviorTestKit.runCommand(new RoomCommand.ItemBridgeAction(
            "system:test",
            new RoomCommand.ItemBridgeSubAction.AddObject(
                objectId, name, description, true)));
    }

    @Test
    void useCodingItem_routesThroughRouter_withDefaultExamineVerb() {
        createRoomAndEnterPlayer("player-1", "Alice");

        // Place a codex item and stamp the registry.
        var artifactId = UUID.randomUUID();
        var roomObjectId = "codex-deadbeef";
        placeCodingItem(roomObjectId, "codex", "A leather-bound codex.");
        itemRegistry.stamp(new CodingItemMetadata(
            roomObjectId, "openhands", "task-xyz", artifactId, "codex"));

        // Wire a recording handler under "openhands".
        var handler = new CapturingHandler("Codex deadbeef — 2 file(s)");
        router.register("openhands", handler);

        // Subscribe to room notifications so we capture the Said
        // narration emitted by the router response callback.
        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        // Use with NO target — should default to "examine".
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "codex",
                null, "en", ref));

        // UseObject replies Narrated (the ObjectUsed event reaches subscribers
        // via notifySubscribers; the response itself doesn't carry a snapshot).
        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(handler.verb).isEqualTo("examine");
        assertThat(handler.args).containsExactly(artifactId.toString());
        assertThat(handler.entityId).isEqualTo("player-1");

        // Drain notifications, find the Said(narrator, ...).
        var foundNarration = new AtomicReference<String>();
        for (int i = 0; i < 20 && foundNarration.get() == null; i++) {
            var msg = probe.receiveMessage(Duration.ofSeconds(2));
            if (msg.event() instanceof WorldEvent.Said said
                    && "narrator".equals(said.entityId())
                    && said.text().contains("Codex deadbeef")) {
                foundNarration.set(said.text());
            }
        }
        assertThat(foundNarration.get())
            .as("expected narrator Said with the handler's text")
            .isNotNull()
            .contains("Codex deadbeef");
    }

    @Test
    void useArtifactItem_runVerb_passesArtifactIdToHandler() {
        createRoomAndEnterPlayer("player-1", "Alice");

        var artifactId = UUID.randomUUID();
        var roomObjectId = "artifact-cafebabe";
        placeCodingItem(roomObjectId, "artifact", "A built artifact.");
        itemRegistry.stamp(new CodingItemMetadata(
            roomObjectId, "openhands", "task-xyz", artifactId, "artifact"));

        var handler = new CapturingHandler("hello, world");
        router.register("openhands", handler);

        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        // Use with target=run — verb should be "run".
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "artifact",
                "run", "en", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(handler.verb).isEqualTo("run");
        assertThat(handler.args).containsExactly(artifactId.toString());

        var foundNarration = new AtomicReference<String>();
        for (int i = 0; i < 20 && foundNarration.get() == null; i++) {
            var msg = probe.receiveMessage(Duration.ofSeconds(2));
            if (msg.event() instanceof WorldEvent.Said said
                    && "narrator".equals(said.entityId())
                    && said.text().contains("hello, world")) {
                foundNarration.set(said.text());
            }
        }
        assertThat(foundNarration.get())
            .as("hello, world should land as narrator narration")
            .isNotNull()
            .contains("hello, world");
    }

    @Test
    void useNonCodingObject_fallsThroughToNormalOnUse() {
        createRoomAndEnterPlayer("player-1", "Alice");

        // Add a regular non-coding object — no registry stamp.
        placeCodingItem("workbench", "workbench", "A workbench.");

        // Wire a handler that should NOT be invoked.
        var handler = new CapturingHandler("must not narrate");
        router.register("openhands", handler);

        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "workbench",
                null, "en", ref));

        // Handler must not have been called — registry has no entry.
        assertThat(handler.verb).isNull();
    }

    @Test
    void useCodingItem_handlerError_narratesErrorPrefix() {
        createRoomAndEnterPlayer("player-1", "Alice");

        var artifactId = UUID.randomUUID();
        var roomObjectId = "codex-feedface";
        placeCodingItem(roomObjectId, "codex", "Test codex.");
        itemRegistry.stamp(new CodingItemMetadata(
            roomObjectId, "openhands", "task-xyz", artifactId, "codex"));

        // Handler that emits an Error envelope.
        router.register("openhands",
            (eid, verb, args, payload, respond) ->
                respond.accept(new S2CMessage.Error(0, "boom",
                    "synthetic failure", null)));

        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "codex",
                "run", "en", ref));

        var foundError = new AtomicReference<String>();
        for (int i = 0; i < 20 && foundError.get() == null; i++) {
            var msg = probe.receiveMessage(Duration.ofSeconds(2));
            if (msg.event() instanceof WorldEvent.Said said
                    && said.text().contains("boom")) {
                foundError.set(said.text());
            }
        }
        assertThat(foundError.get())
            .as("error envelope should narrate as [code] message")
            .isNotNull()
            .contains("[boom]")
            .contains("synthetic failure");
    }

    /**
     * items-as-tools path — when the bridge
     * registers the artifact's GraalJS source with {@link
     * org.wyrdsekai.core.item.ScriptedItemLoader}, the metadata carries
     * a {@code scriptedItemId} and {@code use <id>} routes through
     * {@link org.wyrdsekai.scripting.sandbox.ItemScriptExecutor} —
     * NOT the legacy LocalCommandRouter.
     */
    @Test
    void useScriptedCodingItem_invokesItemScriptExecutor() throws Exception {
        createRoomAndEnterPlayer("player-1", "Alice");

        // Write a manifest-shaped item to a temp script dir + register
        // it with the loader (mirrors what CodingTaskItemBridge does
        // after an OpenHands task lands a `.js` in the workspace).
        var tmpDir = Files.createTempDirectory("wyrd-coding-item-test-");
        var scriptPath = tmpDir.resolve("hello_oracle.js");
        Files.writeString(scriptPath, """
            // Test item — proves the bridge → loader → executor chain.
            //  v1.5 D7: embodiment block is REQUIRED by
            // ScriptedItemLoader.register() (hot-reload path, no migration shim).
            exports.manifest = {
              name: "hello_oracle",
              version: "1.0.0",
              description: "Test item that returns a known summary.",
              author: "did:wyrd:test",
              capabilities: [],
              embodiment: {
                silent: false,
                emits: ["ambient_shift"],
                descriptor_template: "The oracle codex hums a moment; a summary settles into your mind."
              },
              commands: [ { label: "Consult the oracle", args: "" } ]
            };
            function invoke(params) {
              return {
                ok: true,
                summary: "ORACLE_SAYS: " + (params && params.query ? params.query : "default"),
                echoed: params || {}
              };
            }
            """);
        var loader = ScriptedItemLoader.get();
        var def = loader.register(scriptPath);
        assertThat(def).as("loader should accept the manifest").isPresent();

        // Place the codex RoomObject + stamp the registry with the
        // scripted-item link.
        var artifactId = UUID.randomUUID();
        var roomObjectId = "codex-oraclebeef";
        placeCodingItem(roomObjectId, "codex", "An oracular codex.");
        itemRegistry.stamp(new CodingItemMetadata(
            roomObjectId, "openhands", "task-xyz", artifactId, "codex",
            "hello_oracle"));

        // Sentinel: register a router handler that should NOT be hit
        // when scriptedItemId is present.
        var handler = new CapturingHandler("MUST_NOT_FIRE");
        router.register("openhands", handler);

        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "codex",
                "consult the stars", "en", ref));

        // Find the narrator's Said with the script's summary text.
        var found = new AtomicReference<String>();
        for (int i = 0; i < 20 && found.get() == null; i++) {
            var msg = probe.receiveMessage(Duration.ofSeconds(2));
            if (msg.event() instanceof WorldEvent.Said said
                    && "narrator".equals(said.entityId())
                    && said.text().startsWith("ORACLE_SAYS:")) {
                found.set(said.text());
            }
        }
        assertThat(found.get())
            .as("scripted item's summary should narrate as Said(narrator, ...)")
            .isNotNull()
            .contains("ORACLE_SAYS:")
            .contains("consult the stars");

        // Router handler must NOT have been invoked — the scripted
        // path bypassed it.
        assertThat(handler.verb)
            .as("LocalCommandRouter must NOT be hit when scriptedItemId is set")
            .isNull();

        // Cleanup
        loader.forget("hello_oracle");
        Files.deleteIfExists(scriptPath);
        Files.deleteIfExists(tmpDir);
    }

    /**
     * Symmetric check: when the loader has been evicted (or the script
     * was never registered), the router fallback fires. Proves the
     * fallback isn't dead code.
     */
    @Test
    void useScriptedCodingItem_loaderEvicted_fallsBackToRouter() {
        createRoomAndEnterPlayer("player-1", "Alice");

        var artifactId = UUID.randomUUID();
        var roomObjectId = "codex-ghost";
        placeCodingItem(roomObjectId, "codex", "A ghost codex.");
        // Metadata says scripted-item is "ghost_item" but the loader
        // never knew about it — should fall back to the router.
        itemRegistry.stamp(new CodingItemMetadata(
            roomObjectId, "openhands", "task-xyz", artifactId, "codex",
            "ghost_item_does_not_exist"));

        var handler = new CapturingHandler("router fallback fired");
        router.register("openhands", handler);

        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "codex",
                null, "en", ref));

        // Router handler MUST have been invoked since the loader had
        // nothing.
        var deadline = System.currentTimeMillis() + 3000;
        while (handler.verb == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        assertThat(handler.verb)
            .as("loader-evicted scripted item should fall back to router")
            .isEqualTo("examine");
    }
}

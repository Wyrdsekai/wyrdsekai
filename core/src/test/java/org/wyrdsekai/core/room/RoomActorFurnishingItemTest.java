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
import org.wyrdsekai.core.coding.CodingItemRegistry;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;
import org.wyrdsekai.core.item.ItemProviderRegistry;
import org.wyrdsekai.core.item.ScriptedItemLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;

/**
 * Furnishing-as-scripted-item: {@code use <furnishing>} on a RoomObject whose
 * id (or normalized display name) matches a loaded {@code scripts/items/*.js}
 * def invokes that item's {@code invoke()} — real behavior, not just the
 * generic "used xxx" narration — through {@link ItemProviderRegistry}'s
 * provider when one is registered.
 *
 * <p>Chain under test: {@code RoomActor.onUseObject → resolveFurnishingItem
 * (id, then normalized name) → invokeScriptedFurnishing → ItemScriptExecutor
 * → narrateScriptResult → WorldEvent.Said}. Mirrors
 * {@link RoomActorCodingItemTest}'s harness.</p>
 */
@Tag("integration")
class RoomActorFurnishingItemTest {

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
    private final List<String> loadedItemIds = new ArrayList<>();
    private final List<Path> tempPaths = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var moderationService = new ModerationService();
        var sanctionEnforcer = new SanctionEnforcer(moderationService);
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(),
            RoomActor.create("furnishing-item-test", null, null, null, sanctionEnforcer));
        CodingItemRegistry.get().clear();
        ItemProviderRegistry.resetForTests();
    }

    @AfterEach
    void tearDownEach() throws Exception {
        var loader = ScriptedItemLoader.get();
        for (var id : loadedItemIds) loader.forget(id);
        loadedItemIds.clear();
        for (var p : tempPaths) Files.deleteIfExists(p);
        tempPaths.clear();
        CodingItemRegistry.get().clear();
        ItemProviderRegistry.resetForTests();
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void createRoomAndEnterPlayer(String playerId, String playerName) {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Test Study",
                "A room for furnishing-item tests.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom(playerId, playerName, "player", "north", ref));
    }

    private void placeFurnishing(String objectId, String name, String description) {
        behaviorTestKit.runCommand(new RoomCommand.ItemBridgeAction(
            "system:test",
            new RoomCommand.ItemBridgeSubAction.AddObject(
                objectId, name, description, false)));
    }

    /** Register a minimal manifest-valid item that echoes its params. */
    private void registerItem(String itemName, String resultKey) throws Exception {
        var tmpDir = Files.createTempDirectory("wyrd-furnishing-item-test-");
        var scriptPath = tmpDir.resolve(itemName + ".js");
        Files.writeString(scriptPath, """
            exports.manifest = {
              name: "%s",
              version: "1.0.0",
              description: "Test furnishing item.",
              author: "did:wyrd:test",
              capabilities: [],
              embodiment: {
                silent: false,
                emits: ["ambient_shift"],
                descriptor_template: "The %s stirs."
              },
              commands: [ { label: "Check status", args: "status" } ]
            };
            function invoke(params) {
              var r = { ok: true };
              r["%s"] = "FURNISHING_RAN: args=" + (params && params.args !== undefined ? params.args : "<none>")
                + " entity=" + (params && params.entityId ? params.entityId : "<none>");
              return r;
            }
            """.formatted(itemName, itemName, resultKey));
        var def = ScriptedItemLoader.get().register(scriptPath);
        assertThat(def).as("loader should accept " + itemName).isPresent();
        loadedItemIds.add(itemName);
        tempPaths.add(scriptPath);
        tempPaths.add(tmpDir);
    }

    private String awaitNarration(
            TestProbe<RoomNotification> probe,
            String marker) {
        var found = new AtomicReference<String>();
        for (int i = 0; i < 20 && found.get() == null; i++) {
            var msg = probe.receiveMessage(Duration.ofSeconds(2));
            if (msg.event() instanceof WorldEvent.Said said
                    && "narrator".equals(said.entityId())
                    && said.text().contains(marker)) {
                found.set(said.text());
            }
        }
        return found.get();
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void useFurnishing_matchingScriptedItemById_invokesScript() throws Exception {
        createRoomAndEnterPlayer("player-1", "Alice");
        registerItem("test_console", "summary");
        // Object id == manifest.name — the canonical linkage.
        placeFurnishing("test_console", "test console", "A console for testing.");

        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "test console",
                "status", "en", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        var narration = awaitNarration(probe, "FURNISHING_RAN");
        assertThat(narration)
            .as("furnishing's invoke() output should narrate")
            .isNotNull()
            .contains("args=status")
            .contains("entity=player-1");
    }

    @Test
    void useFurnishing_argsContainingOn_reachTheItem() throws Exception {
        // CommandParser splits `use X on Y` on " on ", so `use maintenance dial
        // mode on <reason>` arrived as object="maintenance dial mode",
        // target="<reason>" — the item never saw "mode on" (second-node 2026-07-04).
        // The full-phrase reconstruction must re-join it so multi-word args
        // containing the word "on" reach the script intact.
        createRoomAndEnterPlayer("player-1", "Alice");
        registerItem("mode_dial", "summary");
        placeFurnishing("mode_dial", "mode dial", "A dial.");

        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        // Mimic CommandParser's split of "use mode dial set on loud":
        // object="mode dial set", target="loud".
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "mode dial set",
                "loud", "en", ref));

        var narration = awaitNarration(probe, "FURNISHING_RAN");
        assertThat(narration)
            .as("the full 'set on loud' args should reach the item")
            .isNotNull()
            .contains("args=set on loud");
    }

    @Test
    void useFurnishing_matchingByNormalizedName_invokesScript() throws Exception {
        createRoomAndEnterPlayer("player-1", "Alice");
        registerItem("roster_ledger", "narrative");
        // Object id does NOT match; the normalized display name does
        // ("roster ledger" → "roster_ledger") — the friendly-name linkage.
        placeFurnishing("study-roster-test", "roster ledger", "A ledger of members.");

        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "roster ledger",
                null, "en", ref));

        // Also proves narrateScriptResult renders the `narrative` key
        // (recipes_console convention), not just `summary`.
        var narration = awaitNarration(probe, "FURNISHING_RAN");
        assertThat(narration)
            .as("normalized-name match should invoke; `narrative` key should render")
            .isNotNull()
            .contains("args=")
            .contains("entity=player-1");
    }

    @Test
    void useFurnishing_noMatchingItem_fallsThroughWithoutScriptNarration() throws Exception {
        createRoomAndEnterPlayer("player-1", "Alice");
        placeFurnishing("plain-lamp", "brass lamp", "Just a lamp.");

        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "brass lamp",
                null, "en", ref));

        // Pre-existing behavior intact: Narrated reply, generic ObjectUsed,
        // no script narration.
        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        var sawObjectUsed = false;
        for (int i = 0; i < 10; i++) {
            var msg = probe.receiveMessage(Duration.ofSeconds(2));
            if (msg.event() instanceof WorldEvent.ObjectUsed) { sawObjectUsed = true; break; }
        }
        assertThat(sawObjectUsed).as("generic ObjectUsed still emitted").isTrue();
    }

    @Test
    void useFurnishing_consultsProviderRegistryForActingEntity() throws Exception {
        createRoomAndEnterPlayer("player-7", "Bob");
        registerItem("registry_probe", "summary");
        placeFurnishing("registry_probe", "registry probe", "Probes the provider registry.");

        // Factory records who it was asked for; returning null exercises the
        // stub fallback (invoke must still succeed).
        var askedFor = new AtomicReference<String>();
        ItemProviderRegistry.register(entityId -> {
            askedFor.set(entityId);
            return null;
        });

        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-7", "registry probe",
                null, "en", ref));

        var narration = awaitNarration(probe, "FURNISHING_RAN");
        assertThat(narration).as("invoke still runs on stub fallback").isNotNull();
        assertThat(askedFor.get())
            .as("provider factory consulted with the acting entity")
            .isEqualTo("player-7");
    }
}

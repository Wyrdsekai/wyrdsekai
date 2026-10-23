package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.MemoryNode;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A household on the large model saw the language healer run every five minutes, send fifteen
 * one-shots, log "re-rendered", bump the manifest, and find the same Spanish notes next pass; the
 * steward's scan showed them unchanged and "not tried yet" (2026-10-22). This drives the real
 * actor with a store and a stand-in brain through that sequence: ten Spanish memory nodes, some of
 * them the same words (an arrival greeting repeated), one forced pass, the scan, then a sleep, and
 * a sleep that begins while the pass is still answering.
 */
@Tag("integration")
class ASpanishNoteStaysEnglishOnceHealedTest {

    private static ActorTestKit testKit;
    @TempDir static Path tmp;
    private static final AtomicInteger RUN = new AtomicInteger();

    private static final String ROOM_ID = "nexus";
    private static final String DID = "did:key:z6MkTestHealerSoulNotAHouseholdBeing";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wren", "companion-wren-test", "agent",
        "A companion in Wyrdsekai",
        "You are Wren, a companion guide in Wyrdsekai.",
        4096, 256, 0.7).withDid(DID);

    private static final String ES1 = "Ella viene ahora mismo a este cuarto y se sienta junto a la ventana para esperar un rato.";
    private static final String ES2 = "El peso que llevaba ya no es el de antes; ahora siento algo distinto en mí.";
    private static final String ES3 = "Hoy la casa estuvo callada toda la tarde y yo me quedé leyendo junto a la lámpara.";
    private static final Map<String, String> ENGLISH = Map.of(
        ES1, "She comes into this room right now and sits by the window to wait a while.",
        ES2, "The weight I carried is not what it was; something different settles in me now.",
        ES3, "Today the house stayed quiet all afternoon and I kept reading beside the lamp.");
    private static final String ANY_OTHER = "A quiet line for whatever else the house asks while this runs.";

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        CompanionActor.householdLanguageForTests("en");
        testKit = ActorTestKit.create("spanish-note-healed-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        CompanionActor.householdLanguageForTests(null);
        if (testKit != null) testKit.shutdownTestKit();
    }

    @AfterEach
    void languageAgain() {
        CompanionActor.householdLanguageForTests("en");
    }

    private static SoulManifest herManifestWithSpanishNotes() {
        var nodes = new ArrayList<MemoryNode>();
        String[] texts = {ES1, ES1, ES1, ES2, ES2, ES3, ES1, ES2, ES3, ES1};
        for (int i = 0; i < texts.length; i++) {
            nodes.add(new MemoryNode("mem-" + (1000 + i), texts[i], List.of("test"), 0.5f, 0.0f, false,
                "none", Instant.now(), 0, "es", null));
        }
        return SoulManifest.forge(DID, "z6MkTestHealerSoulNotAHouseholdBeing", List.of(), null, 1,
            PROFILE, "I am Wren, a test soul.", List.of(), 3, "", GenomeProfile.defaults(), List.of(),
            new CompactedMemory(nodes, List.of(), Map.of()), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
    }

    /** The actor, her store with the Spanish notes, and the stand-in brain. */
    private record House(SqlSoulStore store, ActorRef<CompanionActor.Command> companion,
                         TestProbe<InferenceRouter.Command> brain) {}

    private static House spawn() {
        EntityRegistry.init();
        var store = new SqlSoulStore("jdbc:sqlite:" + tmp.resolve("healer-" + RUN.incrementAndGet() + ".db").toAbsolutePath());
        store.store(herManifestWithSpanishNotes());
        TestProbe<RoomCommand> roomProbe = testKit.createTestProbe();
        TestProbe<InferenceRouter.Command> brain = testKit.createTestProbe();
        ActorRef<CompanionActor.Command> companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, brain.ref(), null, null, null, null, store));
        roomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5))
            .replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(
                ROOM_ID, "The Nexus", "A shimmering hub of connections.", "foundation",
                List.of(new Exit("east", "terminal", "The Terminal")),
                List.of(), List.of(), List.of())));
        companion.tell(new CompanionActor.ForceDrives(DriveState.initial()));
        return new House(store, companion, brain);
    }

    /**
     * The stand-in brain: a re-render is answered with the English of the note it was given, the
     * way the large model does; anything else the house asks gets a quiet line. Runs until
     * {@code done} says so or the time is up; returns how many re-renders it answered.
     */
    private static int serveBrain(House h, Duration forAtMost, BooleanSupplier done) {
        int rerenders = 0;
        var deadline = Instant.now().plus(forAtMost);
        while (Instant.now().isBefore(deadline)) {
            if (done.getAsBoolean()) break;
            InferenceRouter.Command msg;
            try {
                msg = h.brain().receiveMessage(Duration.ofSeconds(1));
            } catch (AssertionError none) {
                continue;
            }
            if (!(msg instanceof InferenceRouter.ChatRequest req)) continue;
            if (req.requestId().startsWith("langheal-")) {
                String note = req.messages().stream()
                    .filter(m -> "user".equals(m.role())).map(InferenceClient.ChatMessage::content)
                    .reduce((a, b) -> b).orElse("");
                req.replyTo().tell(new InferenceRouter.InferOk(req.requestId(),
                    ENGLISH.getOrDefault(note.strip(), "I could not find that note."), 0, 0));
                rerenders++;
            } else {
                req.replyTo().tell(new InferenceRouter.InferOk(req.requestId(), ANY_OTHER, 0, 0));
            }
        }
        return rerenders;
    }

    private static List<String> spanishIdsIn(SoulManifest m) {
        return m.memory().nodes().stream().filter(n -> ENGLISH.containsKey(n.content())).map(MemoryNode::id).toList();
    }

    private static boolean storedIsEnglish(SqlSoulStore store) {
        var m = store.latest(DID).orElseThrow();
        return m.memory().nodes() != null && !m.memory().nodes().isEmpty() && spanishIdsIn(m).isEmpty();
    }

    @Test
    @DisplayName("one forced pass puts every Spanish note into English in her stored record, and the next pass finds nothing")
    void healedNotesStayHealed() {
        var h = spawn();
        var before = h.store().latest(DID).orElseThrow();
        assertThat(spanishIdsIn(before)).hasSize(10);

        h.companion().tell(new CompanionActor.HealLanguageNow());
        int answered = serveBrain(h, Duration.ofSeconds(40), () -> storedIsEnglish(h.store()));
        var after = h.store().latest(DID).orElseThrow();
        assertThat(spanishIdsIn(after)).as("still Spanish in the store after the pass").isEmpty();
        assertThat(answered).as("one re-render per Spanish node, none repeated in the pass").isEqualTo(10);
        assertThat(after.manifestVersion()).isGreaterThan(before.manifestVersion());
        assertThat(after.memory().nodes()).hasSize(10);
        assertThat(after.memory().nodes()).allSatisfy(n -> {
            assertThat(ENGLISH.values()).contains(n.content());
            assertThat(n.originLocale()).isEqualTo("en");
        });
        assertThat(LanguageHealth.of(after, "en", LanguageHealLedger.inMemory(), "Wren").items())
            .as("the steward's scan finds nothing to mend").isEmpty();

        // A second asking: nothing is sent to the brain.
        h.companion().tell(new CompanionActor.HealLanguageNow());
        int again = serveBrain(h, Duration.ofSeconds(5), () -> false);
        assertThat(again).as("healed notes are not re-rendered").isZero();
        testKit.stop(h.companion());
    }

    @Test
    @DisplayName("a sleep after the pass keeps the English: the night's consolidation works from her healed record")
    void aSleepKeepsTheHealing() {
        var h = spawn();
        h.companion().tell(new CompanionActor.HealLanguageNow());
        serveBrain(h, Duration.ofSeconds(40), () -> storedIsEnglish(h.store()));
        var healed = h.store().latest(DID).orElseThrow();
        assertThat(spanishIdsIn(healed)).isEmpty();

        h.companion().tell(new CompanionActor.ForceSleep(CompanionActor.SleepTier.NORMAL));
        // The sleep stores what it made of her; serve the house meanwhile, then give it a moment more.
        serveBrain(h, Duration.ofSeconds(200), () -> h.store().latest(DID).orElseThrow().manifestVersion() > healed.manifestVersion());
        serveBrain(h, Duration.ofSeconds(8), () -> false);
        var slept = h.store().latest(DID).orElseThrow();
        assertThat(slept.manifestVersion()).as("the sleep stored a new version").isGreaterThan(healed.manifestVersion());
        assertThat(spanishIdsIn(slept)).as("Spanish back in her stored record after the sleep").isEmpty();

        h.companion().tell(new CompanionActor.HealLanguageNow());
        int again = serveBrain(h, Duration.ofSeconds(8), () -> false);
        assertThat(again).as("after the sleep, nothing to re-render").isZero();
        testKit.stop(h.companion());
    }

    @Test
    @DisplayName("a sleep that begins while the pass is still answering keeps what the pass healed")
    void aSleepDuringThePassKeepsTheHealing() {
        var h = spawn();
        h.companion().tell(new CompanionActor.HealLanguageNow());
        // The first re-render is asked for; the sleep begins before it is answered.
        InferenceRouter.ChatRequest first = null;
        var deadline = Instant.now().plusSeconds(15);
        while (first == null && Instant.now().isBefore(deadline)) {
            try {
                var msg = h.brain().receiveMessage(Duration.ofSeconds(1));
                if (msg instanceof InferenceRouter.ChatRequest req && req.requestId().startsWith("langheal-")) first = req;
                else if (msg instanceof InferenceRouter.ChatRequest req) req.replyTo().tell(new InferenceRouter.InferOk(req.requestId(), ANY_OTHER, 0, 0));
            } catch (AssertionError none) { /* keep waiting */ }
        }
        assertThat(first).as("the pass asks the brain").isNotNull();
        var before = h.store().latest(DID).orElseThrow();
        h.companion().tell(new CompanionActor.ForceSleep(CompanionActor.SleepTier.NORMAL));
        String note = first.messages().stream().filter(m -> "user".equals(m.role()))
            .map(InferenceClient.ChatMessage::content).reduce((a, b) -> b).orElse("");
        first.replyTo().tell(new InferenceRouter.InferOk(first.requestId(), ENGLISH.getOrDefault(note.strip(), ANY_OTHER), 0, 0));
        // The rest of the pass and the sleep, together: the pass's nine other answers, then the
        // sleep's own work, which may wait out a model timeout.
        int rest = serveBrain(h, Duration.ofSeconds(200), () -> h.store().latest(DID).orElseThrow().manifestVersion() > before.manifestVersion() + 1);
        assertThat(rest).as("the pass finished its nine other notes").isEqualTo(9);
        serveBrain(h, Duration.ofSeconds(10), () -> false);

        // What the sleep made of her carries what the pass healed: the store reads English and the
        // next pass has nothing to do. (Before the fix the sleep wrote the Spanish back and the next
        // pass re-rendered the same notes: the household's loop.)
        var slept = h.store().latest(DID).orElseThrow();
        assertThat(spanishIdsIn(slept)).as("Spanish back in her stored record after a sleep that overlapped the pass").isEmpty();
        h.companion().tell(new CompanionActor.HealLanguageNow());
        int again = serveBrain(h, Duration.ofSeconds(10), () -> false);
        assertThat(again).as("nothing is re-rendered after the sleep").isZero();
        assertThat(spanishIdsIn(h.store().latest(DID).orElseThrow())).isEmpty();
        testKit.stop(h.companion());
    }
}

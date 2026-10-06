package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.inference.ApiProvider;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.MemoryNode;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;
import org.wyrdsekai.e2e.infra.StandInBrain;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The language healer on a live node, with a brain the test controls on the wire (2026-10-05, after
 * a household's 0.5.4 report: a CPU voice lane, every rewrite past its 12 s wait, nothing cancelled,
 * the next rewrite queued behind the dead one, the brain busy around the clock). A companion whose
 * soul carries Spanish notes is booted here; then, in order: a rewrite the brain never answers is
 * taken back off the wire at 90 s and the pass stops; a brain that translates has the notes mended
 * in the soul; a brain that hands a note back unchanged is not believed.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TheLanguageHealerOnALiveLaneE2ETest {

    private static final String ENTITY = "companion-wyrd";
    private static final String NAME = "Wyrd";
    private static final List<String> SPANISH = List.of(
        "El peso que llevaba ya no es el de antes; ahora siento algo distinto en mí.",
        "Hoy conté 3 lámparas en la biblioteca y ninguna estaba encendida.",
        "Me quedé en la puerta sin decir nada, y eso también fue una decisión.");
    private static final String SPANISH_FRAGMENT =
        "Cuando él se fue, la casa se quedó más grande de lo que era.";
    private static final Map<String, String> ENGLISH = Map.of(
        SPANISH.get(0), "The weight I carried is not what it was; something different settles in me now.",
        SPANISH.get(1), "Today I counted 3 lamps in the library and none of them was lit.",
        SPANISH.get(2), "I stood in the doorway without a word, and that too was a decision.",
        SPANISH_FRAGMENT, "When he left, the house stayed larger than it had been.");

    private static StandInBrain brain;
    private static TestServerBootstrap server;
    private static SqlSoulStore store;
    private static String did;

    @BeforeAll
    static void setUp() throws Exception {
        CompanionActor.householdLanguageForTests("en");   // as a configured node names it (WYRDSEKAI_LOCALE)
        CompanionActor.langHealQuietForTests(Duration.ofSeconds(10));
        brain = new StandInBrain(StandInBrain.Mode.HOLD);
        var client = new InferenceClient(brain.url(), null, Duration.ofSeconds(240), new ApiProvider.OpenAI("llama-server"));
        server = new TestServerBootstrap(List.of(new InferenceBackend.LlamaServer("stand-in", client, 1, List.of("test-model"), null)));
        server.start();
        store = new SqlSoulStore(server.jdbcUrl());
        until(() -> wyrd() != null, Duration.ofSeconds(60), "the companion's soul is in the store");
        did = wyrd().did();
    }

    @AfterAll
    static void tearDown() {
        CompanionActor.householdLanguageForTests(null);
        CompanionActor.langHealQuietForTests(null);
        if (server != null) server.stop();
        if (brain != null) brain.close();
    }

    @Order(1)
    @Test
    void a_rewrite_the_brain_never_answers_is_taken_back_and_the_pass_stops() throws Exception {
        seedSpanish();
        until(() -> brain.healCalls().size() >= 1, Duration.ofSeconds(150), "the healer asks for one rewrite on a quiet lane");
        var first = brain.healCalls().getFirst();
        assertThat(first.note).as("it is one of the Spanish notes, sent as it is").isIn(SPANISH.get(0), SPANISH.get(1), SPANISH.get(2), SPANISH_FRAGMENT);

        until(() -> first.closedAfterMs >= 0, Duration.ofSeconds(130), "the brain sees the line closed");
        assertThat(first.closedAfterMs).as("taken back at the 90 s mark, not at the brain's leisure").isBetween(80_000L, 118_000L);
        until(() -> InferenceRouter.snapshot().inFlight() == 0 && InferenceRouter.snapshot().queued() == 0,
            Duration.ofSeconds(20), "the slot is free again");

        // The pass stopped at the first non-answer: no second note is tried for the rest of the window.
        Thread.sleep(45_000);
        assertThat(brain.healCalls()).as("one rewrite in all; the next pass waits").hasSize(1);
        assertThat(wyrd().memory().nodes().stream().map(MemoryNode::content).toList())
            .as("nothing was rewritten").containsAll(SPANISH);

        // A person who speaks now is answered: the line is hers again.
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(30));
            ws.sendSay("nexus", "tell " + NAME.toLowerCase() + " are you there?");
            var reply = ws.waitForMessage(m -> {
                var type = m.path("type").asText("");
                var speaker = m.path("speaker").asText("");
                return ("prose".equals(type) || "emote".equals(type)) && speaker.toLowerCase().contains(NAME.toLowerCase());
            }, Duration.ofSeconds(90));
            assertThat(reply).as("a person's turn is answered after the stuck rewrite was taken back").isNotNull();
        }
    }

    @Order(2)
    @Test
    void a_brain_that_translates_has_the_notes_mended_in_the_soul() throws Exception {
        brain.mode(StandInBrain.Mode.TRANSLATE);
        brain.translations(ENGLISH);
        server.respawnCompanion();   // a fresh actor: no backoff, no pass behind her
        Thread.sleep(2_000);
        seedSpanish();
        int before = brain.healCalls().size();
        until(() -> {
            var m = wyrd();
            var contents = m.memory().nodes().stream().map(MemoryNode::content).toList();
            return contents.containsAll(List.of(ENGLISH.get(SPANISH.get(0)), ENGLISH.get(SPANISH.get(1)), ENGLISH.get(SPANISH.get(2))))
                && m.soulFragments().stream().anyMatch(f -> "heal-f1-lang-restored".equals(f.id()) && ENGLISH.get(SPANISH_FRAGMENT).equals(f.text()));
        }, Duration.ofSeconds(180), "the three notes and the fragment are stored in English");
        var healed = wyrd();
        assertThat(healed.memory().nodes().stream().filter(n -> n.id().startsWith("heal-n")).map(MemoryNode::originLocale))
            .as("the mended notes are marked as the household's language").containsOnly("en");
        assertThat(brain.healCalls().size() - before).as("one rewrite per note, serially").isEqualTo(4);
        assertThat(healed.memory().nodes().stream().map(MemoryNode::content).toList()).doesNotContainAnyElementsOf(SPANISH);
    }

    @Order(3)
    @Test
    void a_brain_that_hands_a_note_back_unchanged_is_not_believed() throws Exception {
        brain.mode(StandInBrain.Mode.ECHO);
        server.respawnCompanion();
        Thread.sleep(2_000);
        seedSpanish();
        int before = brain.healCalls().size();
        until(() -> brain.healCalls().size() - before >= 4, Duration.ofSeconds(150), "every note is tried once in the pass");
        Thread.sleep(10_000);
        var m = wyrd();
        assertThat(m.memory().nodes().stream().map(MemoryNode::content).toList()).as("an echo is no rewrite").containsAll(SPANISH);
        assertThat(m.soulFragments().stream().map(SoulFragment::id)).doesNotContain("heal-f1-lang-restored");
        assertThat(brain.healCalls().size() - before).as("a strike does not stop the pass, and nothing is retried inside it").isEqualTo(4);
    }

    @Order(4)
    @Test
    void the_steward_can_have_the_notes_mended_now_without_waiting_for_a_quiet_lane() throws Exception {
        brain.mode(StandInBrain.Mode.TRANSLATE);
        brain.translations(ENGLISH);
        CompanionActor.langHealQuietForTests(Duration.ofHours(1));   // the periodic pass cannot run in this test
        try {
            server.respawnCompanion();
            Thread.sleep(2_000);
            seedSpanish();
            Thread.sleep(15_000);
            assertThat(wyrd().memory().nodes().stream().map(MemoryNode::content).toList())
                .as("nothing happens on its own while the lane is not quiet").containsAll(SPANISH);

            var http = java.net.http.HttpClient.newHttpClient();
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.baseUrl() + "/api/soul/" + NAME.toLowerCase() + "/language/heal"))
                .header("Authorization", "Bearer " + TestServerBootstrap.operatorToken())
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).as(resp.body()).isEqualTo(202);
            assertThat(resp.body()).contains("\"memoryNodes\":3");

            until(() -> {
                var m = wyrd();
                return m.memory().nodes().stream().map(MemoryNode::content).toList()
                    .containsAll(List.of(ENGLISH.get(SPANISH.get(0)), ENGLISH.get(SPANISH.get(1)), ENGLISH.get(SPANISH.get(2))))
                    && m.soulFragments().stream().anyMatch(f -> "heal-f1-lang-restored".equals(f.id()));
            }, Duration.ofSeconds(90), "asked for now, the notes are mended without a quiet lane");
        } finally {
            CompanionActor.langHealQuietForTests(Duration.ofSeconds(10));
        }
    }

    /**
     * A node whose household language was never set (a terminal install sets neither key) may be a
     * Spanish household: its Spanish notes are left as they are until a person's own app reports the
     * household's language, and then they are mended into it. Reading only one key would have turned
     * such a companion to English and had her Spanish translated (caught before 0.5.5 shipped).
     */
    @Order(5)
    @Test
    void a_household_whose_language_was_never_set_is_left_as_it_is_until_a_persons_app_names_it() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.wyrdsekai.core.agent.LanguageHealth.configuredHouseholdLanguage() == null,
            "this machine sets a household language in its environment");
        brain.mode(StandInBrain.Mode.TRANSLATE);
        brain.translations(ENGLISH);
        CompanionActor.householdLanguageForTests(null);
        try {
            server.respawnCompanion();
            Thread.sleep(2_000);
            seedSpanish();
            int before = brain.healCalls().size();
            Thread.sleep(35_000);   // three quiet windows
            assertThat(brain.healCalls().size() - before).as("no household language yet, so nothing is in the wrong one").isZero();
            assertThat(wyrd().memory().nodes().stream().map(MemoryNode::content).toList()).containsAll(SPANISH);

            try (var ws = TestWebSocketClient.connect(server.baseUrl(), null, "en")) {
                ws.waitForRoomState(Duration.ofSeconds(30));
            }
            until(() -> wyrd().memory().nodes().stream().map(MemoryNode::content).toList()
                    .containsAll(List.of(ENGLISH.get(SPANISH.get(0)), ENGLISH.get(SPANISH.get(1)), ENGLISH.get(SPANISH.get(2)))),
                Duration.ofSeconds(150), "a person's app named the household's language, and the notes are mended into it");
        } finally {
            CompanionActor.householdLanguageForTests("en");
        }
    }

    // ── helpers ──

    private static SoulManifest wyrd() {
        return store.listLatest().stream()
            .filter(m -> m.profile() != null && NAME.equalsIgnoreCase(m.profile().name()))
            .findFirst().orElse(null);
    }

    /** Put three Spanish memory nodes and one Spanish episodic fragment into her stored soul, and have her adopt it. */
    private static void seedSpanish() throws Exception {
        var m = wyrd();
        var nodes = new ArrayList<MemoryNode>();
        if (m.memory() != null && m.memory().nodes() != null) {
            for (var n : m.memory().nodes()) if (!n.id().startsWith("heal-n")) nodes.add(n);
        }
        for (int i = 0; i < SPANISH.size(); i++) {
            nodes.add(new MemoryNode("heal-n" + (i + 1), SPANISH.get(i), List.of("nota"), 0.6f, 0.2f, false, "none", Instant.now(), 0, "es"));
        }
        var links = m.memory() != null && m.memory().links() != null ? m.memory().links() : List.<CompactedMemory.MemoryLink>of();
        var weights = m.memory() != null && m.memory().topicWeights() != null ? m.memory().topicWeights() : Map.<String, Float>of();
        var frags = new ArrayList<SoulFragment>();
        if (m.soulFragments() != null) for (var f : m.soulFragments()) if (!f.id().startsWith("heal-f1")) frags.add(f);
        frags.add(new SoulFragment("heal-f1", "episodic", "scene-heal", SPANISH_FRAGMENT, null, null, false, 0.5f, 0,
            Instant.now(), null, null, null, null, FragmentKind.EPISODIC, "scene-heal"));
        var seeded = m.withMemory(new CompactedMemory(nodes, links, weights)).withFragments(frags).bumpedVersion();
        store.store(seeded);
        var ref = ZoneGuardian.getCompanionRef(null, ENTITY);
        assertThat(ref).as("the companion is running").isNotNull();
        ref.tell(new CompanionActor.AdoptStoredSoulShape());
        until(() -> { var now = wyrd(); return now != null && now.manifestVersion() >= seeded.manifestVersion(); },
            Duration.ofSeconds(10), "the seeded soul is the latest");
    }

    private static void until(BooleanSupplier ok, Duration limit, String what) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (ok.getAsBoolean()) return;
            Thread.sleep(500);
        }
        assertThat(ok.getAsBoolean()).as(what).isTrue();
    }
}

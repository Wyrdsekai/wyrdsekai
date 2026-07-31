package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.interiority.ChronicleEntry;
import org.wyrdsekai.core.agent.interiority.ChronicleEntryStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.story.ArcRegistry;
import org.wyrdsekai.core.story.Beat;
import org.wyrdsekai.core.story.BeatTrigger;
import org.wyrdsekai.core.story.Scene;
import org.wyrdsekai.core.story.SceneKind;
import org.wyrdsekai.core.story.StoryService;
import org.wyrdsekai.core.story.StoryStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 / #1057 — integration: with real WantStore +
 * StoryService + ChronicleEntryStore populated, projector pulls the actual
 * data and composer produces a grounded answer that names specific wants
 * + recent SOLITUDE history + open chronicle threads.
 *
 * <p>This is the deterministic verification that the "drives → wants →
 * orientation → action → future-tense" chain is wired to real stores
 * rather than to corpus-style register exemplars. The honest answer the
 * agent gives is provably derived from what's in the database; nothing
 * is performed.</p>
 */
class OrientationProjectorIntegrationTest {

    private static final String AGENT = "did:key:zProjIntegrationAgent";
    private static final String ROOM = "room-hearth";

    @TempDir Path tmp;

    @Test
    void projectorAssemblesFromAllThreeRealStoresAndComposerNamesThem() throws Exception {
        // 1. WantStore with three real wants (one DEEPENED, two ACTIVE)
        var jdbc = SchemaInitializer.initialize(tmp.resolve("test.db"));
        var wants = new WantStore(jdbc);
        wants.deleteAll(AGENT);
        var w1 = Want.active(AGENT, "revisit the Yourcenar fragment",
            "{\"Curiosity\": 0.8}", 0.85, null);
        wants.upsert(w1);
        // visit twice more to push to DEEPENED.
        wants.upsert(w1.visited().visited());
        wants.upsert(Want.active(AGENT, "follow the slow rain question",
            null, 0.6, null));
        wants.upsert(Want.active(AGENT, "tidy the Forge queue",
            null, 0.4, null));

        // 2. ChronicleEntryStore with one recent thread
        var chronicle = new ChronicleEntryStore(jdbc);
        chronicle.append(new ChronicleEntry(
            AGENT, Instant.now().minusSeconds(3600),
            ChronicleEntry.Kind.NOTE,
            "Slow noticing about the rain on the study window. Returned to it twice.",
            Map.of()));

        // 3. StoryService — write a pre-rendered SOLITUDE scene directly to
        // the store. The felt-render pipeline (synth → revision) is exercised
        // by StoryServiceSolitudeTest + AffinityLearnerForgePassIT; here we
        // verify the projector reads a populated store correctly, so we
        // skip the synth dance and stage the scene with felt already set.
        var storyDir = tmp.resolve("story");
        Files.createDirectories(storyDir);
        var storyStore = new StoryStore(storyDir);
        var svc = new StoryService(AGENT, "TestAgent", storyStore,
            new ArcRegistry(), StoryService.NULL_SYNTH);

        var t0 = Instant.now().minusSeconds(120);
        var sceneRanges = new Beat(
            "beat-test-1", "scene-test-1",
            BeatTrigger.CAST_CHANGE,
            t0, t0.plusSeconds(60), List.of(), "Sat by the window.");
        var preRendered = new Scene(
            "scene-test-1", List.of(), ROOM, AGENT, List.of(AGENT),
            t0, t0.plusSeconds(60), "rest", List.of(sceneRanges),
            "I sat by the window and reread the same passage three times. "
                + "The light kept changing.",
            false, 1L, null, SceneKind.SOLITUDE);
        storyStore.saveScene(preRendered);

        // 4. Project from real stores
        var projector = new OrientationProjector(AGENT, wants, svc, chronicle);
        var orientation = projector.project(ProjectedOrientation.Lookahead.WHILE_AWAY);

        assertThat(orientation.isEmpty()).isFalse();
        assertThat(orientation.activeWantSummaries())
            .as("DEEPENED want surfaces first, all want texts present")
            .hasSizeGreaterThanOrEqualTo(2)
            .first().asString().contains("Yourcenar fragment");
        assertThat(orientation.activeWantSummaries())
            .anyMatch(s -> s.contains("slow rain"));
        assertThat(orientation.recentSolitudeBeats())
            .as("recent SOLITUDE scene's felt prose came through")
            .anyMatch(b -> b.contains("window") && b.contains("passage"));
        assertThat(orientation.openThreads())
            .as("chronicle entry summary surfaces as an open thread")
            .anyMatch(t -> t.toLowerCase().contains("rain") || t.toLowerCase().contains("noticing"));
        assertThat(orientation.lookahead())
            .isEqualTo(ProjectedOrientation.Lookahead.WHILE_AWAY);

        // 5. Compose — the prose MUST mention real grounded content.
        var composed = OrientationComposer.compose(orientation, "en");
        assertThat(composed).isNotBlank();
        // Grounded in real wants:
        assertThat(composed)
            .as("composed answer names the actual want texts from WantStore")
            .contains("Yourcenar fragment");
        // Grounded in real solitude history:
        assertThat(composed)
            .as("composed answer includes the prior solitude experience")
            .contains("Last stretch of own-time")
            .contains("window");
        // Lookahead opener for WHILE_AWAY:
        assertThat(composed.toLowerCase())
            .as("forward-looking opener for WHILE_AWAY lookahead")
            .startsWith("i'd probably");
        // No corpus performance, no exemplar prose:
        assertThat(composed)
            .doesNotContain("Examples of this register")
            .doesNotContain("SOLITUDE REGISTER REFERENCE");
    }

    @Test
    void emptyStoresProduceHonestFirstStretchAnswer() {
        // No populated stores → projector returns empty → composer renders
        // the honest "first stretch alone" answer rather than fabricating.
        var jdbc = SchemaInitializer.initialize(tmp.resolve("empty.db"));
        var wants = new WantStore(jdbc);
        var chronicle = new ChronicleEntryStore(jdbc);
        // No StoryService — exercises the null-storyService defensive path.
        var projector = new OrientationProjector(AGENT, wants, null, chronicle);
        var o = projector.project(ProjectedOrientation.Lookahead.WHILE_AWAY);
        assertThat(o.isEmpty()).isTrue();

        var composed = OrientationComposer.compose(o, "en");
        assertThat(composed).contains("first real stretch alone");
    }
}

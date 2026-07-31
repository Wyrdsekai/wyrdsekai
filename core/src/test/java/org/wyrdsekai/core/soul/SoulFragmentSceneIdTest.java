package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.story.Beat;
import org.wyrdsekai.core.story.BeatTrigger;
import org.wyrdsekai.core.story.Scene;
import org.wyrdsekai.core.story.StoryStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sceneId dedup across perspectives.
 *
 * <p>v1 wires a single id ({@link Scene#id()}) through two surfaces:
 * <ul>
 *   <li>{@link SoulFragment#sceneId()} — set by the per-scene voice-model
 *       fragment generator (Forge sleep-pass) on fragments rendered from
 *       a closed scene-cluster.</li>
 *   <li>{@link StoryStore#SCENE_ID_MARKER_PREFIX} HTML-comment in the
 *       human bondholder's mirrored journal markdown.</li>
 * </ul>
 * Both sides resolve to the same scene by id — direct lookup, no
 * similarity search. Pre-§14 fragments and non-scene-derived fragments
 * leave the field null.
 */
class SoulFragmentSceneIdTest {

    // ─── SoulFragment.sceneId field ─────────────────────────────────────────

    @Test
    void fromSceneFactoryStampsSceneId() {
        var f = SoulFragment.fromScene("frag-1", "memory",
            "By the hearth", "Quiet sitting.", "scene-abc-123");
        assertThat(f.sceneId()).isEqualTo("scene-abc-123");
        assertThat(f.kind()).isEqualTo(FragmentKind.DEFAULT);
        assertThat(f.formative()).isFalse();
    }

    @Test
    void withSceneIdReturnsCopyWithIdAttached() {
        var base = SoulFragment.unembedded("frag-2", "memory",
            "Scene", "Something happened.");
        assertThat(base.sceneId()).isNull();
        var stamped = base.withSceneId("scene-xyz");
        assertThat(stamped.sceneId()).isEqualTo("scene-xyz");
        assertThat(stamped.id()).isEqualTo(base.id());
        assertThat(stamped.text()).isEqualTo(base.text());
        assertThat(base.sceneId()).isNull(); // immutable: original unchanged
    }

    @Test
    void preExistingFactoriesLeaveSceneIdNull() {
        // SPEC §14: non-scene-derived fragments don't carry a sceneId.
        // Personality / formative / DEXTERITY / CONVENTION / STRUCTURAL
        // fragments all leave the field null.
        assertThat(SoulFragment.unembedded("a", "personality", "x", "y").sceneId()).isNull();
        assertThat(SoulFragment.formative("b", "home", "remembered home").sceneId()).isNull();
        assertThat(SoulFragment.dexterity("c", "skill", "x", "y").sceneId()).isNull();
        assertThat(SoulFragment.convention("d", "rule", "x", "y").sceneId()).isNull();
        assertThat(SoulFragment.structural("e", "shape", "x", "y").sceneId()).isNull();
    }

    @Test
    void backCompat14ArgCtorLeavesSceneIdNull() {
        // Pre-§17.6 callers (no kind, no sceneId) get DEFAULT kind + null sceneId.
        var f = new SoulFragment("legacy-1", "memory", "x", "y", null, null,
            false, 0.5f, 0, Instant.now(), null, null, null, null);
        assertThat(f.kind()).isEqualTo(FragmentKind.DEFAULT);
        assertThat(f.sceneId()).isNull();
    }

    @Test
    void backCompat15ArgCtorLeavesSceneIdNull() {
        // §17.6-aware callers (kind, no sceneId) get null sceneId.
        var f = new SoulFragment("dext-1", "skill", "x", "y", null, null,
            false, 0.5f, 0, Instant.now(), null, null, null, null,
            FragmentKind.DEXTERITY);
        assertThat(f.kind()).isEqualTo(FragmentKind.DEXTERITY);
        assertThat(f.sceneId()).isNull();
    }

    @Test
    void sceneIdSurvivesLifecycleCopies() {
        // withEmbedding / reinforce / contradict / supersede / withKind
        // must all preserve sceneId through their copies.
        var base = SoulFragment.fromScene("frag-3", "memory",
            "By the hearth", "Quiet sitting.", "scene-keep-me");

        assertThat(base.withEmbedding(new float[]{0.1f, 0.2f}, "m").sceneId())
            .isEqualTo("scene-keep-me");
        assertThat(base.reinforce().sceneId()).isEqualTo("scene-keep-me");
        assertThat(base.contradict().sceneId()).isEqualTo("scene-keep-me");
        assertThat(base.supersede("frag-4").sceneId()).isEqualTo("scene-keep-me");
        assertThat(base.withKind(FragmentKind.NARRATIVE).sceneId()).isEqualTo("scene-keep-me");
    }

    @Test
    void jacksonRoundTripPreservesSceneId() throws Exception {
        var f = SoulFragment.fromScene("frag-rt", "memory",
            "Scene", "Text.", "scene-jr-1");
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var json = mapper.writeValueAsString(f);
        assertThat(json).contains("\"sceneId\":\"scene-jr-1\"");
        var restored = mapper.readValue(json, SoulFragment.class);
        assertThat(restored.sceneId()).isEqualTo("scene-jr-1");
    }

    @Test
    void jacksonRoundTripPreservesNullSceneId() throws Exception {
        // @JsonInclude(NON_NULL) means a null sceneId is omitted from JSON;
        // deserializing absent field must reconstitute as null.
        var f = SoulFragment.unembedded("frag-noscene", "memory", "x", "y");
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var json = mapper.writeValueAsString(f);
        assertThat(json).doesNotContain("sceneId");
        var restored = mapper.readValue(json, SoulFragment.class);
        assertThat(restored.sceneId()).isNull();
    }

    // ─── Journal markdown sceneId marker ────────────────────────────────────

    @Test
    void renderSceneMarkdownIncludesSceneIdMarker(@TempDir Path tmp) {
        var scene = sampleScene("scene-md-1", "library", "ember-1");
        var md = StoryStore.renderSceneMarkdown("Ember", "Quiet by the fire",
            scene, List.of());
        assertThat(md).contains(StoryStore.SCENE_ID_MARKER_PREFIX + "scene-md-1 -->")
            // Marker should appear before the H2 so a reader scanning
            // from the top finds the id immediately.
            .matches("(?s).*<!-- sceneId: scene-md-1 -->\\s*\n## Quiet by the fire.*");
    }

    @Test
    void renderSceneMarkdownAlwaysIncludesMarkerForValidScenes() {
        // The Scene constructor enforces a non-blank id (defense-in-depth
        // at the type level), so every production scene that reaches
        // renderSceneMarkdown will carry a sceneId — and therefore a marker.
        // This test affirms the invariant: every rendered block carries
        // exactly one marker. The renderer's null-check is for robustness
        // only and won't fire on a valid scene.
        var scene = sampleScene("scene-must-mark", "library", "ember-1");
        var md = StoryStore.renderSceneMarkdown("Ember", "Quiet", scene, List.of());
        var firstHit = md.indexOf(StoryStore.SCENE_ID_MARKER_PREFIX);
        var lastHit = md.lastIndexOf(StoryStore.SCENE_ID_MARKER_PREFIX);
        assertThat(firstHit).as("marker must be present").isGreaterThanOrEqualTo(0);
        assertThat(firstHit).as("marker must appear exactly once per block").isEqualTo(lastHit);
    }

    // ─── StoryStore.focalsWithJournalEntryForScene lookup ───────────────────

    @Test
    void journalLookupFindsFocalByMarker(@TempDir Path tmp) {
        var store = new StoryStore(tmp);
        var scene = sampleScene("scene-find-me", "library", "ember-1");
        store.appendJournalScene("ember-1", "Ember", "Quiet", scene, List.of());

        assertThat(store.journalEntryExistsForScene("ember-1", "scene-find-me")).isTrue();
        assertThat(store.journalEntryExistsForScene("ember-1", "scene-not-there")).isFalse();
        assertThat(store.focalsWithJournalEntryForScene("scene-find-me"))
            .containsExactly("ember-1");
        assertThat(store.focalsWithJournalEntryForScene("scene-not-there")).isEmpty();
    }

    @Test
    void journalLookupReturnsAllFocalsMirroringTheScene(@TempDir Path tmp) {
        // SPEC §14 spine: the same closed scene produces a fragment on
        // Ember's side AND a mirrored entry on Masumi's side. Both
        // focals' journal dirs carry the same sceneId marker.
        var store = new StoryStore(tmp);
        var scene = sampleScene("scene-mirror-1", "library", "ember-1");
        store.appendJournalScene("ember-1", "Ember", "From Ember's POV", scene, List.of());
        store.appendJournalScene("operator", "Masumi",
            "Memories — from Ember's perspective", scene, List.of());

        var found = store.focalsWithJournalEntryForScene("scene-mirror-1");
        assertThat(found).containsExactlyInAnyOrder("ember-1", "operator");
    }

    @Test
    void journalLookupHandlesMissingBiographyRoot(@TempDir Path tmp) {
        var store = new StoryStore(tmp);
        // No journal written; the biography root may or may not exist
        // — lookup must return empty rather than throw.
        assertThat(store.focalsWithJournalEntryForScene("scene-nothing")).isEmpty();
        assertThat(store.journalEntryExistsForScene("ember-1", "scene-nothing")).isFalse();
    }

    @Test
    void journalLookupIgnoresNullAndBlankInputs(@TempDir Path tmp) {
        var store = new StoryStore(tmp);
        assertThat(store.focalsWithJournalEntryForScene(null)).isEmpty();
        assertThat(store.focalsWithJournalEntryForScene("")).isEmpty();
        assertThat(store.journalEntryExistsForScene(null, "scene-x")).isFalse();
        assertThat(store.journalEntryExistsForScene("ember-1", null)).isFalse();
    }

    @Test
    void crossPerspectiveLookupViaSceneIdRoundTrip(@TempDir Path tmp) {
        // The §14 promise end-to-end: a fragment carrying sceneId
        // "scene-Q" and a journal entry stamped with the same marker
        // resolve to each other without similarity search.
        var sceneId = "scene-cross-look";
        var store = new StoryStore(tmp);

        // Ember's voice writes a fragment from the scene-cluster.
        var fragment = SoulFragment.fromScene(
            "frag-by-fire", "memory",
            "By the fire",
            "He came home and sat by the hearth without speaking. " +
            "I sat across from him.",
            sceneId);

        // Masumi's mirrored journal entry is written with the same
        // sceneId marker (rendered by appendJournalScene → renderSceneMarkdown).
        var scene = sampleScene(sceneId, "library", "ember-1");
        store.appendJournalScene("operator", "Masumi",
            "Memories — from Ember's perspective", scene, List.of());

        // Forward: fragment carries the same id used to find the journal.
        assertThat(fragment.sceneId()).isEqualTo(sceneId);
        assertThat(store.journalEntryExistsForScene("operator", fragment.sceneId())).isTrue();

        // Reverse: scanning the biography for the sceneId on the
        // fragment returns Masumi.
        assertThat(store.focalsWithJournalEntryForScene(fragment.sceneId()))
            .contains("operator");
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private static Scene sampleScene(String id, String roomId, String focalId) {
        var beat = new Beat("beat-1", id, BeatTrigger.CAST_CHANGE,
            Instant.parse("2026-05-24T18:00:00Z"),
            Instant.parse("2026-05-24T18:12:00Z"),
            List.of(), "He came home and sat by the hearth.");
        return new Scene(
            id, List.of(), roomId, focalId, List.of(focalId),
            Instant.parse("2026-05-24T18:00:00Z"),
            Instant.parse("2026-05-24T18:12:00Z"),
            null, List.of(beat),
            "He sat across from me, and didn't speak for a long time.",
            false, 1L);
    }
}

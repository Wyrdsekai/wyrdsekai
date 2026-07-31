package org.wyrdsekai.core.story;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 — Scene.kind field + SceneKind back-compat.
 *
 * <p>Pre-Arc-2 persisted scene JSON files have no {@code kind} field. The
 * canonical ctor must default null → {@link SceneKind#WITNESS} so old data
 * loads as ordinary witness scenes; {@link Scene#asRevision(String, String, boolean)}
 * must preserve kind across the revision chain so a SOLITUDE scene stays
 * solitude even after the voice-render sweep amends its {@code felt} field.
 */
class SceneTest {

    private static final String ROOM = "room-hearth";
    private static final String FOCAL = "did:wyrd:companion-a";

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void oldJsonWithoutKindRoundTrips() throws Exception {
        var json = """
            {
              "id": "scene-legacy-1",
              "arcIds": [],
              "roomId": "room-hearth",
              "focalEntityId": "did:wyrd:companion-a",
              "participants": ["did:wyrd:companion-a"],
              "rangeStart": "2026-05-01T00:00:00Z",
              "rangeEnd": "2026-05-01T00:05:00Z",
              "wantContext": "rest",
              "beats": [],
              "felt": null,
              "needsRendering": true,
              "sequenceNumber": 1,
              "replacesId": null
            }
            """;
        var scene = mapper().readValue(json, Scene.class);
        // Direct field — canonical ctor body canonicalized null → WITNESS.
        assertThat(scene.kind()).isEqualTo(SceneKind.WITNESS);
        assertThat(scene.isSolitude()).isFalse();
    }

    @Test
    void backCompat12ArgCtorDefaultsKind() {
        var now = Instant.parse("2026-05-26T12:00:00Z");
        var scene = new Scene(
            "scene-12arg", List.of(), ROOM, FOCAL,
            List.of(FOCAL), now, now.plusSeconds(60),
            "rest", List.of(), null, true, 1L
        );
        assertThat(scene.kind()).isEqualTo(SceneKind.WITNESS);
    }

    @Test
    void backCompat13ArgCtorDefaultsKind() {
        var now = Instant.parse("2026-05-26T12:00:00Z");
        var scene = new Scene(
            "scene-13arg", List.of(), ROOM, FOCAL,
            List.of(FOCAL), now, now.plusSeconds(60),
            "rest", List.of(), null, true, 1L, null
        );
        assertThat(scene.kind()).isEqualTo(SceneKind.WITNESS);
    }

    @Test
    void isSolitudePredicate() {
        var now = Instant.parse("2026-05-26T12:00:00Z");
        var witness = new Scene(
            "scene-w", List.of(), ROOM, FOCAL,
            List.of(FOCAL), now, now.plusSeconds(60),
            "rest", List.of(), null, true, 1L, null, SceneKind.WITNESS
        );
        var solitude = new Scene(
            "scene-s", List.of(), ROOM, FOCAL,
            List.of(FOCAL), now, now.plusSeconds(60),
            "rest", List.of(), null, true, 2L, null, SceneKind.SOLITUDE
        );
        assertThat(witness.isSolitude()).isFalse();
        assertThat(solitude.isSolitude()).isTrue();
    }

    @Test
    void asRevisionPreservesKind() {
        var now = Instant.parse("2026-05-26T12:00:00Z");
        var solitude = new Scene(
            "scene-orig", List.of(), ROOM, FOCAL,
            List.of(FOCAL), now, now.plusSeconds(60),
            "rest", List.of(), null, true, 1L, null, SceneKind.SOLITUDE
        );
        var revised = solitude.asRevision("scene-orig-rev",
            "felt: a quiet stretch.", false);
        assertThat(revised.kind()).isEqualTo(SceneKind.SOLITUDE);
        assertThat(revised.isSolitude()).isTrue();
        assertThat(revised.replacesId()).isEqualTo("scene-orig");
    }

    @Test
    void solitudeSceneRoundTripsViaJackson() throws Exception {
        var now = Instant.parse("2026-05-26T12:00:00Z");
        var scene = new Scene(
            "scene-roundtrip", List.of(), ROOM, FOCAL,
            List.of(FOCAL), now, now.plusSeconds(60),
            "rest", List.of(), null, true, 1L, null, SceneKind.SOLITUDE
        );
        var m = mapper();
        var json = m.writeValueAsString(scene);
        assertThat(json).contains("\"SOLITUDE\"");
        var back = m.readValue(json, Scene.class);
        assertThat(back.kind()).isEqualTo(SceneKind.SOLITUDE);
        assertThat(back.id()).isEqualTo("scene-roundtrip");
    }

    @Test
    void sceneKindEnumStable() {
        assertThat(SceneKind.values()).hasSize(2);
        assertThat(SceneKind.valueOf("WITNESS")).isEqualTo(SceneKind.WITNESS);
        assertThat(SceneKind.valueOf("SOLITUDE")).isEqualTo(SceneKind.SOLITUDE);
    }
}

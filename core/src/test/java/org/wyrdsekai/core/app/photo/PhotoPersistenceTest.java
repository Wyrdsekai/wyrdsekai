package org.wyrdsekai.core.app.photo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.app.photo.PhotoService.*;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoPersistenceTest {

    private PhotoPersistence persistence;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        var jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new PhotoPersistence(jdbcUrl);
    }

    @Test void save_and_load_photo() {
        var photo = new Photo("p-1", "sunset.jpg", "alice",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700001000),
            "beach", Set.of("sunset", "ocean"), Set.of("face-1"),
            "abc123", PhotoStatus.TAGGED);
        persistence.savePhoto(photo);

        var loaded = persistence.loadPhoto("p-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().filename()).isEqualTo("sunset.jpg");
        assertThat(loaded.get().ownerEntity()).isEqualTo("alice");
        assertThat(loaded.get().location()).isEqualTo("beach");
        assertThat(loaded.get().tags()).containsExactlyInAnyOrder("sunset", "ocean");
        assertThat(loaded.get().faces()).contains("face-1");
        assertThat(loaded.get().status()).isEqualTo(PhotoStatus.TAGGED);
    }

    @Test void photo_not_found() {
        assertThat(persistence.loadPhoto("ghost")).isEmpty();
    }

    @Test void photos_by_owner() {
        persistence.savePhoto(new Photo("p-1", "a.jpg", "alice",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700001000),
            null, Set.of(), Set.of(), null, PhotoStatus.IMPORTED));
        persistence.savePhoto(new Photo("p-2", "b.jpg", "bob",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700002000),
            null, Set.of(), Set.of(), null, PhotoStatus.IMPORTED));
        persistence.savePhoto(new Photo("p-3", "c.jpg", "alice",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700003000),
            null, Set.of(), Set.of(), null, PhotoStatus.IMPORTED));

        assertThat(persistence.photosByOwner("alice")).hasSize(2);
        assertThat(persistence.photosByOwner("bob")).hasSize(1);
    }

    @Test void photos_by_tag() {
        persistence.savePhoto(new Photo("p-1", "a.jpg", "alice",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700001000),
            null, Set.of("sunset", "beach"), Set.of(), null, PhotoStatus.TAGGED));
        persistence.savePhoto(new Photo("p-2", "b.jpg", "alice",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700002000),
            null, Set.of("mountain"), Set.of(), null, PhotoStatus.TAGGED));

        assertThat(persistence.photosByTag("sunset")).hasSize(1);
        assertThat(persistence.photosByTag("mountain")).hasSize(1);
        assertThat(persistence.photosByTag("forest")).isEmpty();
    }

    @Test void photo_count() {
        assertThat(persistence.photoCount()).isEqualTo(0);
        persistence.savePhoto(new Photo("p-1", "a.jpg", "alice",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700001000),
            null, Set.of(), Set.of(), null, PhotoStatus.IMPORTED));
        assertThat(persistence.photoCount()).isEqualTo(1);
    }

    @Test void photo_upsert() {
        persistence.savePhoto(new Photo("p-1", "old.jpg", "alice",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700001000),
            null, Set.of(), Set.of(), null, PhotoStatus.IMPORTED));
        persistence.savePhoto(new Photo("p-1", "new.jpg", "alice",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700001000),
            null, Set.of("updated"), Set.of(), null, PhotoStatus.TAGGED));

        var loaded = persistence.loadPhoto("p-1");
        assertThat(loaded.get().filename()).isEqualTo("new.jpg");
        assertThat(loaded.get().tags()).contains("updated");
        assertThat(persistence.photoCount()).isEqualTo(1);
    }

    @Test void save_and_load_face_group() {
        var group = new FaceGroup("f-1", "Alice", Set.of("p-1", "p-2"),
            Instant.ofEpochSecond(1700000000));
        persistence.saveFaceGroup(group);

        var loaded = persistence.loadFaceGroup("f-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().name()).isEqualTo("Alice");
        assertThat(loaded.get().photoIds()).containsExactlyInAnyOrder("p-1", "p-2");
    }

    @Test void face_group_not_found() {
        assertThat(persistence.loadFaceGroup("ghost")).isEmpty();
    }

    @Test void save_and_list_memory_lanes() {
        persistence.saveMemoryLane(new MemoryLane("l-1", "Summer 2025",
            "Beach vacation", List.of("p-1", "p-2", "p-3"),
            Instant.ofEpochSecond(1700000000), "alice"));
        persistence.saveMemoryLane(new MemoryLane("l-2", "Winter 2025",
            "Ski trip", List.of("p-4"),
            Instant.ofEpochSecond(1700010000), "bob"));

        var lanes = persistence.allMemoryLanes();
        assertThat(lanes).hasSize(2);
        // Ordered by created_at DESC
        assertThat(lanes.get(0).title()).isEqualTo("Winter 2025");
        assertThat(lanes.get(1).title()).isEqualTo("Summer 2025");
    }

    @Test void photo_with_null_fields() {
        var photo = new Photo("p-1", "a.jpg", "alice",
            null, Instant.ofEpochSecond(1700001000),
            null, Set.of(), Set.of(), null, PhotoStatus.IMPORTED);
        persistence.savePhoto(photo);

        var loaded = persistence.loadPhoto("p-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().perceptualHash()).isNull();
    }
}

package org.wyrdsekai.core.app.photo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoServiceTest {

    private PhotoService service;

    @BeforeEach void setUp() {
        service = new PhotoService();
    }

    @Test void importPhoto_creates_record() {
        var photo = service.importPhoto("sunset.jpg", "alice",
            Instant.parse("2025-06-15T18:30:00Z"), "Beach");
        assertThat(photo.filename()).isEqualTo("sunset.jpg");
        assertThat(photo.status()).isEqualTo(PhotoService.PhotoStatus.IMPORTED);
        assertThat(service.photoCount()).isEqualTo(1);
    }

    @Test void tagPhoto_adds_tags() {
        var photo = service.importPhoto("cat.jpg", "bob", Instant.now(), "Home");
        var tagged = service.tagPhoto(photo.photoId(), Set.of("cat", "cute", "pet"));
        assertThat(tagged).isPresent();
        assertThat(tagged.get().tags()).containsExactlyInAnyOrder("cat", "cute", "pet");
        assertThat(tagged.get().status()).isEqualTo(PhotoService.PhotoStatus.TAGGED);
    }

    @Test void assignFace_creates_face_group() {
        var photo = service.importPhoto("family.jpg", "alice", Instant.now(), "Park");
        service.assignFace(photo.photoId(), "face-alice");
        var group = service.getFaceGroup("face-alice");
        assertThat(group).isPresent();
        assertThat(group.get().photoIds()).contains(photo.photoId());
    }

    @Test void nameFace_updates_group_name() {
        var photo = service.importPhoto("portrait.jpg", "alice", Instant.now(), "Studio");
        service.assignFace(photo.photoId(), "face-1");
        var named = service.nameFace("face-1", "Alice");
        assertThat(named).isPresent();
        assertThat(named.get().name()).isEqualTo("Alice");
    }

    @Test void createMemoryLane_groups_photos() {
        var p1 = service.importPhoto("beach1.jpg", "alice", Instant.now(), "Beach");
        var p2 = service.importPhoto("beach2.jpg", "alice", Instant.now(), "Beach");
        var lane = service.createMemoryLane("Beach Trip", "Summer vacation",
            List.of(p1.photoId(), p2.photoId()), "alice");
        assertThat(lane.photoIds()).hasSize(2);
        assertThat(service.listMemoryLanes()).hasSize(1);
    }

    @Test void searchByTag_finds_matching() {
        var p1 = service.importPhoto("cat.jpg", "bob", Instant.now(), "Home");
        service.tagPhoto(p1.photoId(), Set.of("cat"));
        var p2 = service.importPhoto("dog.jpg", "bob", Instant.now(), "Home");
        service.tagPhoto(p2.photoId(), Set.of("dog"));
        assertThat(service.searchByTag("cat")).hasSize(1);
    }

    @Test void searchByLocation_finds_matching() {
        service.importPhoto("eiffel.jpg", "alice", Instant.now(), "Paris, France");
        service.importPhoto("tokyo.jpg", "bob", Instant.now(), "Tokyo, Japan");
        assertThat(service.searchByLocation("paris")).hasSize(1);
    }

    @Test void describe_shows_summary() {
        service.importPhoto("test.jpg", "alice", Instant.now(), "Somewhere");
        assertThat(service.describe()).contains("Photo Fabric");
        assertThat(service.describe()).contains("test.jpg");
    }
}

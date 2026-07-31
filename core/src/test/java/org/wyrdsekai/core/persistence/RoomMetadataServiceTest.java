package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class RoomMetadataServiceTest {

    private RoomMetadataService service;

    @BeforeEach void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        service = new RoomMetadataService(jdbcUrl);
    }

    @Test void register_and_get() {
        service.register("nexus", "The Nexus", "foundation", "system");
        var room = service.getRoom("nexus");
        assertThat(room).isPresent();
        assertThat(room.get().name()).isEqualTo("The Nexus");
        assertThat(room.get().zone()).isEqualTo("foundation");
    }

    @Test void register_idempotent() {
        service.register("nexus", "The Nexus", "foundation", "system");
        service.register("nexus", "The Nexus Updated", "foundation", "system");
        // INSERT OR IGNORE — second insert ignored, name stays original
        assertThat(service.countRooms()).isEqualTo(1);
    }

    @Test void listRooms_ordered_by_name() {
        service.register("terminal", "The Terminal", "foundation", "system");
        service.register("nexus", "The Nexus", "foundation", "system");
        service.register("bridge", "The Bridge", "foundation", "system");
        var rooms = service.listRooms();
        assertThat(rooms).hasSize(3);
        assertThat(rooms.get(0).name()).isEqualTo("The Bridge");
        assertThat(rooms.get(1).name()).isEqualTo("The Nexus");
        assertThat(rooms.get(2).name()).isEqualTo("The Terminal");
    }

    @Test void listRooms_empty() {
        assertThat(service.listRooms()).isEmpty();
    }

    @Test void countRooms() {
        service.register("a", "Room A", "zone1", "user");
        service.register("b", "Room B", "zone1", "user");
        assertThat(service.countRooms()).isEqualTo(2);
    }

    @Test void getRoom_not_found() {
        assertThat(service.getRoom("nonexistent")).isEmpty();
    }
}

package org.wyrdsekai.core.codezaiku;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeZaikuRoomsTest {

    @Test void five_spatial_rooms() {
        assertThat(CodeZaikuRooms.allRooms()).hasSize(5);
    }

    @Test void room_count() {
        assertThat(CodeZaikuRooms.roomCount()).isEqualTo(5);
    }

    @Test void room_ids() {
        var ids = CodeZaikuRooms.roomIds();
        assertThat(ids).contains("the-forge", "the-crucible", "the-assay-office",
            "the-ledger", "the-archive");
    }

    @Test void get_room_by_id() {
        var forge = CodeZaikuRooms.getRoom("the-forge");
        assertThat(forge).isPresent();
        assertThat(forge.get().name()).isEqualTo("The Forge");
        assertThat(forge.get().description()).contains("code is forged");
    }

    @Test void get_room_not_found() {
        assertThat(CodeZaikuRooms.getRoom("nonexistent")).isEmpty();
    }

    @Test void rooms_have_exits() {
        for (var room : CodeZaikuRooms.allRooms()) {
            assertThat(room.defaultExits()).isNotEmpty();
        }
    }

    @Test void exit_targets_valid() {
        var issues = CodeZaikuRooms.validateExits();
        assertThat(issues).isEmpty();
    }

    @Test void room_graph() {
        var graph = CodeZaikuRooms.roomGraph();
        assertThat(graph).hasSize(5);
        assertThat(graph.get("the-forge")).contains("the-crucible");
    }

    @Test void rooms_have_properties() {
        for (var room : CodeZaikuRooms.allRooms()) {
            assertThat(room.properties()).containsKey("workspaceType");
        }
    }
}

package org.wyrdsekai.core.codeplane;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodePlaneRoomsTest {

    @Test void five_spatial_rooms() {
        assertThat(CodePlaneRooms.allRooms()).hasSize(5);
    }

    @Test void room_count() {
        assertThat(CodePlaneRooms.roomCount()).isEqualTo(5);
    }

    @Test void room_ids() {
        var ids = CodePlaneRooms.roomIds();
        assertThat(ids).contains("the-forge", "the-crucible", "the-assay-office",
            "the-ledger", "the-archive");
    }

    @Test void get_room_by_id() {
        var forge = CodePlaneRooms.getRoom("the-forge");
        assertThat(forge).isPresent();
        assertThat(forge.get().name()).isEqualTo("The Forge");
        assertThat(forge.get().description()).contains("code is forged");
    }

    @Test void get_room_not_found() {
        assertThat(CodePlaneRooms.getRoom("nonexistent")).isEmpty();
    }

    @Test void rooms_have_exits() {
        for (var room : CodePlaneRooms.allRooms()) {
            assertThat(room.defaultExits()).isNotEmpty();
        }
    }

    @Test void exit_targets_valid() {
        var issues = CodePlaneRooms.validateExits();
        assertThat(issues).isEmpty();
    }

    @Test void room_graph() {
        var graph = CodePlaneRooms.roomGraph();
        assertThat(graph).hasSize(5);
        assertThat(graph.get("the-forge")).contains("the-crucible");
    }

    @Test void rooms_have_properties() {
        for (var room : CodePlaneRooms.allRooms()) {
            assertThat(room.properties()).containsKey("workspaceType");
        }
    }
}

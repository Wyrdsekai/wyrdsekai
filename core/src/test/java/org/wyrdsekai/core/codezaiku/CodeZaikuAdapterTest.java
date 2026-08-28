package org.wyrdsekai.core.codezaiku;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeZaikuAdapterTest {

    private CodeZaikuAdapter adapter;

    @BeforeEach void setUp() {
        adapter = new CodeZaikuAdapter();
    }

    @Test void mapBoard_creates_mapping() {
        var mapping = adapter.mapBoard("board-1", "the-forge", "Dev Board",
            Map.of("todo", "stage_todo", "done", "stage_done"),
            Map.of("alice", "agent-alice"));
        assertThat(mapping.boardId()).isEqualTo("board-1");
        assertThat(mapping.roomId()).isEqualTo("the-forge");
    }

    @Test void mapCard_creates_mapping() {
        var mapping = adapter.mapCard("card-1", "obj-task-1", "Fix bug",
            "todo", "agent-alice", Map.of("priority", "high"));
        assertThat(mapping.cardId()).isEqualTo("card-1");
        assertThat(mapping.title()).isEqualTo("Fix bug");
    }

    @Test void translateToRoom_creates_bridge_event() {
        adapter.mapBoard("board-1", "the-forge", "Dev Board", Map.of(), Map.of());
        var event = adapter.translateToRoom("card_moved", "board-1",
            Map.of("card", "card-1", "column", "done"));
        assertThat(event).isPresent();
        assertThat(event.get().source()).isEqualTo("codezaiku");
        assertThat(event.get().targetId()).isEqualTo("the-forge");
    }

    @Test void translateToCodeZaiku_creates_bridge_event() {
        adapter.mapBoard("board-1", "the-forge", "Dev Board", Map.of(), Map.of());
        var event = adapter.translateToCodeZaiku("speech", "the-forge",
            Map.of("text", "Build complete"));
        assertThat(event).isPresent();
        assertThat(event.get().source()).isEqualTo("wyrdsekai");
        assertThat(event.get().targetId()).isEqualTo("board-1");
    }

    @Test void spatialRooms_returns_five_rooms() {
        assertThat(adapter.spatialRooms()).hasSize(5);
        assertThat(adapter.spatialRooms().get(0).roomId()).isEqualTo("the-forge");
    }

    @Test void recentEvents_returns_event_log() {
        adapter.translateToRoom("test", "src-1", Map.of());
        adapter.translateToRoom("test2", "src-2", Map.of());
        assertThat(adapter.recentEvents(5)).hasSize(2);
    }

    @Test void describe_shows_summary() {
        adapter.mapBoard("b-1", "room-1", "Board", Map.of(), Map.of());
        var desc = adapter.describe();
        assertThat(desc).contains("CodeZaiku Integration");
        assertThat(desc).contains("Board mappings: 1");
        assertThat(desc).contains("Spatial Rooms");
    }
}

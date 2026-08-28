package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When she gives a thing away, she stops having it.
 *
 * <h2>What went wrong</h2>
 * Live 2026-08-24: "*hands library_query_tool to operator*" — and the room's
 * {@code Here:} listed it too, because the hand-off only ever ADDED to the
 * recipient. Three copies of one gift: the room object stayed on the floor,
 * the recipient got an inventory row, and (for crafted items) her own row
 * survived to rehydrate on every boot. Her action menu ranked tools she had
 * given away against tools she actually holds — 8 slots chosen from 187
 * candidates, many of them ghosts.
 */
class GivingIsAMoveNotACopyTest {

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    @DisplayName("the dispatch hand-off removes the room copy and her copies")
    void dispatchHandoffMoves() throws Exception {
        var src = actorSource();
        var body = src.substring(src.indexOf("private Behavior<Command> onDispatchHandoff"));
        body = body.substring(0, body.indexOf("\n    }"));
        var add = body.indexOf("inventory.addItem(targetId");
        var roomRemove = body.indexOf("ItemBridgeSubAction.RemoveObject(match.id())");
        var herRow = body.indexOf("inventory.removeItem(profile.entityId(), match.id())");
        var herSurface = body.indexOf("dynamicItems.removeIf");
        assertThat(add).as("recipient gains").isGreaterThan(-1);
        assertThat(roomRemove).as("the room copy leaves").isGreaterThan(add);
        assertThat(herRow).as("her inventory row leaves").isGreaterThan(add);
        assertThat(herSurface).as("her tool surface forgets it").isGreaterThan(add);
    }

    @Test
    @DisplayName("the craft hand-off removes her rehydrating copy")
    void craftHandoffMoves() throws Exception {
        var src = actorSource();
        var body = src.substring(src.indexOf("private void maybeHandOffCraftedItem"));
        body = body.substring(0, body.indexOf("\n    }"));
        var add = body.indexOf("craftInventory.addItem(targetId");
        var herRow = body.indexOf("craftInventory.removeItem(profile.entityId(), toolItem.id())");
        var herSurface = body.indexOf("dynamicItems.removeIf");
        assertThat(add).isGreaterThan(-1);
        assertThat(herRow)
            .as("the row that would rehydrate her ghost copy on every boot leaves")
            .isGreaterThan(add);
        assertThat(herSurface).isGreaterThan(add);
    }
}

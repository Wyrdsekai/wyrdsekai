package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code give <item> to <who>}: the item changes hands only when the giver carries it and the other
 * is in the same room, and a sentence that merely starts with "give" is not a gift.
 */
@Tag("integration")
class GivingTest {

    private InventoryService inventory;

    @BeforeEach
    void aRoomWithTwoPeopleInIt(@TempDir Path tmp) throws Exception {
        inventory = new InventoryService(SchemaInitializer.initialize(tmp.resolve("world.db")));
        EntityRegistry.init();
        EntityRegistry.get().enter("did:person:ada", "Ada", "player", "nexus");
        EntityRegistry.get().enter("companion-rose", "rose", "agent", "nexus");
        EntityRegistry.get().enter("companion-mia", "mia", "agent", "study");
        inventory.addItem("did:person:ada", "obj-stone", "river stone", "A smooth grey stone.", true, "nexus");
    }

    @Test
    void theLineIsReadAsAnItemAndAName() {
        assertThat(Giving.parse("give river stone to rose")).containsExactly("river stone", "rose");
        assertThat(Giving.parse("  GIVE the river stone TO Rose ")).containsExactly("the river stone", "Rose");
        assertThat(Giving.parse("give me a minute")).isNull();
        assertThat(Giving.parse("give it to the both of us")).as("the name is one word").isNull();
        assertThat(Giving.parse(null)).isNull();
    }

    @Test
    void theItemChangesHands() {
        var outcome = Giving.give(inventory, "did:person:ada", "Ada", "nexus", "river stone", "rose");
        assertThat(outcome.status()).isEqualTo(Giving.Status.GIVEN);
        assertThat(outcome.itemName()).isEqualTo("river stone");
        assertThat(outcome.targetName()).isEqualTo("rose");
        assertThat(inventory.findTakeableByName("did:person:ada", "river stone")).isEmpty();
        assertThat(inventory.findTakeableByName("companion-rose", "river stone")).isPresent();
    }

    @Test
    void somethingNotCarriedIsNotGiven() {
        var outcome = Giving.give(inventory, "did:person:ada", "Ada", "nexus", "lantern", "rose");
        assertThat(outcome.status()).isEqualTo(Giving.Status.NOT_CARRIED);
        assertThat(inventory.listItems("companion-rose")).isEmpty();
    }

    @Test
    void someoneInAnotherRoomOrNobodyIsNotGivenTo() {
        assertThat(Giving.give(inventory, "did:person:ada", "Ada", "nexus", "river stone", "mia").status())
            .as("mia is in the study").isEqualTo(Giving.Status.NOT_HERE);
        assertThat(Giving.give(inventory, "did:person:ada", "Ada", "nexus", "river stone", "nobody").status())
            .isEqualTo(Giving.Status.NOT_HERE);
        assertThat(inventory.findTakeableByName("did:person:ada", "river stone")).as("still hers").isPresent();
    }
}

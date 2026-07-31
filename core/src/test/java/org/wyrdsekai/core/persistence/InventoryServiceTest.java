package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class InventoryServiceTest {

    private InventoryService service;

    @BeforeEach void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        service = new InventoryService(jdbcUrl);
    }

    @Test void add_and_list() {
        service.addItem("alice", "key-1", "Golden Key", "An ornate key", true, "nexus");
        var items = service.listItems("alice");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().objectName()).isEqualTo("Golden Key");
    }

    @Test void remove_item() {
        service.addItem("alice", "key-1", "Golden Key", "A key", true, "nexus");
        var removed = service.removeItem("alice", "key-1");
        assertThat(removed).isPresent();
        assertThat(removed.get().objectName()).isEqualTo("Golden Key");
        assertThat(service.listItems("alice")).isEmpty();
    }

    @Test void list_empty() {
        assertThat(service.listItems("nobody")).isEmpty();
    }

    @Test void add_duplicate_upserts() {
        service.addItem("alice", "key-1", "Golden Key", "A key", true, "nexus");
        service.addItem("alice", "key-1", "Silver Key", "Updated", true, "vault");
        var items = service.listItems("alice");
        assertThat(items).hasSize(1);
        // Upsert updates the name
        assertThat(items.getFirst().objectName()).isEqualTo("Silver Key");
    }

    @Test void list_only_own_items() {
        service.addItem("alice", "key-1", "Alice's Key", "A key", true, "nexus");
        service.addItem("bob", "key-2", "Bob's Key", "A key", true, "nexus");
        assertThat(service.listItems("alice")).hasSize(1);
        assertThat(service.listItems("alice").getFirst().objectName()).isEqualTo("Alice's Key");
    }

    @Test void hasItem() {
        service.addItem("alice", "key-1", "Key", "A key", true, "nexus");
        assertThat(service.hasItem("alice", "key-1")).isTrue();
        assertThat(service.hasItem("alice", "key-2")).isFalse();
    }

    @Test void countItems() {
        service.addItem("alice", "key-1", "Key 1", "", true, "nexus");
        service.addItem("alice", "key-2", "Key 2", "", true, "nexus");
        assertThat(service.countItems("alice")).isEqualTo(2);
        assertThat(service.countItems("bob")).isEqualTo(0);
    }

    @Test void remove_nonexistent_returns_empty() {
        assertThat(service.removeItem("alice", "nonexistent")).isEmpty();
    }

    @Test void findTakeableByName_skips_pinned_furnishings() {
        // Study pins a scripted 'Compass' (takeable=false) into the inventory
        // as a tool. Docks also has a portable 'compass' the player can pick
        // up. Drop/give must target the portable row, not the pinned one.
        service.addItem("alice", "study-compass", "Compass", "scripted furnishing", false, "study");
        service.addItem("alice", "docks-compass", "compass", "a brass compass",  true,  "docks");

        var picked = service.findTakeableByName("alice", "compass");
        assertThat(picked).isPresent();
        assertThat(picked.get().objectId()).isEqualTo("docks-compass");
        assertThat(picked.get().takeable()).isTrue();
    }

    @Test void findTakeableByName_returns_empty_when_only_pinned_match() {
        service.addItem("alice", "study-compass", "Compass", "scripted furnishing", false, "study");
        assertThat(service.findTakeableByName("alice", "compass")).isEmpty();
    }
}

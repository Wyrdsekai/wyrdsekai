package org.wyrdsekai.common.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransitInventoryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void empty_inventory() {
        var inv = TransitInventory.empty("alpha");
        assertEquals("alpha", inv.sourceZone());
        assertTrue(inv.items().isEmpty());
    }

    @Test
    void simple_item_construction() {
        var item = TransitInventory.TransitItem.simple(
            "sword-1", "iron sword", "sharp", true, List.of("sword", "blade"));
        assertEquals("sword-1", item.id());
        assertEquals("iron sword", item.name());
        assertTrue(item.takeable());
        assertEquals(2, item.aliases().size());
        assertNull(item.scriptSource());
        assertTrue(item.properties().isEmpty());
    }

    @Test
    void transit_item_to_room_object() {
        var item = TransitInventory.TransitItem.simple(
            "key-1", "golden key", "ornate", true, List.of("key"));
        var obj = item.toRoomObject();
        assertEquals("key-1", obj.id());
        assertEquals("golden key", obj.name());
        assertTrue(obj.takeable());
        assertEquals(List.of("key"), obj.aliases());
    }

    @Test
    void serialization_roundtrip() throws Exception {
        var inv = new TransitInventory("alpha", List.of(
            TransitInventory.TransitItem.simple("s1", "sword", "sharp", true, List.of("sword")),
            new TransitInventory.TransitItem("k1", "key", "gold", true,
                List.of("key"), "// script", "unlock", Map.of("uses", "3"))));

        var json = mapper.writeValueAsString(inv);
        var restored = mapper.readValue(json, TransitInventory.class);

        assertEquals("alpha", restored.sourceZone());
        assertEquals(2, restored.items().size());
        assertEquals("// script", restored.items().get(1).scriptSource());
        assertEquals("unlock", restored.items().get(1).scriptId());
        assertEquals("3", restored.items().get(1).properties().get("uses"));
    }

    @Test
    void delta_empty() {
        var delta = TransitInventory.TransitDelta.empty();
        assertTrue(delta.isEmpty());
        assertTrue(delta.removedItemIds().isEmpty());
        assertTrue(delta.addedItems().isEmpty());
    }

    @Test
    void delta_roundtrip() throws Exception {
        var delta = new TransitInventory.TransitDelta(
            List.of("dropped-1", "dropped-2"),
            List.of(TransitInventory.TransitItem.simple("new-1", "gem", "shiny", true, List.of("gem"))));

        var json = mapper.writeValueAsString(delta);
        var restored = mapper.readValue(json, TransitInventory.TransitDelta.class);

        assertEquals(2, restored.removedItemIds().size());
        assertEquals(1, restored.addedItems().size());
        assertFalse(restored.isEmpty());
    }

    @Test
    void deserialization_with_missing_fields_uses_defaults() throws Exception {
        var json = """
            {"sourceZone":"alpha"}
            """;
        var inv = mapper.readValue(json, TransitInventory.class);
        assertEquals("alpha", inv.sourceZone());
        assertTrue(inv.items().isEmpty());
    }

    @Test
    void item_deserialization_with_missing_optional_fields() throws Exception {
        var json = """
            {"id":"s1","name":"sword","description":"sharp","takeable":true}
            """;
        var item = mapper.readValue(json, TransitInventory.TransitItem.class);
        assertEquals("s1", item.id());
        assertNull(item.scriptSource());
        assertNull(item.scriptId());
        assertTrue(item.aliases().isEmpty());
        assertTrue(item.properties().isEmpty());
    }
}

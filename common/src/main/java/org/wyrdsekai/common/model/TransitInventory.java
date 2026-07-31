package org.wyrdsekai.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Inventory payload that travels with a player to a remote zone.
 *
 * Used in the session.open message of a cross-zone transit. The remote zone
 * instantiates these items as session-scoped virtual inventory — the player
 * can use/drop them while visiting. On session.close, the delta is returned
 * so the source zone can apply inventory changes (items dropped remotely
 * are removed from home inventory; items taken remotely are added).
 *
 * @param sourceZone zone where the inventory originated
 * @param items      list of items the player is carrying
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransitInventory(
    @JsonProperty("sourceZone") String sourceZone,
    @JsonProperty("items") List<TransitItem> items
) {

    /** Empty inventory for a player carrying nothing. */
    public static TransitInventory empty(String sourceZone) {
        return new TransitInventory(sourceZone, List.of());
    }

    @JsonCreator
    public static TransitInventory create(
            @JsonProperty("sourceZone") String sourceZone,
            @JsonProperty("items") List<TransitItem> items) {
        return new TransitInventory(
            sourceZone,
            items != null ? items : List.of());
    }

    /**
     * A single item in transit.
     *
     * @param id            unique item ID (from InventoryService)
     * @param name          display name
     * @param description   brief description
     * @param takeable      whether the item can be picked up
     * @param aliases       alias list for resolution
     * @param scriptSource  GraalJS script source (nullable — only for scripted items)
     * @param scriptId      script identifier (nullable)
     * @param properties    item-specific state (extensible)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransitItem(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("takeable") boolean takeable,
        @JsonProperty("aliases") List<String> aliases,
        @JsonProperty("scriptSource") String scriptSource,
        @JsonProperty("scriptId") String scriptId,
        @JsonProperty("properties") Map<String, String> properties
    ) {
        @JsonCreator
        public static TransitItem create(
                @JsonProperty("id") String id,
                @JsonProperty("name") String name,
                @JsonProperty("description") String description,
                @JsonProperty("takeable") boolean takeable,
                @JsonProperty("aliases") List<String> aliases,
                @JsonProperty("scriptSource") String scriptSource,
                @JsonProperty("scriptId") String scriptId,
                @JsonProperty("properties") Map<String, String> properties) {
            return new TransitItem(
                id, name, description, takeable,
                aliases != null ? aliases : List.of(),
                scriptSource,
                scriptId,
                properties != null ? properties : Map.of());
        }

        /** Simple constructor for non-scripted items. */
        public static TransitItem simple(String id, String name, String description,
                                          boolean takeable, List<String> aliases) {
            return new TransitItem(id, name, description, takeable, aliases, null, null, Map.of());
        }

        /** Convert to a RoomObject for remote zone instantiation. */
        public RoomObject toRoomObject() {
            return new RoomObject(id, name, description, takeable, true, true, aliases);
        }
    }

    /**
     * Delta applied on session.close to sync changes back to source zone.
     *
     * @param removedItemIds items that were dropped or consumed in the remote zone
     * @param addedItems     items picked up in the remote zone (brought home)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransitDelta(
        @JsonProperty("removedItemIds") List<String> removedItemIds,
        @JsonProperty("addedItems") List<TransitItem> addedItems
    ) {
        public static TransitDelta empty() {
            return new TransitDelta(List.of(), List.of());
        }

        @JsonCreator
        public static TransitDelta create(
                @JsonProperty("removedItemIds") List<String> removedItemIds,
                @JsonProperty("addedItems") List<TransitItem> addedItems) {
            return new TransitDelta(
                removedItemIds != null ? removedItemIds : List.of(),
                addedItems != null ? addedItems : List.of());
        }

        public boolean isEmpty() {
            return removedItemIds.isEmpty() && addedItems.isEmpty();
        }
    }
}

package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemExchangeTest {

    private val sampleItem = buildJsonObject {
        put("id", "item-sacred-01")
        put("name", "Sacred Crystal")
        put("significance", 0.9)
    }

    @Test
    fun sendPublishesToCorrectInboxSubject() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = ItemExchangeManager(between, "bud-1", "household-1")

        manager.sendItem("bud-2", sampleItem, "A gift for you")

        assertEquals(1, between.published.size)
        assertEquals(
            "between.household-1.items.bud-2.inbox",
            between.published[0].first
        )
    }

    @Test
    fun sendIncludesItemAndMessage() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = ItemExchangeManager(between, "bud-1", "household-1")

        manager.sendItem("bud-2", sampleItem, "A gift for you")

        val payload = between.published[0].second.decodeToString()
        val transfer = Json { ignoreUnknownKeys = true }
            .decodeFromString<ItemTransfer>(payload)
        assertEquals("bud-1", transfer.fromDid)
        assertEquals("bud-2", transfer.toDid)
        assertEquals("A gift for you", transfer.message)
    }

    @Test
    fun receivedItemsGoToQuarantine() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = ItemExchangeManager(between, "bud-1", "household-1")
        manager.startListening()

        // Simulate inbound item
        val transfer = ItemTransfer(
            fromDid = "bud-2",
            toDid = "bud-1",
            itemJson = sampleItem,
            message = "Here's a crystal",
            timestamp = 1000L,
        )
        val data = Json.encodeToString(ItemTransfer.serializer(), transfer).encodeToByteArray()
        between.publish("between.household-1.items.bud-1.inbox", data)

        val quarantined = manager.getQuarantinedItems()
        assertEquals(1, quarantined.size)
        assertEquals("bud-2", quarantined[0].fromDid)
        assertEquals("Here's a crystal", quarantined[0].message)

        manager.stopListening()
    }

    @Test
    fun clearQuarantineRemovesAllItems() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = ItemExchangeManager(between, "bud-1", "household-1")
        manager.startListening()

        // Simulate two inbound items
        for (i in 1..2) {
            val transfer = ItemTransfer(
                fromDid = "bud-$i",
                toDid = "bud-1",
                itemJson = JsonPrimitive("item-$i"),
                timestamp = 1000L + i,
            )
            val data = Json.encodeToString(ItemTransfer.serializer(), transfer).encodeToByteArray()
            between.publish("between.household-1.items.bud-1.inbox", data)
        }

        assertEquals(2, manager.getQuarantinedItems().size)

        manager.clearQuarantine()
        assertEquals(0, manager.getQuarantinedItems().size)

        manager.stopListening()
    }

    @Test
    fun sendWithoutMessageOmitsField() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = ItemExchangeManager(between, "bud-1", "household-1")

        manager.sendItem("bud-2", sampleItem)

        val payload = between.published[0].second.decodeToString()
        val transfer = Json { ignoreUnknownKeys = true }
            .decodeFromString<ItemTransfer>(payload)
        assertEquals(null, transfer.message)
    }
}

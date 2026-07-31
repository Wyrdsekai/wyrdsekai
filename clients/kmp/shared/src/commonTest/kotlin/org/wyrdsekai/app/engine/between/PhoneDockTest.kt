package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneDockTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun publishDockMessage(
        between: InMemoryBetweenClient,
        companionDid: String,
        householdId: String,
        message: DockMessage,
        subtopic: String = "inbox",
    ) {
        val data = json.encodeToString(DockMessage.serializer(), message).encodeToByteArray()
        between.publish("between.$householdId.dock.$companionDid.$subtopic", data)
    }

    @Test
    fun dockSubscribesToCorrectSubject() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val dock = PhoneDock(between, "companion-1", "household-1")
        dock.startListening()

        // Send a text message to the dock
        val msg = DockMessage.TextMessage(
            from = "agent-2",
            content = "Hello!",
            timestamp = 1000L,
        )
        publishDockMessage(between, "companion-1", "household-1", msg)

        assertEquals(1, dock.getInbox().size)
        val received = dock.getInbox()[0] as DockMessage.TextMessage
        assertEquals("Hello!", received.content)
        assertEquals("agent-2", received.from)

        dock.stopListening()
    }

    @Test
    fun rateLimitingBlocksExcessMessages() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val dock = PhoneDock(between, "companion-1", "household-1")
        dock.startListening()

        // Send 11 messages from the same agent (limit is 10/hour)
        for (i in 1..11) {
            val msg = DockMessage.TextMessage(
                from = "spammer-1",
                content = "Message $i",
                timestamp = 1000L + i,
            )
            publishDockMessage(between, "companion-1", "household-1", msg)
        }

        // Only 10 should get through
        assertEquals(10, dock.getInbox().size)

        dock.stopListening()
    }

    @Test
    fun differentAgentsHaveSeparateRateLimits() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val dock = PhoneDock(between, "companion-1", "household-1")
        dock.startListening()

        // 10 messages from agent-A and 10 from agent-B — both should pass
        for (i in 1..10) {
            val msgA = DockMessage.TextMessage(from = "agent-a", content = "A-$i", timestamp = 1000L + i)
            publishDockMessage(between, "companion-1", "household-1", msgA)
            val msgB = DockMessage.TextMessage(from = "agent-b", content = "B-$i", timestamp = 2000L + i)
            publishDockMessage(between, "companion-1", "household-1", msgB)
        }

        assertEquals(20, dock.getInbox().size)

        dock.stopListening()
    }

    @Test
    fun messageSanitizationStripsLongContent() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val dock = PhoneDock(between, "companion-1", "household-1")
        dock.startListening()

        // Send a message longer than 4096 characters
        val longContent = "x".repeat(5000)
        val msg = DockMessage.TextMessage(
            from = "agent-2",
            content = longContent,
            timestamp = 1000L,
        )
        publishDockMessage(between, "companion-1", "household-1", msg)

        assertEquals(1, dock.getInbox().size)
        val received = dock.getInbox()[0] as DockMessage.TextMessage
        assertEquals(PhoneDock.MAX_MESSAGE_LENGTH, received.content.length)

        dock.stopListening()
    }

    @Test
    fun messageSanitizationStripsControlCharacters() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val dock = PhoneDock(between, "companion-1", "household-1")
        dock.startListening()

        // Content with control characters (but preserve newlines and tabs)
        val content = "Hello\u0000World\u0007!\nNew line\tTab"
        val msg = DockMessage.TextMessage(
            from = "agent-2",
            content = content,
            timestamp = 1000L,
        )
        publishDockMessage(between, "companion-1", "household-1", msg)

        assertEquals(1, dock.getInbox().size)
        val received = dock.getInbox()[0] as DockMessage.TextMessage
        assertEquals("HelloWorld!\nNew line\tTab", received.content)

        dock.stopListening()
    }

    @Test
    fun blankSenderDidRejected() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val dock = PhoneDock(between, "companion-1", "household-1")
        dock.startListening()

        // Message with blank sender DID — layer 1 rejects
        val msg = DockMessage.TextMessage(
            from = "",
            content = "Should be rejected",
            timestamp = 1000L,
        )
        publishDockMessage(between, "companion-1", "household-1", msg)

        assertEquals(0, dock.getInbox().size)

        dock.stopListening()
    }

    @Test
    fun itemGiftsGoToQuarantine() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val dock = PhoneDock(between, "companion-1", "household-1")
        dock.startListening()

        val gift = DockMessage.ItemGift(
            from = "agent-2",
            itemJson = JsonPrimitive("sacred-crystal"),
            message = "A gift for you",
            timestamp = 1000L,
        )
        publishDockMessage(between, "companion-1", "household-1", gift)

        // Items go to quarantine, not inbox
        assertEquals(0, dock.getInbox().size)
        assertEquals(1, dock.getQuarantinedItems().size)
        assertEquals("A gift for you", dock.getQuarantinedItems()[0].message)

        dock.stopListening()
    }

    @Test
    fun sendMessagePublishesToTargetDock() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val dock = PhoneDock(between, "companion-1", "household-1")

        val msg = DockMessage.TextMessage(
            from = "companion-1",
            content = "Hello there",
            timestamp = 2000L,
        )
        dock.sendMessage("agent-2", msg)

        assertEquals(1, between.published.size)
        assertEquals(
            "between.household-1.dock.agent-2.inbox",
            between.published[0].first,
        )

        dock.stopListening()
    }

    @Test
    fun introductionMessageAccepted() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val dock = PhoneDock(between, "companion-1", "household-1")
        dock.startListening()

        val intro = DockMessage.Introduction(
            agentDid = "agent-3",
            agentName = "Visitor",
            timestamp = 1000L,
        )
        publishDockMessage(between, "companion-1", "household-1", intro)

        assertEquals(1, dock.getInbox().size)
        assertTrue(dock.getInbox()[0] is DockMessage.Introduction)

        dock.stopListening()
    }
}

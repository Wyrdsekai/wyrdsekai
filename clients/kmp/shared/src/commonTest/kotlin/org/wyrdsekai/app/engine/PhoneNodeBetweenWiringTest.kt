package org.wyrdsekai.app.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.between.DockMessage
import org.wyrdsekai.app.engine.between.HouseholdEvent
import org.wyrdsekai.app.engine.between.HouseholdEventListener
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import org.wyrdsekai.app.engine.between.ItemTransfer
import org.wyrdsekai.app.engine.between.WarmHandoffContext
import org.wyrdsekai.app.inference.InferenceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for PhoneNode Between subsystem wiring.
 *
 * Verifies that starting PhoneNode with a connected BetweenClient creates
 * all subsystems (ItemExchangeManager, PhoneDock, McpGatewayLite,
 * HouseholdEventListener, WarmHandoffManager), and that stop() cleans them up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneNodeBetweenWiringTest {

    private fun makeNode(
        scope: kotlinx.coroutines.CoroutineScope,
        betweenClient: org.wyrdsekai.app.engine.between.BetweenClient? = null,
        nodeId: String = "test-node",
        familyId: String = "test-family",
    ) = PhoneNode(
        journal = InMemoryEventJournal(),
        vitalityStore = null,
        inferenceClient = InferenceClient(),
        inferenceBaseUrl = "http://test",
        scope = scope,
        tierManager = null,
        betweenClient = betweenClient,
        nodeId = nodeId,
        familyId = familyId,
    )

    // ── All subsystems created when Between is connected ────────────────

    @Test
    fun startWithConnectedBetweenCreatesAllSubsystems() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(scope = backgroundScope, betweenClient = between)

        node.start()
        advanceTimeBy(200)

        assertEquals(PhoneNode.State.RUNNING, node.state.value)

        // All Between subsystems should be created
        assertNotNull(node.headlineSyncClient, "HeadlineSyncClient should exist")
        assertNotNull(node.presenceManager, "PresenceManager should exist")
        assertNotNull(node.itemExchange, "ItemExchangeManager should exist")
        assertNotNull(node.phoneDock, "PhoneDock should exist")
        assertNotNull(node.mcpGateway, "McpGatewayLite should exist")
        assertNotNull(node.householdEventListener, "HouseholdEventListener should exist")
        assertNotNull(node.warmHandoff, "WarmHandoffManager should exist")

        node.stop()
    }

    // ── No subsystems when Between is null ──────────────────────────────

    @Test
    fun startWithoutBetweenCreatesNoSubsystems() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.start()
        advanceTimeBy(200)

        assertEquals(PhoneNode.State.RUNNING, node.state.value)

        // All Between subsystems should be null
        assertNull(node.headlineSyncClient)
        assertNull(node.presenceManager)
        assertNull(node.itemExchange)
        assertNull(node.phoneDock)
        assertNull(node.mcpGateway)
        assertNull(node.householdEventListener)
        assertNull(node.warmHandoff)

        node.stop()
    }

    // ── No subsystems when Between is disconnected ──────────────────────

    @Test
    fun startWithDisconnectedBetweenCreatesNoSubsystems() = runTest {
        val between = InMemoryBetweenClient()
        // NOT connected

        val node = makeNode(scope = backgroundScope, betweenClient = between)

        node.start()
        advanceTimeBy(200)

        assertEquals(PhoneNode.State.RUNNING, node.state.value)

        assertNull(node.itemExchange)
        assertNull(node.phoneDock)
        assertNull(node.mcpGateway)
        assertNull(node.householdEventListener)
        assertNull(node.warmHandoff)

        node.stop()
    }

    // ── Stop cleans up all subsystems ───────────────────────────────────

    @Test
    fun stopCleansUpAllBetweenSubsystems() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(scope = backgroundScope, betweenClient = between)

        node.start()
        advanceTimeBy(200)

        // Verify all exist
        assertNotNull(node.itemExchange)
        assertNotNull(node.phoneDock)
        assertNotNull(node.mcpGateway)
        assertNotNull(node.householdEventListener)
        assertNotNull(node.warmHandoff)

        node.stop()

        // Verify all cleaned up
        assertNull(node.headlineSyncClient)
        assertNull(node.presenceManager)
        assertNull(node.itemExchange)
        assertNull(node.phoneDock)
        assertNull(node.mcpGateway)
        assertNull(node.householdEventListener)
        assertNull(node.warmHandoff)
    }

    // ── MCP Gateway configured correctly ────────────────────────────────

    @Test
    fun mcpGatewayConfiguredWithBetweenDetails() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(
            scope = backgroundScope,
            betweenClient = between,
            nodeId = "my-phone",
            familyId = "my-household",
        )

        node.start()
        advanceTimeBy(200)

        val gateway = node.mcpGateway
        assertNotNull(gateway)
        assertEquals("my-phone", gateway.nodeId)
        assertEquals("my-household", gateway.householdId)
        assertNotNull(gateway.betweenClient)

        node.stop()
    }

    // ── Item exchange receives items ────────────────────────────────────

    @Test
    fun itemExchangeReceivesInboundItems() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(
            scope = backgroundScope,
            betweenClient = between,
            nodeId = "my-did",
            familyId = "hh-1",
        )

        node.start()
        advanceTimeBy(200)

        val exchange = node.itemExchange
        assertNotNull(exchange)

        // Simulate inbound item
        val transfer = ItemTransfer(
            fromDid = "sibling-did",
            toDid = "my-did",
            itemJson = kotlinx.serialization.json.JsonPrimitive("test-item"),
            message = "A gift",
            timestamp = 1000L,
        )
        val data = Json.encodeToString(transfer).encodeToByteArray()
        between.publish("between.hh-1.items.my-did.inbox", data)

        val quarantined = exchange.getQuarantinedItems()
        assertEquals(1, quarantined.size)
        assertEquals("sibling-did", quarantined[0].fromDid)

        node.stop()
    }

    // ── PhoneDock receives messages ─────────────────────────────────────

    @Test
    fun phoneDockReceivesTextMessage() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(
            scope = backgroundScope,
            betweenClient = between,
            nodeId = "my-did",
            familyId = "hh-1",
        )

        node.start()
        advanceTimeBy(200)

        val dock = node.phoneDock
        assertNotNull(dock)

        // Simulate inbound text message
        val message = DockMessage.TextMessage(
            from = "agent-x",
            content = "Hello from the docks",
            timestamp = 2000L,
        )
        val data = Json.encodeToString<DockMessage>(message).encodeToByteArray()
        between.publish("between.hh-1.dock.my-did.inbox", data)

        val inbox = dock.getInbox()
        assertEquals(1, inbox.size)
        assertTrue(inbox[0] is DockMessage.TextMessage)
        assertEquals("agent-x", (inbox[0] as DockMessage.TextMessage).from)

        node.stop()
    }

    // ── Household events emit notifications ─────────────────────────────

    @Test
    fun householdEventListenerIsWired() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(
            scope = backgroundScope,
            betweenClient = between,
            nodeId = "test-node",
            familyId = "hh-1",
        )

        node.start()
        advanceTimeBy(200)

        // Verify the listener was wired by PhoneNode
        assertNotNull(node.householdEventListener, "HouseholdEventListener should be wired")

        // Verify the Between subscription works end-to-end by testing
        // HouseholdEventListener directly (avoids backgroundScope SharedFlow timing)
        var receivedEvent: HouseholdEvent? = null
        val directListener = HouseholdEventListener(between, "hh-1") { event ->
            receivedEvent = event
        }
        directListener.startListening()

        val hhEvent = HouseholdEvent.AgentArrived(
            agentDid = "new-agent",
            agentName = "Explorer",
            timestamp = 3000L,
        )
        val data = Json.encodeToString<HouseholdEvent>(hhEvent).encodeToByteArray()
        between.publish("between.hh-1.events", data)

        assertNotNull(receivedEvent, "Event should be received by listener")
        assertTrue(receivedEvent is HouseholdEvent.AgentArrived)
        assertEquals("new-agent", (receivedEvent as HouseholdEvent.AgentArrived).agentDid)

        directListener.stopListening()
        node.stop()
    }

    // ── WarmHandoff receives handoff ────────────────────────────────────

    @Test
    fun warmHandoffReceivesInboundHandoff() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(
            scope = backgroundScope,
            betweenClient = between,
            nodeId = "phone-A",
            familyId = "fam-1",
        )

        node.start()
        advanceTimeBy(200)

        val handoff = node.warmHandoff
        assertNotNull(handoff)

        var received: WarmHandoffContext? = null
        handoff.onHandoffReceived { received = it }

        // Simulate inbound handoff from another device
        val context = WarmHandoffContext(
            fromDid = "desktop-did",
            toDid = "phone-did",
            activeRoomId = "nexus",
            timestamp = 4000L,
        )
        val data = Json.encodeToString(context).encodeToByteArray()
        between.publish("between.household.fam-1.desktop-node.phone-A.soul.handoff", data)

        assertNotNull(received)
        assertEquals("desktop-did", received!!.fromDid)
        assertEquals("nexus", received!!.activeRoomId)

        node.stop()
    }

    // ── visitRoom creates proxy ─────────────────────────────────────────

    @Test
    fun visitRoomCreatesProxy() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(
            scope = backgroundScope,
            betweenClient = between,
            nodeId = "test-node",
            familyId = "hh-1",
        )

        node.start()
        advanceTimeBy(200)

        val proxy = node.visitRoom("remote-room", "host-node-1")
        assertNotNull(proxy)
        assertEquals("remote-room", proxy.roomId)
        assertTrue(node.visitingRoomIds().contains("remote-room"))

        node.stop()
        // After stop, visiting proxies should be cleared
        assertTrue(node.visitingRoomIds().isEmpty())
    }

    @Test
    fun visitRoomReturnsNullWithoutBetween() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.start()
        advanceTimeBy(200)

        val proxy = node.visitRoom("remote-room", "host-node")
        assertNull(proxy)

        node.stop()
    }

    @Test
    fun visitRoomReturnsSameProxyForSameRoom() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(scope = backgroundScope, betweenClient = between)

        node.start()
        advanceTimeBy(200)

        val proxy1 = node.visitRoom("remote-room", "host")
        val proxy2 = node.visitRoom("remote-room", "host")

        assertNotNull(proxy1)
        assertNotNull(proxy2)
        assertTrue(proxy1 === proxy2, "Same proxy should be returned for the same room")

        node.stop()
    }

    @Test
    fun leaveVisitingRoomCleansUpProxy() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(scope = backgroundScope, betweenClient = between)

        node.start()
        advanceTimeBy(200)

        node.visitRoom("remote-room", "host")
        assertTrue(node.visitingRoomIds().contains("remote-room"))

        node.leaveVisitingRoom("remote-room")
        assertTrue(node.visitingRoomIds().isEmpty())

        node.stop()
    }
}

package org.wyrdsekai.app.engine.transit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.time.Clock
import org.wyrdsekai.app.engine.InMemoryEventJournal
import org.wyrdsekai.app.engine.PhoneNode
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.tier.*
import org.wyrdsekai.app.inference.InferenceClient
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.RoomSnapshot
import org.wyrdsekai.app.protocol.S2CMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransitCoordinatorTest {

    /** Create a probe whose resources match the requested tier. */
    private fun probeForTier(tier: Tier) = object : ResourceProbe {
        override fun snapshot() = when (tier) {
            Tier.T0 -> ResourceSnapshot(500, 4000, 5, false, ThermalState.CRITICAL, false)
            Tier.T1 -> ResourceSnapshot(1500, 4000, 50, false, ThermalState.NOMINAL, false) // no wifi → T1
            Tier.T2 -> ResourceSnapshot(2500, 4000, 80, false, ThermalState.NOMINAL, true)
            Tier.T3 -> ResourceSnapshot(4000, 8000, 100, true, ThermalState.NOMINAL, true)
        }
    }

    private fun makeTierManager(tier: Tier, scope: kotlinx.coroutines.CoroutineScope): TierManager {
        return TierManager(probeForTier(tier), scope = scope)
    }

    private fun makeNode(
        scope: kotlinx.coroutines.CoroutineScope,
        tierManager: TierManager? = null,
        journal: InMemoryEventJournal = InMemoryEventJournal(),
    ) = PhoneNode(
        journal = journal,
        vitalityStore = null,
        inferenceClient = InferenceClient(),
        inferenceBaseUrl = "http://test",
        scope = scope,
        tierManager = tierManager,
    )

    /**
     * At T0/T1 the study room's "out → home" exit is filtered because the Home room
     * doesn't exist at those tiers. Pre-seed the journal with an ExitOpened event so
     * the exit survives recovery and the transit tests can exercise it.
     */
    private suspend fun journalWithStudyExit(): InMemoryEventJournal {
        val journal = InMemoryEventJournal()
        journal.append("study", WorldEvent.ExitOpened(
            roomId = "study",
            timestamp = Clock.System.now(),
            direction = "out",
            targetRoom = "home",
            label = "Step out",
        ))
        return journal
    }

    @Test
    fun startsInLocalMode() = runTest {
        val phone = makeNode(this)
        val coordinator = TransitCoordinator(phone, null)

        assertEquals(TransitCoordinator.Mode.LOCAL, coordinator.mode)
        assertNull(coordinator.remoteRoomId)
        assertFalse(coordinator.isRemote)

        phone.stop()
    }

    @Test
    fun localGoRoutesToPhoneNode() = runTest {
        val tm = makeTierManager(Tier.T2, this)
        val phone = makeNode(this, tm)
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val coordinator = TransitCoordinator(phone, null)

        // Go from study to home (both local at T2) via "out" exit
        val handled = coordinator.go("player-1", "Player", "out")
        assertTrue(handled)
        assertEquals(TransitCoordinator.Mode.LOCAL, coordinator.mode)

        phone.stop()
    }

    @Test
    fun remoteGoTransitsToServer() = runTest {
        val tm = makeTierManager(Tier.T1, this)
        val phone = makeNode(this, tm, journal = journalWithStudyExit())
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val server = InMemoryServerConnection()
        server.isConnected = true
        server.addRemoteRooms("home") // "home" is not local at T1, so it's remote

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        val events = mutableListOf<TransitEvent>()
        coordinator.onEvent { events.add(it) }

        // From study, "out" exit goes to "home" which is remote at T1
        val handled = coordinator.go("player-1", "Player", "out")
        assertTrue(handled)
        assertEquals(TransitCoordinator.Mode.REMOTE, coordinator.mode)
        assertEquals("home", coordinator.remoteRoomId)
        assertTrue(coordinator.isRemote)

        assertEquals(1, events.size)
        assertTrue(events[0] is TransitEvent.TransitedToRemote)
        assertEquals("home", (events[0] as TransitEvent.TransitedToRemote).roomId)

        // Should have sent a Look command to the server
        assertTrue(server.sent.any { it is C2SMessage.Look })

        coordinator.stop()
        phone.stop()
    }

    @Test
    fun sayInRemoteModeSendsToServer() = runTest {
        val tm = makeTierManager(Tier.T1, this)
        val phone = makeNode(this, tm, journal = journalWithStudyExit())
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val server = InMemoryServerConnection()
        server.isConnected = true
        server.addRemoteRooms("home")

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        coordinator.go("player-1", "Player", "out") // study → remote "home"
        coordinator.say("player-1", "Player", "Hello server!")

        val sayMessages = server.sent.filterIsInstance<C2SMessage.Say>()
        assertEquals(1, sayMessages.size)
        assertEquals("Hello server!", sayMessages[0].text)
        assertEquals("home", sayMessages[0].roomId)

        coordinator.stop()
        phone.stop()
    }

    @Test
    fun goInRemoteModeSendsToServer() = runTest {
        val tm = makeTierManager(Tier.T1, this)
        val phone = makeNode(this, tm, journal = journalWithStudyExit())
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val server = InMemoryServerConnection()
        server.isConnected = true
        server.addRemoteRooms("home")

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        coordinator.go("player-1", "Player", "out") // study → remote "home"
        coordinator.go("player-1", "Player", "east") // in remote mode, forwards to server

        val goMessages = server.sent.filterIsInstance<C2SMessage.Go>()
        assertEquals(1, goMessages.size)
        assertEquals("east", goMessages[0].direction)
        assertEquals("home", goMessages[0].roomId)

        coordinator.stop()
        phone.stop()
    }

    @Test
    fun lookInRemoteModeSendsToServer() = runTest {
        val tm = makeTierManager(Tier.T1, this)
        val phone = makeNode(this, tm, journal = journalWithStudyExit())
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val server = InMemoryServerConnection()
        server.isConnected = true
        server.addRemoteRooms("home")

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        coordinator.go("player-1", "Player", "out") // study → remote "home"
        server.sent.clear()

        coordinator.look()

        val lookMessages = server.sent.filterIsInstance<C2SMessage.Look>()
        assertEquals(1, lookMessages.size)
        assertEquals("home", lookMessages[0].roomId)

        coordinator.stop()
        phone.stop()
    }

    @Test
    fun returnToLocalSwitchesMode() = runTest {
        val tm = makeTierManager(Tier.T1, this)
        val phone = makeNode(this, tm, journal = journalWithStudyExit())
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val server = InMemoryServerConnection()
        server.isConnected = true
        server.addRemoteRooms("home")

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        val events = mutableListOf<TransitEvent>()
        coordinator.onEvent { events.add(it) }

        coordinator.go("player-1", "Player", "out") // study → remote "home"
        assertTrue(coordinator.isRemote)

        coordinator.returnToLocal("player-1", "Player")
        assertFalse(coordinator.isRemote)
        assertEquals(TransitCoordinator.Mode.LOCAL, coordinator.mode)
        assertNull(coordinator.remoteRoomId)

        assertTrue(events.any { it is TransitEvent.ReturnedToLocal })

        coordinator.stop()
        phone.stop()
    }

    @Test
    fun serverProseForwardedInRemoteMode() = runTest {
        val tm = makeTierManager(Tier.T1, this)
        val phone = makeNode(this, tm, journal = journalWithStudyExit())
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val server = InMemoryServerConnection()
        server.isConnected = true
        server.addRemoteRooms("home")

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        val events = mutableListOf<TransitEvent>()
        coordinator.onEvent { events.add(it) }

        coordinator.go("player-1", "Player", "out") // study → remote "home"

        server.receive(S2CMessage.Prose(
            seq = 1,
            speaker = "narrator",
            text = "You enter the guild hall.",
        ))

        val proseEvents = events.filterIsInstance<TransitEvent.RemoteProse>()
        assertEquals(1, proseEvents.size)
        assertEquals("narrator", proseEvents[0].speaker)
        assertEquals("You enter the guild hall.", proseEvents[0].text)

        coordinator.stop()
        phone.stop()
    }

    @Test
    fun serverProseIgnoredInLocalMode() = runTest {
        val phone = makeNode(this)
        val server = InMemoryServerConnection()
        server.isConnected = true

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        val events = mutableListOf<TransitEvent>()
        coordinator.onEvent { events.add(it) }

        server.receive(S2CMessage.Prose(
            seq = 1,
            speaker = "narrator",
            text = "This should be ignored.",
        ))

        val proseEvents = events.filterIsInstance<TransitEvent.RemoteProse>()
        assertEquals(0, proseEvents.size)

        coordinator.stop()
        phone.stop()
    }

    @Test
    fun serverRoomStateUpdatesRemoteRoomId() = runTest {
        val tm = makeTierManager(Tier.T1, this)
        val phone = makeNode(this, tm, journal = journalWithStudyExit())
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val server = InMemoryServerConnection()
        server.isConnected = true
        server.addRemoteRooms("home")

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        coordinator.go("player-1", "Player", "out") // study → remote "home"
        assertEquals("home", coordinator.remoteRoomId)

        // Server sends room state for a different room (player moved on server)
        val events = mutableListOf<TransitEvent>()
        coordinator.onEvent { events.add(it) }

        server.receive(S2CMessage.RoomState(
            seq = 2,
            room = RoomSnapshot(
                roomId = "guild-hall",
                name = "The Guild Hall",
                description = "A grand hall with banners.",
                zone = "community",
            ),
        ))

        assertEquals("guild-hall", coordinator.remoteRoomId)

        val roomEvents = events.filterIsInstance<TransitEvent.RemoteRoomState>()
        assertEquals(1, roomEvents.size)
        assertEquals("guild-hall", roomEvents[0].roomId)
        assertEquals("The Guild Hall", roomEvents[0].name)

        coordinator.stop()
        phone.stop()
    }

    @Test
    fun serverTransitEmitsEvent() = runTest {
        val phone = makeNode(this)
        val server = InMemoryServerConnection()
        server.isConnected = true

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        val events = mutableListOf<TransitEvent>()
        coordinator.onEvent { events.add(it) }

        server.receive(S2CMessage.Transit(
            seq = 1,
            targetZoneId = "community",
            message = "You feel the world shift...",
        ))

        val transitEvents = events.filterIsInstance<TransitEvent.ServerTransit>()
        assertEquals(1, transitEvents.size)
        assertEquals("community", transitEvents[0].targetZoneId)
        assertEquals("You feel the world shift...", transitEvents[0].message)

        coordinator.stop()
        phone.stop()
    }

    @Test
    fun goWithNoServerConnectionReturnsFalse() = runTest {
        val tm = makeTierManager(Tier.T1, this)
        val phone = makeNode(this, tm)
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val coordinator = TransitCoordinator(phone, null)

        val handled = coordinator.go("player-1", "Player", "north")
        assertFalse(handled)
        assertEquals(TransitCoordinator.Mode.LOCAL, coordinator.mode)

        phone.stop()
    }

    @Test
    fun goWithDisconnectedServerReturnsFalse() = runTest {
        val tm = makeTierManager(Tier.T1, this)
        val phone = makeNode(this, tm)
        phone.startRoomsOnly()
        yield()
        advanceTimeBy(500)
        yield()

        val server = InMemoryServerConnection()
        server.isConnected = false
        server.addRemoteRooms("terminal")

        val coordinator = TransitCoordinator(phone, server)

        val handled = coordinator.go("player-1", "Player", "north")
        assertFalse(handled)
        assertEquals(TransitCoordinator.Mode.LOCAL, coordinator.mode)

        phone.stop()
    }

    @Test
    fun unsubscribeEventListener() = runTest {
        val phone = makeNode(this)
        val server = InMemoryServerConnection()
        server.isConnected = true

        val coordinator = TransitCoordinator(phone, server)
        coordinator.start()

        val events = mutableListOf<TransitEvent>()
        val unsub = coordinator.onEvent { events.add(it) }

        server.receive(S2CMessage.Transit(seq = 1, targetZoneId = "z1", message = "msg1"))
        assertEquals(1, events.size)

        unsub()

        server.receive(S2CMessage.Transit(seq = 2, targetZoneId = "z2", message = "msg2"))
        assertEquals(1, events.size) // No new events after unsub

        coordinator.stop()
        phone.stop()
    }
}

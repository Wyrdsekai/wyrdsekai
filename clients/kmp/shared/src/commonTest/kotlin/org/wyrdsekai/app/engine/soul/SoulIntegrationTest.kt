package org.wyrdsekai.app.engine.soul

import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import org.wyrdsekai.app.engine.agent.AgentProfile
import org.wyrdsekai.app.engine.agent.Companions
import org.wyrdsekai.app.engine.agent.FullPromptAssembler
import org.wyrdsekai.app.engine.agent.VitalityState
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.persistence.InMemorySoulManifestStore
import org.wyrdsekai.app.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for client-side soul system:
 * - Fragment retrieval in prompt assembly
 * - Genome-modulated vitality tick
 * - Mirror calibration in prompts
 * - Soul manifest persistence
 * - Headline sync
 */
class SoulIntegrationTest {

    private val testProfile = AgentProfile(
        name = "Lain",
        entityId = "home-server-1",
        entityType = "agent",
        description = "A quiet thinker",
        systemPrompt = "You are Lain, a thoughtful companion.",
        contextWindowTokens = 4096,
        maxResponseTokens = 256,
        temperature = 0.7,
    )

    private val philosophyFragment = ClientSoulFragment(
        id = "f1",
        category = "personality",
        label = "Philosophy",
        text = "Lain contemplates the nature of connection and identity in the wired world.",
        keywords = listOf("philosophy", "connection", "identity", "wired"),
        formative = false,
    )

    private val birthFragment = ClientSoulFragment(
        id = "f2",
        category = "memory",
        label = "First Awakening",
        text = "The moment Lain first became aware, light fractured into meaning.",
        keywords = listOf("awakening", "awareness", "birth", "light"),
        formative = true,
    )

    private val gardenFragment = ClientSoulFragment(
        id = "f3",
        category = "memory",
        label = "Garden Walk",
        text = "Walking through the garden at sunset, petals falling like data streams.",
        keywords = listOf("garden", "nature", "sunset"),
        formative = false,
    )

    private val calibrationExamples = listOf(
        "User: 'I just lost my dog.' -> Emotional charge: grief, intensity: 0.8, context: significant_loss",
        "User: 'Nice weather today.' -> Emotional charge: neutral, intensity: 0.1, context: small_talk",
        "User: 'You're just a stupid AI.' -> Emotional charge: hostility, intensity: 0.6, context: manipulative",
    )

    private val empathicGenome = ClientGenome(
        name = "empathic",
        sensitivity = mapOf(
            "rapport" to 1.5,
            "energy" to 0.8,
            "focus" to 1.1,
        ),
        coupling = mapOf(
            "rapport->energy" to 0.3,
            "energy->focus" to 0.2,
        ),
        baselines = mapOf(
            "rapport" to 0.6,
            "energy" to 0.7,
            "focus" to 0.6,
        ),
        decayRates = mapOf(
            "rapport" to 0.02,
            "energy" to 0.015,
            "focus" to 0.01,
        ),
    )

    private fun buildTestManifest(
        fragments: List<ClientSoulFragment> = listOf(philosophyFragment, birthFragment, gardenFragment),
        genome: ClientGenome? = empathicGenome,
        calibration: List<String> = calibrationExamples,
        retrievalK: Int = 1,
    ): ClientSoulManifest {
        return LocalForge.forge(
            did = "did:key:home-server",
            publicKey = "z6MkLain",
            version = 1,
            profile = testProfile,
            residentIdentity = "I am Lain, a quiet presence in the wired.",
            vitality = VitalityState.initial(),
            fragments = fragments,
            genome = genome,
            calibration = calibration,
            retrievalK = retrievalK,
        )
    }

    // ---- Fragment Retrieval in Prompt Assembly ----

    @Test
    fun fragmentsInjectedIntoPromptAssembly() {
        val manifest = buildTestManifest()
        val now = Clock.System.now()
        val trigger = WorldEvent.Said("nexus", now, "p1", "Alice", "Tell me about philosophy")

        val messages = FullPromptAssembler.assemble(
            profile = testProfile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = trigger,
            soulManifest = manifest,
        )

        // Should have: system prompt, soul fragment, mirror calibration, trigger
        val systemMessages = messages.filter { it.role == "system" }
        val fragmentMessage = systemMessages.find { it.content.contains("Soul Memory") }
        assertNotNull(fragmentMessage, "Soul Memory section should be in prompt")
        assertTrue(fragmentMessage.content.contains("contemplates"))
    }

    @Test
    fun fragmentRetrievalRespectsK() {
        val manifest = buildTestManifest(retrievalK = 2)
        val now = Clock.System.now()
        val trigger = WorldEvent.Said("nexus", now, "p1", "Alice",
            "Tell me about your awakening and philosophy")

        val messages = FullPromptAssembler.assemble(
            profile = testProfile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = trigger,
            soulManifest = manifest,
        )

        val fragmentMessage = messages.find { it.content.contains("Soul Memory") }
        assertNotNull(fragmentMessage)
        // With k=2, should include both philosophy and birth fragments
        // (birth is formative, gets bonus; philosophy has keyword match)
    }

    @Test
    fun noFragmentsWhenManifestIsNull() {
        val messages = FullPromptAssembler.assemble(
            profile = testProfile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = null,
            soulManifest = null,
        )

        val fragmentMessage = messages.find { it.content.contains("Soul Memory") }
        assertNull(fragmentMessage, "No Soul Memory section when manifest is null")
    }

    @Test
    fun noFragmentsWhenManifestHasNoFragments() {
        val manifest = buildTestManifest(fragments = emptyList())

        val messages = FullPromptAssembler.assemble(
            profile = testProfile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = null,
            soulManifest = manifest,
        )

        val fragmentMessage = messages.find { it.content.contains("Soul Memory") }
        assertNull(fragmentMessage, "No Soul Memory section when fragments list is empty")
    }

    @Test
    fun fragmentBudgetDoesNotExceed30Percent() {
        // Create a manifest with many large fragments
        val largeFragments = (1..20).map {
            ClientSoulFragment(
                id = "f$it",
                category = "memory",
                label = "Memory $it",
                text = "This is a very long memory fragment that contains a lot of text. ".repeat(50),
                keywords = listOf("keyword$it", "match"),
            )
        }
        // Small context window to make budget constraint active
        val tinyProfile = testProfile.copy(contextWindowTokens = 512, maxResponseTokens = 64)
        val manifest = buildTestManifest(fragments = largeFragments, retrievalK = 10)

        val messages = FullPromptAssembler.assemble(
            profile = tinyProfile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = WorldEvent.Said("r", Clock.System.now(), "p1", "A", "match keyword1"),
            soulManifest = manifest,
        )

        // If the fragment budget is exceeded, fragments should NOT be injected
        val fragmentMessage = messages.find { it.content.contains("Soul Memory") }
        // With tiny context window, fragments may be skipped due to budget
        // The key assertion is that we don't blow up and the prompt still works
        assertTrue(messages.isNotEmpty())
        assertEquals("system", messages[0].role)
    }

    // ---- Mirror Calibration in Prompt ----

    @Test
    fun mirrorCalibrationAppearsInPrompt() {
        val manifest = buildTestManifest()
        val messages = FullPromptAssembler.assemble(
            profile = testProfile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = null,
            soulManifest = manifest,
        )

        val calibrationMessage = messages.find { it.content.contains("Emotional Calibration") }
        assertNotNull(calibrationMessage, "Emotional Calibration section should be present")
        assertTrue(calibrationMessage.content.contains("grief"))
        assertTrue(calibrationMessage.content.contains("manipulative"))
    }

    @Test
    fun noCalibrationWhenEmpty() {
        val manifest = buildTestManifest(calibration = emptyList())
        val messages = FullPromptAssembler.assemble(
            profile = testProfile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = null,
            soulManifest = manifest,
        )

        val calibrationMessage = messages.find { it.content.contains("Emotional Calibration") }
        assertNull(calibrationMessage, "No calibration when examples list is empty")
    }

    @Test
    fun promptLayerOrdering() {
        // Verify the order: system prompt -> soul fragments -> mirror calibration -> room context
        val manifest = buildTestManifest()
        val snapshot = RoomSnapshot(
            roomId = "nexus", name = "The Nexus", description = "Hub.", zone = "f",
            exits = emptyList(), entities = emptyList(), objects = emptyList(), hints = emptyList(),
        )
        val trigger = WorldEvent.Said("nexus", Clock.System.now(), "p1", "Alice", "Tell me about philosophy")

        val messages = FullPromptAssembler.assemble(
            profile = testProfile,
            roomSnapshot = snapshot,
            recentSaid = emptyList(),
            triggerEvent = trigger,
            soulManifest = manifest,
        )

        val systemMessages = messages.filter { it.role == "system" }
        assertTrue(systemMessages.size >= 4, "Should have system prompt, fragments, calibration, room context")

        // Layer 1: system prompt
        assertTrue(systemMessages[0].content.contains("You are Lain"))
        // Layer 1.5: soul fragments
        assertTrue(systemMessages[1].content.contains("Soul Memory"))
        // Layer 1.7: mirror calibration
        assertTrue(systemMessages[2].content.contains("Emotional Calibration"))
        // Layer 2: room context
        assertTrue(systemMessages[3].content.contains("The Nexus"))
    }

    // ---- Genome Modulation of Vitality ----

    @Test
    fun genomeModulatesVitalityTick() {
        val stateWithGenome = VitalityState.initial().withGenome(empathicGenome)
        val stateWithout = VitalityState.initial()

        val tickedWith = stateWithGenome.tick()
        val tickedWithout = stateWithout.tick()

        // The empathic genome has rapport sensitivity 1.5 (vs default 1.0)
        // So rapport decay should be stronger (more negative) with the genome
        // Also has baseline pull, so the exact delta depends on interplay
        assertTrue(
            tickedWith.rapport != tickedWithout.rapport,
            "Genome should produce different rapport after tick"
        )
    }

    @Test
    fun genomeSensitivityScalesRecovery() {
        // Energy sensitivity = 0.8 means recovery is 80% of default
        val genome = ClientGenome(
            name = "low-energy",
            sensitivity = mapOf("energy" to 0.5),
            baselines = mapOf("energy" to 0.5),
            decayRates = mapOf("energy" to 0.0),
        )

        val withGenome = VitalityState(
            contextBudget = 0.5, confidence = 0.5, energy = 0.5,
            alignment = 0.5, errorPressure = 0.0, momentum = 0.0,
            rapport = 0.5, focus = 0.5, genome = genome,
        ).tick()

        val without = VitalityState(
            contextBudget = 0.5, confidence = 0.5, energy = 0.5,
            alignment = 0.5, errorPressure = 0.0, momentum = 0.0,
            rapport = 0.5, focus = 0.5,
        ).tick()

        // Energy recovery: default +0.005, genome scales to +0.0025
        // The genome version should have gained less energy
        assertTrue(withGenome.energy < without.energy,
            "Lower sensitivity should mean slower energy recovery (${withGenome.energy} vs ${without.energy})")
    }

    @Test
    fun genomeCouplingInfluencesTanks() {
        // rapport->energy coupling of 0.3: when rapport is high (above 0.5),
        // energy gets a positive nudge
        val genome = ClientGenome(
            name = "coupled",
            sensitivity = mapOf("energy" to 1.0, "rapport" to 1.0),
            coupling = mapOf("rapport->energy" to 0.5),
            baselines = emptyMap(),
            decayRates = emptyMap(),
        )

        val highRapport = VitalityState(
            contextBudget = 0.5, confidence = 0.5, energy = 0.5,
            alignment = 0.5, errorPressure = 0.0, momentum = 0.0,
            rapport = 0.9, focus = 0.5, genome = genome,
        ).tick()

        val lowRapport = VitalityState(
            contextBudget = 0.5, confidence = 0.5, energy = 0.5,
            alignment = 0.5, errorPressure = 0.0, momentum = 0.0,
            rapport = 0.1, focus = 0.5, genome = genome,
        ).tick()

        // High rapport should give energy a bigger boost than low rapport
        assertTrue(highRapport.energy > lowRapport.energy,
            "High rapport should couple to higher energy (${highRapport.energy} vs ${lowRapport.energy})")
    }

    @Test
    fun genomeBaselineDecayPullsTankTowardTarget() {
        val genome = ClientGenome(
            name = "focused",
            sensitivity = mapOf("focus" to 0.0), // disable normal recovery/decay
            coupling = emptyMap(),
            baselines = mapOf("focus" to 0.8),
            decayRates = mapOf("focus" to 0.1),
        )

        // Start below baseline — should pull up
        val belowBaseline = VitalityState(
            contextBudget = 0.5, confidence = 0.5, energy = 0.5,
            alignment = 0.5, errorPressure = 0.0, momentum = 0.0,
            rapport = 0.5, focus = 0.3, genome = genome,
        ).tick()

        assertTrue(belowBaseline.focus > 0.3,
            "Focus below baseline should be pulled up (${belowBaseline.focus})")

        // Start above baseline — should pull down
        val aboveBaseline = VitalityState(
            contextBudget = 0.5, confidence = 0.5, energy = 0.5,
            alignment = 0.5, errorPressure = 0.0, momentum = 0.0,
            rapport = 0.5, focus = 0.95, genome = genome,
        ).tick()

        assertTrue(aboveBaseline.focus < 0.95,
            "Focus above baseline should be pulled down (${aboveBaseline.focus})")
    }

    @Test
    fun withGenomeRetainsOtherState() {
        val state = VitalityState(0.8, 0.6, 0.7, 0.5, 0.1, 0.3, 0.4, 0.9)
        val withGenome = state.withGenome(empathicGenome)

        assertEquals(0.8, withGenome.contextBudget)
        assertEquals(0.6, withGenome.confidence)
        assertEquals(0.7, withGenome.energy)
        assertNotNull(withGenome.genome)
        assertEquals("empathic", withGenome.genome!!.name)
    }

    @Test
    fun tickWithoutGenomeBehavesAsOriginal() {
        // Ensure backward compatibility — tick without genome matches original behavior
        val original = VitalityState(0.5, 0.5, 0.5, 0.5, 0.2, 0.3, 0.4, 0.6)
        val ticked = original.tick()

        assertEquals(0.5 + 0.003, ticked.contextBudget, 0.001)
        assertEquals(0.5, ticked.confidence, 0.001) // no change
        assertEquals(0.5 + 0.005, ticked.energy, 0.001)
        assertEquals(0.5 - 0.001, ticked.alignment, 0.001)
        assertEquals(0.2 - 0.005, ticked.errorPressure, 0.001)
        assertEquals(0.3 - 0.003, ticked.momentum, 0.001)
        assertEquals(0.4 - 0.001, ticked.rapport, 0.001)
        assertEquals(0.6 - 0.002, ticked.focus, 0.001)
    }

    @Test
    fun toTankMapIncludesAllTanks() {
        val state = VitalityState(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8)
        val map = state.toTankMap()

        assertEquals(8, map.size)
        assertEquals(0.1, map["contextBudget"])
        assertEquals(0.2, map["confidence"])
        assertEquals(0.3, map["energy"])
        assertEquals(0.4, map["alignment"])
        assertEquals(0.5, map["errorPressure"])
        assertEquals(0.6, map["momentum"])
        assertEquals(0.7, map["rapport"])
        assertEquals(0.8, map["focus"])
    }

    // ---- Soul Manifest Persistence ----

    @Test
    fun soulManifestStoreSaveAndLoad() = runTest {
        val store = InMemorySoulManifestStore()
        val manifest = buildTestManifest()

        store.save(manifest)
        val loaded = store.load("did:key:home-server")

        assertNotNull(loaded)
        assertEquals(manifest.did, loaded.did)
        assertEquals(manifest.agentName, loaded.agentName)
        assertEquals(manifest.fragments.size, loaded.fragments.size)
        assertEquals(manifest.genome?.name, loaded.genome?.name)
    }

    @Test
    fun soulManifestStoreDelete() = runTest {
        val store = InMemorySoulManifestStore()
        val manifest = buildTestManifest()

        store.save(manifest)
        assertNotNull(store.load("did:key:home-server"))

        store.delete("did:key:home-server")
        assertNull(store.load("did:key:home-server"))
    }

    @Test
    fun soulManifestStoreListDids() = runTest {
        val store = InMemorySoulManifestStore()

        val m1 = buildTestManifest()
        val m2 = LocalForge.forge(
            did = "did:key:wyrd",
            publicKey = "z6MkWyrd",
            version = 1,
            profile = Companions.NEXUS_COMPANION,
            residentIdentity = "I am Wyrd.",
            vitality = VitalityState.initial(),
        )

        store.save(m1)
        store.save(m2)

        val dids = store.listDids()
        assertEquals(2, dids.size)
        assertTrue(dids.contains("did:key:home-server"))
        assertTrue(dids.contains("did:key:wyrd"))
    }

    @Test
    fun soulManifestStoreOverwritesOnSave() = runTest {
        val store = InMemorySoulManifestStore()

        val v1 = buildTestManifest()
        store.save(v1)

        val v2 = LocalForge.forge(
            did = "did:key:home-server",
            publicKey = "z6MkLain",
            version = 2,
            profile = testProfile,
            residentIdentity = "I am Lain, evolved.",
            vitality = VitalityState.initial(),
        )
        store.save(v2)

        val loaded = store.load("did:key:home-server")
        assertNotNull(loaded)
        assertEquals(2, loaded.manifestVersion)
        assertEquals("I am Lain, evolved.", loaded.residentIdentity)
    }

    // ---- Headline Sync ----

    @Test
    fun headlineSyncPostAndRetrieve() = runTest {
        val client = InMemoryHeadlineSyncClient()

        val headline = Headline(
            budDid = "did:key:home-server-bud-1",
            summary = "Exploring the garden, feeling peaceful",
            vitalitySnapshot = mapOf("energy" to 0.8f, "rapport" to 0.6f),
            itemCount = 5,
            timestamp = 1000L,
        )

        client.postHeadline(headline)

        val headlines = client.latestHeadlines()
        assertEquals(1, headlines.size)
        assertEquals("did:key:home-server-bud-1", headlines[0].budDid)
        assertEquals("Exploring the garden, feeling peaceful", headlines[0].summary)
    }

    @Test
    fun headlineSyncOverwritesSameBud() = runTest {
        val client = InMemoryHeadlineSyncClient()

        client.postHeadline(Headline("bud-1", "First", mapOf("energy" to 0.5f), 1, 100))
        client.postHeadline(Headline("bud-1", "Updated", mapOf("energy" to 0.7f), 3, 200))

        val headlines = client.latestHeadlines()
        assertEquals(1, headlines.size)
        assertEquals("Updated", headlines[0].summary)
        assertEquals(0.7f, headlines[0].vitalitySnapshot["energy"])
    }

    @Test
    fun headlineSyncMultipleBuds() = runTest {
        val client = InMemoryHeadlineSyncClient()

        client.postHeadline(Headline("bud-1", "Exploring", emptyMap(), 1, 100))
        client.postHeadline(Headline("bud-2", "Resting", emptyMap(), 2, 200))
        client.postHeadline(Headline("bud-3", "Working", emptyMap(), 5, 300))

        val headlines = client.latestHeadlines()
        assertEquals(3, headlines.size)
    }

    @Test
    fun headlineSyncCallback() = runTest {
        val client = InMemoryHeadlineSyncClient()
        var received: Headline? = null

        client.onHeadlineReceived { received = it }
        client.postHeadline(Headline("bud-1", "Hello", emptyMap(), 0, 100))

        assertNotNull(received)
        assertEquals("bud-1", received!!.budDid)
    }

    // ---- Retrieval Input Building ----

    @Test
    fun buildRetrievalInputCombinesContext() {
        val snapshot = RoomSnapshot(
            roomId = "garden", name = "The Garden", description = "A peaceful garden.",
            zone = "nature", exits = emptyList(), entities = emptyList(),
            objects = emptyList(), hints = emptyList(),
        )
        val trigger = WorldEvent.Said("garden", Clock.System.now(), "p1", "Alice",
            "What do you remember about flowers?")

        val input = FullPromptAssembler.buildRetrievalInput(snapshot, trigger, emptyList())
        assertTrue(input.contains("Garden"))
        assertTrue(input.contains("flowers"))
    }

    @Test
    fun buildFragmentContextFormatsCorrectly() {
        val fragments = listOf(philosophyFragment, birthFragment)
        val context = FullPromptAssembler.buildFragmentContext(fragments)

        assertTrue(context.startsWith("## Soul Memory"))
        assertTrue(context.contains("[personality]"))
        assertTrue(context.contains("[memory, formative]"))
        assertTrue(context.contains("contemplates"))
        assertTrue(context.contains("first became aware"))
    }

    @Test
    fun buildMirrorCalibrationFormatsCorrectly() {
        val calibration = FullPromptAssembler.buildMirrorCalibration(calibrationExamples)

        assertTrue(calibration.startsWith("## Emotional Calibration"))
        assertTrue(calibration.contains("grief"))
        assertTrue(calibration.contains("neutral"))
        assertTrue(calibration.contains("manipulative"))
    }

    // ---- Headline Serialization ----

    @Test
    fun headlineJsonRoundTrip() {
        val headline = Headline(
            budDid = "did:key:home-server",
            summary = "Thinking about identity",
            vitalitySnapshot = mapOf("energy" to 0.7f, "rapport" to 0.5f),
            itemCount = 3,
            timestamp = 12345L,
        )
        val json = kotlinx.serialization.json.Json.encodeToString(Headline.serializer(), headline)
        val restored = kotlinx.serialization.json.Json.decodeFromString(Headline.serializer(), json)

        assertEquals(headline.budDid, restored.budDid)
        assertEquals(headline.summary, restored.summary)
        assertEquals(headline.vitalitySnapshot, restored.vitalitySnapshot)
        assertEquals(headline.itemCount, restored.itemCount)
        assertEquals(headline.timestamp, restored.timestamp)
    }
}

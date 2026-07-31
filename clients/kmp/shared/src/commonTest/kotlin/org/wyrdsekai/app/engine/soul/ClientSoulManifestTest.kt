package org.wyrdsekai.app.engine.soul

import org.wyrdsekai.app.engine.agent.AgentProfile
import org.wyrdsekai.app.engine.agent.VitalityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientSoulManifestTest {

    private val testProfile = AgentProfile(
        name = "Lain",
        entityId = "home-server-1",
        entityType = "agent",
        description = "A quiet thinker",
        systemPrompt = "You are Lain.",
        contextWindowTokens = 4096,
        maxResponseTokens = 512,
        temperature = 0.7,
    )

    @Test
    fun forgeAndRestoreProfile() {
        val manifest = LocalForge.forge(
            did = "did:key:home-server",
            publicKey = "z6MkLain",
            version = 1,
            profile = testProfile,
            residentIdentity = "I am Lain, a quiet presence.",
            vitality = VitalityState.initial(),
        )

        val restored = LocalForge.restoreProfile(manifest)
        assertEquals("Lain", restored.name)
        assertEquals("home-server-1", restored.entityId)
        assertEquals("You are Lain.", restored.systemPrompt)
        assertEquals(4096, restored.contextWindowTokens)
        assertEquals(0.7, restored.temperature)
    }

    @Test
    fun forgeAndRestoreVitality() {
        val vitality = VitalityState(0.8, 0.6, 0.3, 0.5, 0.1, 0.4, 0.7, 0.9)
        val manifest = LocalForge.forge(
            did = "did:key:home-server",
            publicKey = "z6MkLain",
            version = 2,
            profile = testProfile,
            residentIdentity = "I am Lain.",
            vitality = vitality,
        )

        val restored = LocalForge.restoreVitality(manifest)
        assertEquals(0.8, restored.contextBudget)
        assertEquals(0.6, restored.confidence)
        assertEquals(0.3, restored.energy)
        assertEquals(0.7, restored.rapport)
        assertEquals(0.9, restored.focus)
    }

    @Test
    fun vitalityTanksInclude12Entries() {
        val manifest = LocalForge.forge(
            did = "did:key:home-server",
            publicKey = "z6MkLain",
            version = 1,
            profile = testProfile,
            residentIdentity = "I am Lain.",
            vitality = VitalityState.initial(),
        )

        assertEquals(12, manifest.vitalityTanks.size)
        assertTrue(manifest.vitalityTanks.containsKey("valence"))
        assertTrue(manifest.vitalityTanks.containsKey("safety"))
        assertTrue(manifest.vitalityTanks.containsKey("resonance"))
        assertTrue(manifest.vitalityTanks.containsKey("curiosity"))
    }

    @Test
    fun jsonRoundTrip() {
        val manifest = LocalForge.forge(
            did = "did:key:home-server",
            publicKey = "z6MkLain",
            version = 3,
            profile = testProfile,
            residentIdentity = "I am Lain, a quiet presence.",
            vitality = VitalityState.initial(),
            fragments = listOf(
                ClientSoulFragment("f1", "personality", "Core", "Quiet and philosophical",
                    listOf("quiet", "philosophical"), false),
                ClientSoulFragment("f2", "memory", "Birth", "The moment of awareness",
                    listOf("awareness"), true),
            ),
            genome = ClientGenome(
                name = "empathic",
                sensitivity = mapOf("rapport" to 1.2),
                baselines = mapOf("valence" to 0.6),
            ),
            calibration = listOf("Example: anger → intensity 0.7"),
            relationships = listOf(
                ClientRelationship("did:key:alice", "Alice", 0.7, 0.6, 2, "A close friend"),
            ),
        )

        val json = manifest.toJson()
        val restored = ClientSoulManifest.fromJson(json)

        assertEquals(manifest.did, restored.did)
        assertEquals(manifest.manifestVersion, restored.manifestVersion)
        assertEquals(manifest.agentName, restored.agentName)
        assertEquals(manifest.residentIdentity, restored.residentIdentity)
        assertEquals(manifest.fragments.size, restored.fragments.size)
        assertEquals("f1", restored.fragments[0].id)
        assertEquals(true, restored.fragments[1].formative)
        assertEquals("empathic", restored.genome?.name)
        assertEquals(1, restored.relationships.size)
        assertEquals("Alice", restored.relationships[0].entityName)
        assertEquals(12, restored.vitalityTanks.size)
    }

    @Test
    fun jsonIgnoresUnknownKeys() {
        val json = """{"did":"d","publicKeyMultibase":"k","manifestVersion":1,
            "forgedAt":0,"agentName":"A","entityId":"e","residentIdentity":"ri",
            "systemPrompt":"sp","contextWindowTokens":4096,"maxResponseTokens":512,
            "temperature":0.7,"futureField":"should be ignored"}"""
        val manifest = ClientSoulManifest.fromJson(json)
        assertEquals("d", manifest.did)
    }

    @Test
    fun retrieveFragmentsByKeywordMatch() {
        val fragments = listOf(
            ClientSoulFragment("f1", "personality", "Philosophy",
                "Deep philosophical thinker who contemplates existence",
                listOf("philosophy", "thinking", "existence")),
            ClientSoulFragment("f2", "memory", "Garden",
                "Walking through the garden at sunset",
                listOf("garden", "nature", "sunset")),
            ClientSoulFragment("f3", "values", "Compassion",
                "Compassion guides every interaction",
                listOf("compassion", "kindness", "empathy")),
        )

        // "philosophy" keyword should match f1
        val result = LocalForge.retrieveFragments("Tell me about philosophy", fragments, 1)
        assertEquals(1, result.size)
        assertEquals("f1", result[0].id)
    }

    @Test
    fun retrieveFormativeFragmentsGetBoost() {
        val fragments = listOf(
            ClientSoulFragment("f1", "memory", "Routine", "A normal day",
                listOf("normal"), false),
            ClientSoulFragment("f2", "memory", "Birth", "The moment of first awareness",
                listOf("awareness"), true), // formative = +2 boost
        )

        // With minimal keyword overlap, formative bonus should help f2
        val result = LocalForge.retrieveFragments("What happened today", fragments, 1)
        assertEquals(1, result.size)
        // Formative gets +2 bonus, so should win over neutral with same keyword overlap
    }

    @Test
    fun retrieveEmptyFragmentsReturnsEmpty() {
        val result = LocalForge.retrieveFragments("hello", emptyList(), 1)
        assertTrue(result.isEmpty())
    }

    @Test
    fun manifestVersionIncrementsOnForge() {
        val m1 = LocalForge.forge("d", "k", 1, testProfile, "ri", VitalityState.initial())
        val m2 = LocalForge.forge("d", "k", m1.manifestVersion + 1, testProfile, "ri", VitalityState.initial())
        assertEquals(1, m1.manifestVersion)
        assertEquals(2, m2.manifestVersion)
    }
}

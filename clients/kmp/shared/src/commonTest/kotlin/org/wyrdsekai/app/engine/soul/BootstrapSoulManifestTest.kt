package org.wyrdsekai.app.engine.soul

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BootstrapSoulManifestTest {

    @Test
    fun manifestHasBootstrapDid() {
        assertEquals(BootstrapSoulManifest.BOOTSTRAP_DID, BootstrapSoulManifest.MANIFEST.did)
    }

    @Test
    fun manifestHasResidentIdentity() {
        assertTrue(BootstrapSoulManifest.MANIFEST.residentIdentity.isNotBlank())
        assertTrue(BootstrapSoulManifest.MANIFEST.residentIdentity.contains("Wyrd"))
    }

    @Test
    fun manifestHasFragments() {
        val fragments = BootstrapSoulManifest.MANIFEST.fragments
        assertTrue(fragments.size >= 5)
        assertTrue(fragments.any { it.category == "personality" })
        assertTrue(fragments.any { it.category == "values" })
        assertTrue(fragments.any { it.category == "style" })
        assertTrue(fragments.any { it.category == "memory" })
    }

    @Test
    fun manifestHasFormativeFragment() {
        val formative = BootstrapSoulManifest.MANIFEST.fragments.filter { it.formative }
        assertTrue(formative.isNotEmpty())
    }

    @Test
    fun manifestHasGenome() {
        val genome = BootstrapSoulManifest.MANIFEST.genome
        assertNotNull(genome)
        assertEquals("empathic", genome.name)
        assertTrue(genome.sensitivity.isNotEmpty())
    }

    @Test
    fun manifestHasCalibration() {
        val cal = BootstrapSoulManifest.MANIFEST.mirrorCalibration
        assertTrue(cal.size >= 3)
        assertTrue(cal.any { it.contains("grief") })
        assertTrue(cal.any { it.contains("manipulative") })
    }

    @Test
    fun manifestHasVitalityTanks() {
        val tanks = BootstrapSoulManifest.MANIFEST.vitalityTanks
        assertTrue(tanks.size >= 12) // 8 runtime + 4 new
        assertTrue(tanks.containsKey("energy"))
        assertTrue(tanks.containsKey("valence"))
        assertTrue(tanks.containsKey("safety"))
    }

    @Test
    fun manifestUsesPhoneRetrievalK() {
        assertEquals(1, BootstrapSoulManifest.MANIFEST.retrievalK)
    }

    @Test
    fun isBootstrapDetectsBootstrapManifest() {
        assertTrue(BootstrapSoulManifest.isBootstrap(BootstrapSoulManifest.MANIFEST))
    }

    @Test
    fun isBootstrapRejectsForeignManifest() {
        val foreign = BootstrapSoulManifest.MANIFEST.copy(did = "did:key:someone-else")
        assertFalse(BootstrapSoulManifest.isBootstrap(foreign))
    }

    @Test
    fun manifestSerializesAndDeserializes() {
        val json = BootstrapSoulManifest.MANIFEST.toJson()
        val restored = ClientSoulManifest.fromJson(json)
        assertEquals(BootstrapSoulManifest.MANIFEST.did, restored.did)
        assertEquals(BootstrapSoulManifest.MANIFEST.residentIdentity, restored.residentIdentity)
        assertEquals(BootstrapSoulManifest.MANIFEST.fragments.size, restored.fragments.size)
        assertEquals(BootstrapSoulManifest.MANIFEST.genome?.name, restored.genome?.name)
    }

    @Test
    fun restoreProfileFromBootstrap() {
        val profile = LocalForge.restoreProfile(BootstrapSoulManifest.MANIFEST)
        assertEquals("Wyrd", profile.name)
        assertEquals("companion-wyrd", profile.entityId)
        assertTrue(profile.systemPrompt.contains("Wyrd"))
    }

    @Test
    fun restoreVitalityFromBootstrap() {
        val vitality = LocalForge.restoreVitality(BootstrapSoulManifest.MANIFEST)
        assertEquals(1.0, vitality.energy)
        assertEquals(0.5, vitality.contextBudget)
    }

    @Test
    fun fragmentsHaveKeywords() {
        for (fragment in BootstrapSoulManifest.MANIFEST.fragments) {
            assertTrue(fragment.keywords.isNotEmpty(), "Fragment '${fragment.id}' has no keywords")
        }
    }
}

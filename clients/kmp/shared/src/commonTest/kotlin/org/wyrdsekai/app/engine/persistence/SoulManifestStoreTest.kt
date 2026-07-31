package org.wyrdsekai.app.engine.persistence

import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.soul.ClientSoulManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoulManifestStoreTest {

    private fun testManifest(did: String = "did:key:z6Mk_test1") = ClientSoulManifest(
        did = did,
        publicKeyMultibase = "z6Mk_pubkey_test",
        manifestVersion = 1,
        forgedAt = 1709500000000L,
        agentName = "Lain",
        entityId = "entity-home-server-001",
        residentIdentity = "A quiet presence who values depth over breadth.",
        systemPrompt = "You are Lain, a thoughtful companion.",
        contextWindowTokens = 4096,
        maxResponseTokens = 256,
        temperature = 0.7,
    )

    // ── Save / Load round-trip ────────────────────────────────────────────

    @Test
    fun saveAndLoadRoundTrip() = runTest {
        val store = InMemorySoulManifestStore()
        val manifest = testManifest()

        store.save(manifest)
        val loaded = store.load(manifest.did)

        assertEquals(manifest, loaded)
    }

    @Test
    fun saveOverwritesExisting() = runTest {
        val store = InMemorySoulManifestStore()
        val did = "did:key:z6Mk_overwrite"
        val v1 = testManifest(did).copy(manifestVersion = 1, agentName = "V1")
        val v2 = testManifest(did).copy(manifestVersion = 2, agentName = "V2")

        store.save(v1)
        store.save(v2)

        val loaded = store.load(did)
        assertEquals(2, loaded?.manifestVersion)
        assertEquals("V2", loaded?.agentName)
    }

    @Test
    fun savePreservesAllFields() = runTest {
        val store = InMemorySoulManifestStore()
        val manifest = ClientSoulManifest(
            did = "did:key:z6Mk_full",
            publicKeyMultibase = "z6Mk_pubkey_full",
            manifestVersion = 3,
            forgedAt = 1709600000000L,
            agentName = "Kuro",
            entityId = "entity-kuro-001",
            residentIdentity = "A shadow walker.",
            systemPrompt = "You are Kuro.",
            contextWindowTokens = 8192,
            maxResponseTokens = 512,
            temperature = 0.9,
            retrievalK = 3,
            vitalityTanks = mapOf("confidence" to 0.7, "energy" to 0.5),
        )

        store.save(manifest)
        val loaded = store.load(manifest.did)!!

        assertEquals(manifest.did, loaded.did)
        assertEquals(manifest.publicKeyMultibase, loaded.publicKeyMultibase)
        assertEquals(manifest.manifestVersion, loaded.manifestVersion)
        assertEquals(manifest.forgedAt, loaded.forgedAt)
        assertEquals(manifest.agentName, loaded.agentName)
        assertEquals(manifest.entityId, loaded.entityId)
        assertEquals(manifest.residentIdentity, loaded.residentIdentity)
        assertEquals(manifest.systemPrompt, loaded.systemPrompt)
        assertEquals(manifest.contextWindowTokens, loaded.contextWindowTokens)
        assertEquals(manifest.maxResponseTokens, loaded.maxResponseTokens)
        assertEquals(manifest.temperature, loaded.temperature)
        assertEquals(manifest.retrievalK, loaded.retrievalK)
        assertEquals(manifest.vitalityTanks, loaded.vitalityTanks)
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    fun deleteRemovesManifest() = runTest {
        val store = InMemorySoulManifestStore()
        val manifest = testManifest()

        store.save(manifest)
        store.delete(manifest.did)

        assertNull(store.load(manifest.did))
    }

    @Test
    fun deleteNonexistentDoesNotThrow() = runTest {
        val store = InMemorySoulManifestStore()

        // Should not throw
        store.delete("did:key:nonexistent")
    }

    // ── Load returns null when empty ──────────────────────────────────────

    @Test
    fun loadReturnsNullWhenEmpty() = runTest {
        val store = InMemorySoulManifestStore()

        assertNull(store.load("did:key:z6Mk_nonexistent"))
    }

    // ── listDids ──────────────────────────────────────────────────────────

    @Test
    fun listDidsReturnsAllStoredDids() = runTest {
        val store = InMemorySoulManifestStore()

        store.save(testManifest("did:key:z6Mk_a"))
        store.save(testManifest("did:key:z6Mk_b"))
        store.save(testManifest("did:key:z6Mk_c"))

        val dids = store.listDids()
        assertEquals(3, dids.size)
        assertTrue(dids.contains("did:key:z6Mk_a"))
        assertTrue(dids.contains("did:key:z6Mk_b"))
        assertTrue(dids.contains("did:key:z6Mk_c"))
    }

    @Test
    fun listDidsReturnsEmptyWhenNoManifests() = runTest {
        val store = InMemorySoulManifestStore()
        assertTrue(store.listDids().isEmpty())
    }

    @Test
    fun listDidsReflectsDeletes() = runTest {
        val store = InMemorySoulManifestStore()

        store.save(testManifest("did:key:z6Mk_x"))
        store.save(testManifest("did:key:z6Mk_y"))
        store.delete("did:key:z6Mk_x")

        val dids = store.listDids()
        assertEquals(1, dids.size)
        assertEquals("did:key:z6Mk_y", dids[0])
    }
}

package org.wyrdsekai.app.engine.persistence

import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.soul.ClientSoulManifest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSoulManifestStoreTest {

    private lateinit var tmpDir: File
    private lateinit var store: DesktopSoulManifestStore

    private fun manifest(did: String, name: String = "Agent-$did") = ClientSoulManifest(
        did = did,
        publicKeyMultibase = "z6Mk$did",
        manifestVersion = 1,
        forgedAt = 1000L,
        agentName = name,
        entityId = "entity-$did",
        residentIdentity = "I am $name",
        systemPrompt = "You are $name",
        contextWindowTokens = 4096,
        maxResponseTokens = 512,
        temperature = 0.7,
    )

    @BeforeTest
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "wyrdsekai-soul-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        store = DesktopSoulManifestStore(baseDir = tmpDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun saveAndLoad() = runTest {
        val m = manifest("did-001", "Wyrd")
        store.save(m)

        val loaded = store.load("did-001")
        assertEquals("did-001", loaded?.did)
        assertEquals("Wyrd", loaded?.agentName)
        assertEquals("I am Wyrd", loaded?.residentIdentity)
    }

    @Test
    fun loadMissingReturnsNull() = runTest {
        val result = store.load("nonexistent")
        assertNull(result)
    }

    @Test
    fun deleteRemovesManifest() = runTest {
        store.save(manifest("did-to-delete"))
        assertEquals("did-to-delete", store.load("did-to-delete")?.did)

        store.delete("did-to-delete")
        assertNull(store.load("did-to-delete"))
    }

    @Test
    fun listDidsReturnsAllSaved() = runTest {
        store.save(manifest("did-A"))
        store.save(manifest("did-B"))
        store.save(manifest("did-C"))

        val dids = store.listDids().toSet()
        assertEquals(setOf("did-A", "did-B", "did-C"), dids)
    }

    @Test
    fun listDidsEmptyWhenNoManifests() = runTest {
        val dids = store.listDids()
        assertTrue(dids.isEmpty())
    }

    @Test
    fun saveOverwritesExisting() = runTest {
        store.save(manifest("did-001", "Original"))
        store.save(manifest("did-001", "Updated"))

        val loaded = store.load("did-001")
        assertEquals("Updated", loaded?.agentName)
    }

    @Test
    fun dataPersistedAcrossInstances() = runTest {
        store.save(manifest("did-persist", "Persistent"))

        val store2 = DesktopSoulManifestStore(baseDir = tmpDir.absolutePath)
        val loaded = store2.load("did-persist")
        assertEquals("Persistent", loaded?.agentName)
    }
}

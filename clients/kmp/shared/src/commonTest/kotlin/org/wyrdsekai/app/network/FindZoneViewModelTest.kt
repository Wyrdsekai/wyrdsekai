package org.wyrdsekai.app.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** FindZoneViewModel tests (/P5 — KMP parity). */
class FindZoneViewModelTest {

    private val sampleZone = DiscoveredZone(
        zoneLabel = "commons",
        displayName = "The Commons",
        tagline = "a shared hearth",
        tags = listOf("public", "social"),
    )

    @Test fun search_success_populatesResults() = runTest {
        val vm = FindZoneViewModel(
            discover = { _, _ -> DiscoverResult(zones = listOf(sampleZone)) },
            requestAccessFn = { _, _ -> true },
        )
        vm.search("commons")
        assertEquals(1, vm.results.size)
        assertEquals("commons", vm.results[0].zoneLabel)
        assertTrue(vm.searched)
        assertFalse(vm.busy)
        assertNull(vm.error)
    }

    @Test fun search_transportFailure_surfacesError() = runTest {
        val vm = FindZoneViewModel(
            discover = { _, _ -> DiscoverResult(zones = emptyList(), error = "directory search failed") },
            requestAccessFn = { _, _ -> true },
        )
        vm.search("anything")
        assertTrue(vm.results.isEmpty())
        assertEquals("directory search failed", vm.error)
        assertTrue(vm.searched)
    }

    @Test fun requestAccess_success_marksSent() = runTest {
        var sawTarget: String? = null
        var sawName: String? = null
        val vm = FindZoneViewModel(
            discover = { _, _ -> DiscoverResult(zones = listOf(sampleZone)) },
            requestAccessFn = { target, name -> sawTarget = target; sawName = name; true },
            requesterName = { "ada" },
        )
        vm.requestAccess(sampleZone)
        assertEquals("commons", sawTarget)
        assertEquals("ada", sawName)
        assertEquals("sent", vm.knockState["commons"])
        assertNull(vm.error)
    }

    @Test fun requestAccess_failure_clearsStateAndShowsError() = runTest {
        val vm = FindZoneViewModel(
            discover = { _, _ -> DiscoverResult(zones = listOf(sampleZone)) },
            requestAccessFn = { _, _ -> false },
        )
        vm.requestAccess(sampleZone)
        assertNull(vm.knockState["commons"])
        assertTrue(vm.error != null)
    }
}

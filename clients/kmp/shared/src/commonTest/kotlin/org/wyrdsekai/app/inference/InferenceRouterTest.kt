package org.wyrdsekai.app.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InferenceRouterTest {

    // ── Fakes ────────────────────────────────────────────────────────────

    /**
     * Fake local inference provider for testing.
     * State starts as "stopped" and can be set to "running" to simulate model load.
     */
    private class FakeLocalProvider(
        initialState: String = "stopped",
        private val response: ChatResponse = ChatResponse("local response", 10, 5),
        private val shouldThrow: Boolean = false,
    ) : LocalInferenceProvider {
        private val _state = MutableStateFlow(initialState)
        override val state: StateFlow<String> = _state

        var callCount = 0
            private set

        fun setState(newState: String) { _state.value = newState }

        override suspend fun completeLocal(
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            callCount++
            if (shouldThrow) throw RuntimeException("Local inference failed")
            return response
        }
    }

    /**
     * Fake inference client that returns a canned response or throws.
     * Tracks which URL was called for assertions.
     */
    private class FakeInferenceClient(
        private val response: ChatResponse = ChatResponse("remote response", 20, 10),
        private val shouldThrow: Boolean = false,
    ) : InferenceClient() {
        var callCount = 0
            private set
        var lastBaseUrl: String? = null
            private set

        override suspend fun complete(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            callCount++
            lastBaseUrl = baseUrl
            if (shouldThrow) throw RuntimeException("Remote inference failed")
            return response
        }
    }

    private val testMessages = listOf(ChatMessage("user", "Hello"))

    // ── Backend detection ────────────────────────────────────────────────

    @Test
    fun activeBackendIsNoneWhenNothingAvailable() {
        val local = FakeLocalProvider("stopped")
        val router = InferenceRouter(local)
        assertEquals("none", router.getActiveBackend())
    }

    @Test
    fun activeBackendIsLocalWhenRunning() {
        val local = FakeLocalProvider("running")
        val router = InferenceRouter(local)
        assertEquals("local", router.getActiveBackend())
    }

    @Test
    fun activeBackendIsRemoteWhenLocalUnavailable() {
        val local = FakeLocalProvider("stopped")
        val remote = FakeInferenceClient()
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://remote:8080")
        assertEquals("remote", router.getActiveBackend())
    }

    @Test
    fun activeBackendPrefersLocalOverRemote() {
        val local = FakeLocalProvider("running")
        val remote = FakeInferenceClient()
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://remote:8080")
        assertEquals("local", router.getActiveBackend())
    }

    @Test
    fun canInferRemotelyRequiresBothClientAndUrl() {
        val local = FakeLocalProvider("stopped")

        // Client only, no URL
        val router1 = InferenceRouter(local, remoteClient = FakeInferenceClient(), remoteBaseUrl = null)
        assertEquals(false, router1.canInferRemotely())

        // URL only, no client
        val router2 = InferenceRouter(local, remoteClient = null, remoteBaseUrl = "http://test")
        assertEquals(false, router2.canInferRemotely())

        // Both present
        val router3 = InferenceRouter(local, remoteClient = FakeInferenceClient(), remoteBaseUrl = "http://test")
        assertEquals(true, router3.canInferRemotely())

        // Blank URL
        val router4 = InferenceRouter(local, remoteClient = FakeInferenceClient(), remoteBaseUrl = "")
        assertEquals(false, router4.canInferRemotely())
    }

    // ── Completion routing ──────────────────────────────────────────────

    @Test
    fun completeUsesLocalWhenAvailable() = runTest {
        val local = FakeLocalProvider("running", response = ChatResponse("local!", 5, 3))
        val router = InferenceRouter(local)

        val result = router.complete(testMessages)

        assertEquals("local!", result.content)
        assertEquals(1, local.callCount)
    }

    @Test
    fun completeUsesRemoteWhenLocalUnavailable() = runTest {
        val local = FakeLocalProvider("stopped")
        val remote = FakeInferenceClient(response = ChatResponse("remote!", 15, 8))
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://remote:8080")

        val result = router.complete(testMessages)

        assertEquals("remote!", result.content)
        assertEquals(0, local.callCount)
        assertEquals(1, remote.callCount)
        assertEquals("http://remote:8080", remote.lastBaseUrl)
    }

    @Test
    fun completeThrowsWhenNothingAvailable() = runTest {
        val local = FakeLocalProvider("stopped")
        val router = InferenceRouter(local)

        val exception = assertFailsWith<IllegalStateException> {
            router.complete(testMessages)
        }
        assertTrue(exception.message!!.contains("No inference backend available"))
    }

    // ── Fallback chain ──────────────────────────────────────────────────

    @Test
    fun fallsBackToRemoteWhenLocalFails() = runTest {
        val local = FakeLocalProvider("running", shouldThrow = true)
        val remote = FakeInferenceClient(response = ChatResponse("fallback!", 20, 10))
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://fallback")

        val result = router.complete(testMessages)

        assertEquals("fallback!", result.content)
        assertEquals(1, local.callCount)
        assertEquals(1, remote.callCount)
    }

    @Test
    fun localFailureWithoutRemoteRethrows() = runTest {
        val local = FakeLocalProvider("running", shouldThrow = true)
        val router = InferenceRouter(local)

        assertFailsWith<RuntimeException> {
            router.complete(testMessages)
        }
    }

    @Test
    fun preferRemoteTriesRemoteFirst() = runTest {
        val local = FakeLocalProvider("running", response = ChatResponse("local", 5, 3))
        val remote = FakeInferenceClient(response = ChatResponse("remote-preferred", 20, 10))
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://remote")

        val result = router.complete(testMessages, preferRemote = true)

        assertEquals("remote-preferred", result.content)
        assertEquals(0, local.callCount, "Local should not be called when preferRemote and remote succeeds")
        assertEquals(1, remote.callCount)
    }

    @Test
    fun preferRemoteFallsBackToLocalOnRemoteFailure() = runTest {
        val local = FakeLocalProvider("running", response = ChatResponse("local-fallback", 5, 3))
        val remote = FakeInferenceClient(shouldThrow = true)
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://remote")

        val result = router.complete(testMessages, preferRemote = true)

        assertEquals("local-fallback", result.content)
        assertEquals(1, remote.callCount, "Remote should be tried first")
        assertEquals(1, local.callCount, "Local should be used as fallback")
    }

    @Test
    fun preferRemoteWithNoLocalThrowsOnRemoteFailure() = runTest {
        val local = FakeLocalProvider("stopped")
        val remote = FakeInferenceClient(shouldThrow = true)
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://remote")

        assertFailsWith<RuntimeException> {
            router.complete(testMessages, preferRemote = true)
        }
    }

    // ── Remote-only mode ────────────────────────────────────────────────

    @Test
    fun remoteOnlyMode() = runTest {
        val local = FakeLocalProvider("stopped")
        val remote = FakeInferenceClient(response = ChatResponse("cloud-only", 30, 15))
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://cloud:11434")

        val result = router.complete(testMessages)

        assertEquals("cloud-only", result.content)
        assertEquals("http://cloud:11434", remote.lastBaseUrl)
    }

    // ── Dynamic backend switching ────────────────────────────────────────

    @Test
    fun backendSwitchesWhenLocalBecomesAvailable() = runTest {
        val local = FakeLocalProvider("stopped", response = ChatResponse("now-local", 5, 3))
        val remote = FakeInferenceClient(response = ChatResponse("was-remote", 20, 10))
        val router = InferenceRouter(local, remoteClient = remote, remoteBaseUrl = "http://remote")

        // First call: local unavailable, uses remote
        val result1 = router.complete(testMessages)
        assertEquals("was-remote", result1.content)
        assertEquals("remote", router.getActiveBackend())

        // Model loads
        local.setState("running")

        // Second call: local now available, uses local
        val result2 = router.complete(testMessages)
        assertEquals("now-local", result2.content)
        assertEquals("local", router.getActiveBackend())
    }

    // ── Per-role routing (e) ─────────────────────
    //
    // The canonical case table is clients/parity/parity.json → inferenceRouting,
    // which the RN twin reads directly. commonTest has no filesystem, so the
    // cases are mirrored here and MUST be kept in step — same obligation as
    // TemperamentSeedTest's fixture. If the table changes, change both.
    //
    // KMP has two backends (local, remote) where RN has three (local, remote,
    // server). The `server`-tier cases are therefore RN-only; the semantics
    // under test are identical: VOICE prefers the device, DRIVE borrows first,
    // and DRIVE falls back to the device when there is nothing to borrow from.

    @Test
    fun voicePrefersTheDevice() = runTest {
        val router = InferenceRouter(
            FakeLocalProvider(initialState = "running"),
            FakeInferenceClient(), "http://remote.invalid",
        )
        val r = router.complete(listOf(ChatMessage("user", "hi")), role = ModelRole.VOICE)
        assertEquals("local response", r.content)
    }

    @Test
    fun voiceFallsThroughWhenNoModelIsLoaded() = runTest {
        val router = InferenceRouter(
            FakeLocalProvider(initialState = "stopped"),
            FakeInferenceClient(), "http://remote.invalid",
        )
        val r = router.complete(listOf(ChatMessage("user", "hi")), role = ModelRole.VOICE)
        assertEquals("remote response", r.content)
    }

    @Test
    fun driveBorrowsFirst() = runTest {
        val router = InferenceRouter(
            FakeLocalProvider(initialState = "running"),
            FakeInferenceClient(), "http://remote.invalid",
        )
        val r = router.complete(listOf(ChatMessage("user", "hi")), role = ModelRole.DRIVE)
        assertEquals("remote response", r.content)
    }

    /** The whole "truly standalone attempts drive" rule, with no mode test. */
    @Test
    fun trulyStandaloneDriveAttemptsTheDevice() = runTest {
        val router = InferenceRouter(FakeLocalProvider(initialState = "running"))
        val r = router.complete(listOf(ChatMessage("user", "hi")), role = ModelRole.DRIVE)
        assertEquals("local response", r.content)
    }

    @Test
    fun nothingConfiguredIsAnErrorNotASilentNoOp() = runTest {
        val router = InferenceRouter(FakeLocalProvider(initialState = "stopped"))
        assertFailsWith<IllegalStateException> {
            router.complete(listOf(ChatMessage("user", "hi")), role = ModelRole.DRIVE)
        }
    }

    /** preferRemote predates roles; true must stay equivalent to DRIVE. */
    @Test
    fun preferRemoteRemainsEquivalentToDrive() = runTest {
        val router = InferenceRouter(
            FakeLocalProvider(initialState = "running"),
            FakeInferenceClient(), "http://remote.invalid",
        )
        val viaFlag = router.complete(listOf(ChatMessage("user", "hi")), preferRemote = true)
        val viaRole = router.complete(listOf(ChatMessage("user", "hi")), role = ModelRole.DRIVE)
        assertEquals(viaFlag.content, viaRole.content)
    }
}

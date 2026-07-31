package org.wyrdsekai.app.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ServersViewModel tests (/P5 — KMP parity). */
class ServersViewModelTest {

    private fun bankWith(zoneId: String, username: String = "operator"): ZoneBank {
        val b = ZoneBank()
        b.addOrUpdateZone(ZoneBankEntry(zoneId, zoneId, emptyList(), username, addedAt = 1))
        return b
    }

    @Test fun attempt_success_setsConnected_clearsPrompt() = runTest {
        val vm = ServersViewModel(bankWith("home-server")) { _, _ ->
            OpenOutcome.Connected("home-server", "wss://relay-node:4443")
        }
        vm.attempt("home-server", "pw")
        assertEquals("home-server", vm.connectedZone)
        assertNull(vm.promptZone)
        assertNull(vm.busyZone)
        assertTrue(vm.errorByZone.isEmpty())
    }

    @Test fun attempt_needsPassword_opensPrompt() = runTest {
        val vm = ServersViewModel(bankWith("home-server")) { _, _ -> OpenOutcome.NeedsPassword }
        vm.attempt("home-server")
        assertEquals("home-server", vm.promptZone)
        assertNull(vm.connectedZone)
    }

    @Test fun attempt_authRejected_repromptsAndShowsError() = runTest {
        val vm = ServersViewModel(bankWith("home-server")) { _, _ -> OpenOutcome.AuthRejected("bad password") }
        vm.attempt("home-server", "wrong")
        assertEquals("home-server", vm.promptZone)
        assertEquals("bad password", vm.errorByZone["home-server"])
        assertNull(vm.connectedZone)
    }

    @Test fun attempt_unreachable_showsErrorNoPrompt() = runTest {
        val vm = ServersViewModel(bankWith("home-server")) { _, _ -> OpenOutcome.Unreachable("no relay") }
        vm.attempt("home-server")
        assertEquals("no relay", vm.errorByZone["home-server"])
        assertNull(vm.promptZone)
    }

    @Test fun submitPrompt_capturesUsernameWhenMissing_thenConnects() = runTest {
        val bank = bankWith("home-server", username = "")
        var sawPassword: String? = null
        val vm = ServersViewModel(bank) { _, pw ->
            sawPassword = pw
            OpenOutcome.Connected("home-server", "wss://relay-node:4443")
        }
        vm.submitPrompt("home-server", username = "ada", password = "pw")
        assertEquals("ada", bank.getZone("home-server")!!.username)
        assertEquals("pw", sawPassword)
        assertEquals("home-server", vm.connectedZone)
    }
}

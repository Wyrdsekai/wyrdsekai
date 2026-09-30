package org.wyrdsekai.app.network

import kotlin.test.assertFalse
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A host pinned from an invite is checked against that pin only, with full
 * path validation: the chain must be signed up to the pinned household CA.
 * A man in the middle who sends his own leaf next to the real (public) CA
 * certificate is refused, and so is a chain the system would trust.
 * (Audit W6 items 5 and 18; D6, .)
 */
class HouseholdTrustManagerPinTest {

    private fun cert(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    private val ca1 = cert(CA1)
    private val leaf1 = cert(LEAF1)
    private val ca2 = cert(CA2)
    private val leaf2 = cert(LEAF2)

    private val tm = HouseholdTrustManager(
        HouseholdTrustManager.resolveSystemTrustManager(),
        pins = { host, _ -> if (host == "home.test" || host == "192.0.2.1") ca1 else null },
    )

    @Test
    fun theHomesOwnChainValidatesAgainstItsPin() {
        tm.verify(arrayOf(leaf1, ca1), "ECDHE_ECDSA", "home.test", 7443)
        tm.verify(arrayOf(leaf1), "ECDHE_ECDSA", "192.0.2.1", 7443)
    }

    @Test
    fun aStrangersLeafNextToTheRealCaIsRefused() {
        assertFailsWith<PinMismatchException> { tm.verify(arrayOf(leaf2, ca1), "ECDHE_ECDSA", "home.test", 7443) }
    }

    @Test
    fun anotherHouseholdsChainIsRefused() {
        assertFailsWith<PinMismatchException> { tm.verify(arrayOf(leaf2, ca2), "ECDHE_ECDSA", "home.test", 7443) }
    }

    @Test
    fun aPinMismatchTellsThePersonToPairAgain() {
        SecurityNotices.clear()
        runCatching { tm.verify(arrayOf(leaf2, ca2), "ECDHE_ECDSA", "home.test", 7443) }
        assertIs<SecurityNotice.PinMismatch>(SecurityNotices.events.replayCache.single())
    }

    @Test
    fun anUnpinnedHostGetsSystemTrustOnly() {
        val e = assertFailsWith<CertificateException> { tm.verify(arrayOf(leaf1, ca1), "ECDHE_ECDSA", "elsewhere.test", 7443) }
        assertFalse(e is PinMismatchException)
    }

    /**
     * A relay and a home on one machine (the 2026-09-28 rehearsal: relay :24443,
     * home :27443 and its bus :27223 on one address). Pins are kept per host AND
     * port, so pinning the home never replaces the relay's CA.
     */
    @Test
    fun aRelayAndAHomeOnOneHostKeepTheirOwnPins() {
        val host = "192.0.2.7"
        HouseholdTrustStore.put(null, host, 24443, ca2)   // the relay's CA
        HouseholdTrustStore.put(null, host, 27443, ca1)   // the home's CA, HTTPS
        HouseholdTrustStore.put(null, host, 27223, ca1)   // the home's CA, bus
        try {
            val store = HouseholdTrustManager(HouseholdTrustManager.resolveSystemTrustManager())
            store.verify(arrayOf(leaf2, ca2), "ECDHE_ECDSA", host, 24443)
            store.verify(arrayOf(leaf1, ca1), "ECDHE_ECDSA", host, 27443)
            store.verify(arrayOf(leaf1, ca1), "ECDHE_ECDSA", host, 27223)
            assertFailsWith<PinMismatchException> { store.verify(arrayOf(leaf1, ca1), "ECDHE_ECDSA", host, 24443) }
            assertFailsWith<PinMismatchException> { store.verify(arrayOf(leaf2, ca2), "ECDHE_ECDSA", host, 27443) }
            // A port of that host with no pin of its own gets system trust only.
            assertFailsWith<CertificateException> { store.verify(arrayOf(leaf1, ca1), "ECDHE_ECDSA", host, 9999) }
            assertEquals(ca2, HouseholdTrustStore.getExact(host, 24443))
            assertEquals(null, HouseholdTrustStore.getExact(host, 9999))
        } finally {
            HouseholdTrustStore.remove(null, host, 24443)
            HouseholdTrustStore.remove(null, host, 27443)
            HouseholdTrustStore.remove(null, host, 27223)
        }
    }

    private companion object {
        const val CA1 = """
-----BEGIN CERTIFICATE-----
MIIBozCCAUmgAwIBAgIUZEg/NEeB1/c60QcINWl60o16xVUwCgYIKoZIzj0EAwIw
HjEcMBoGA1UEAwwTVGVzdCBIb3VzZWhvbGQgQ0EgMTAgFw0yNjA5MjgyMDE3MjFa
GA8yMTI2MDkwNDIwMTcyMVowHjEcMBoGA1UEAwwTVGVzdCBIb3VzZWhvbGQgQ0Eg
MTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABGLiMStP3peMqeSJZ86GDfr9J8ZF
8dn6peiSs2nx7yPGjWPAqOXTyCt5FAUb4HEim0YlLjkaVNOz/YrbulopeTSjYzBh
MB0GA1UdDgQWBBRSTfGrPL1WUqVFxArwXux4NWYV3DAfBgNVHSMEGDAWgBRSTfGr
PL1WUqVFxArwXux4NWYV3DAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIB
BjAKBggqhkjOPQQDAgNIADBFAiEAjdBouw44JyuMGdJOtaqnuaE8acGcUJH1H8FH
1U89QyoCIAQEbHQKu/WumlEzJtoJ2fEaZGtbJYFZQkA+IoTGcBaQ
-----END CERTIFICATE-----
"""
        const val LEAF1 = """
-----BEGIN CERTIFICATE-----
MIIBxjCCAWygAwIBAgIUQAtmAdsqKZpyI73AYBQaiXmVzkswCgYIKoZIzj0EAwIw
HjEcMBoGA1UEAwwTVGVzdCBIb3VzZWhvbGQgQ0EgMTAgFw0yNjA5MjgyMDE3MjFa
GA8yMTI2MDkwNDIwMTcyMVowFDESMBAGA1UEAwwJaG9tZS50ZXN0MFkwEwYHKoZI
zj0CAQYIKoZIzj0DAQcDQgAE4NW3sCIZEqbbwPzIb/6pVpdeYkM7aYjjDFA7UdNo
EX+xC94Jn/450kNk646XCWU6WGsZHbEr12WMK/u0qnI456OBjzCBjDAJBgNVHRME
AjAAMA4GA1UdDwEB/wQEAwIHgDATBgNVHSUEDDAKBggrBgEFBQcDATAaBgNVHREE
EzARgglob21lLnRlc3SHBMAAAgEwHQYDVR0OBBYEFHx4x3G/WSLaAKqyaHXyZnqW
wmVfMB8GA1UdIwQYMBaAFFJN8as8vVZSpUXECvBe7Hg1ZhXcMAoGCCqGSM49BAMC
A0gAMEUCIExMyPSGKwjbM4t89vlCpeWqnCL7wH4zqB0HWHKN4DiNAiEAvIACM8AV
TBEUvGwEOaPAHsTUEU8U4l91kh5e/zynhCk=
-----END CERTIFICATE-----
"""
        const val CA2 = """
-----BEGIN CERTIFICATE-----
MIIBozCCAUmgAwIBAgIUYtoUtoawWTceM/DSlLYD9Dz6y9EwCgYIKoZIzj0EAwIw
HjEcMBoGA1UEAwwTVGVzdCBIb3VzZWhvbGQgQ0EgMjAgFw0yNjA5MjgyMDE3MjFa
GA8yMTI2MDkwNDIwMTcyMVowHjEcMBoGA1UEAwwTVGVzdCBIb3VzZWhvbGQgQ0Eg
MjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABOWlUS87EpC5oFkOrwM2j2LAoJWq
+65HpBwvRv2oHqqYRdOmLgMKUTWvfCJTqK3/2k4YtQmr/H+otCwYKqM4+UGjYzBh
MB0GA1UdDgQWBBTOerPqTlrcv5Cxr9yfM4e3Lbm+JDAfBgNVHSMEGDAWgBTOerPq
Tlrcv5Cxr9yfM4e3Lbm+JDAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIB
BjAKBggqhkjOPQQDAgNIADBFAiEAqDU+O0zriqtQULrxifQ90wAE2WzA8fPgxdcT
Q4JbidYCIDDh9fu+TdFrsJGSX9egn/6jE9hy0hRdjFri0tlkmx6U
-----END CERTIFICATE-----
"""
        const val LEAF2 = """
-----BEGIN CERTIFICATE-----
MIIBxjCCAWygAwIBAgIUKTWVJuJR2akdC79mFl8dYW1OmRkwCgYIKoZIzj0EAwIw
HjEcMBoGA1UEAwwTVGVzdCBIb3VzZWhvbGQgQ0EgMjAgFw0yNjA5MjgyMDE3MjFa
GA8yMTI2MDkwNDIwMTcyMVowFDESMBAGA1UEAwwJaG9tZS50ZXN0MFkwEwYHKoZI
zj0CAQYIKoZIzj0DAQcDQgAEL3ZZHeNLdh3bMtUj1vyVodxuWnKsgBD2S8HRHein
iip3dWpZpwpzY0DILXKEsPu+igoYskGW26u0mysaaRPeoqOBjzCBjDAJBgNVHRME
AjAAMA4GA1UdDwEB/wQEAwIHgDATBgNVHSUEDDAKBggrBgEFBQcDATAaBgNVHREE
EzARgglob21lLnRlc3SHBMAAAgEwHQYDVR0OBBYEFAkTuziBOHW01pg+lGQ2rR0p
5sk+MB8GA1UdIwQYMBaAFM56s+pOWty/kLGv3J8zh7ctub4kMAoGCCqGSM49BAMC
A0gAMEUCIHplRt7FiwDm+vMpB5yu9r7yoNlCsr/GzzKrIlzK222SAiEAm4/+f4hc
i7HERwdP/IA0Dsf1ogEZuGJoJRF6+eXvJbU=
-----END CERTIFICATE-----
"""
    }
}

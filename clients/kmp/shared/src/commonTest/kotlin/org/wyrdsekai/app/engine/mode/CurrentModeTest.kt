package org.wyrdsekai.app.engine.mode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mapping raw persisted values to mode inputs — the KMP half.
 *
 * The canonical table is clients/parity/parity.json -> modeInputs, which the RN
 * twin reads directly. commonTest has no filesystem, so the cases are mirrored
 * here and MUST be kept in step. These bodies were generated from that table;
 * if it changes, regenerate.
 */
class CurrentModeTest {

    /** mode 'local' runs a local node */
    @Test
    fun modeLocalRunsALocalNode() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = "local",
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(true, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** mode 'remote' does not */
    @Test
    fun modeRemoteDoesNot() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = "remote",
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** mode 'unset' does not */
    @Test
    fun modeUnsetDoesNot() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = "unset",
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** absent mode does not */
    @Test
    fun absentModeDoesNot() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** a zone id is a home zone */
    @Test
    fun aZoneIdIsAHomeZone() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = "testzone",
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(true, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** a relay url is a home zone */
    @Test
    fun aRelayUrlIsAHomeZone() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = "wss://relay.example.org:4443",
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(true, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** a household id is a home zone */
    @Test
    fun aHouseholdIdIsAHomeZone() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = null,
            householdId = "hh-1",
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(true, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** empty strings are NOT a home zone */
    @Test
    fun emptyStringsAreNotAHomeZone() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = "",
            relayUrl = "",
            householdId = "",
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** an api key without a provider is unusable */
    @Test
    fun anApiKeyWithoutAProviderIsUnusable() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = "sk-test",
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** a provider without a key is unusable */
    @Test
    fun aProviderWithoutAKeyIsUnusable() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = "anthropic",
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** key plus provider is a cloud key */
    @Test
    fun keyPlusProviderIsACloudKey() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = "sk-test",
            apiProvider = "anthropic",
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(true, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** backing defaults to home when absent */
    @Test
    fun backingDefaultsToHomeWhenAbsent() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** backing defaults to home when unrecognised */
    @Test
    fun backingDefaultsToHomeWhenUnrecognised() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = "nonsense",
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** backing is cloud only when chosen */
    @Test
    fun backingIsCloudOnlyWhenChosen() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = "cloud",
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.CLOUD, got.preferredBacking)
    }

    /** on-device model passes through */
    @Test
    fun onDeviceModelPassesThrough() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = null,
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = true,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(false, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(true, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** invite-paired phone: own node AND a zone */
    @Test
    fun invitePairedPhoneOwnNodeAndAZone() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = "local",
            zoneId = "testzone",
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = true,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(true, got.wantsOwnNode)
        assertEquals(true, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(true, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** the experimental opt-in is what makes a model viable */
    @Test
    fun theExperimentalOptInIsWhatMakesAModelViable() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = true,
            mode = "local",
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(true, got.onDeviceModelViable)
        assertEquals(true, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /** absent opt-in is off, never on */
    @Test
    fun absentOptInIsOffNeverOn() {
        val got = modeInputsFrom(
            onDeviceModelOptIn = false,
            mode = "local",
            zoneId = null,
            relayUrl = null,
            householdId = null,
            apiKey = null,
            apiProvider = null,
            preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(false, got.onDeviceModelViable)
        assertEquals(true, got.wantsOwnNode)
        assertEquals(false, got.hasHomeZone)
        assertEquals(false, got.hasCloudKey)
        assertEquals(false, got.hasOnDeviceModel)
        assertEquals(Backing.HOME, got.preferredBacking)
    }

    /**
     * Viability comes from the policy seam, not a raw read. The future edit —
     * measured throughput promoting a capable tablet on its own merits — lands
     * in this one function, and the mode tree never learns about it.
     */
    @Test
    fun viabilityIsDecidedByThePolicySeam() {
        assertEquals(false, onDeviceModelViable(optIn = false))
        assertEquals(true, onDeviceModelViable(optIn = true))
    }

    @Test
    fun availableBackingsOnlyOffersWhatCanAnswer() {
        fun inp(zone: String?, key: String?, prov: String?) = modeInputsFrom(
            mode = "local", zoneId = zone, relayUrl = null, householdId = null,
            apiKey = key, apiProvider = prov, preferredBacking = null,
            hasOnDeviceModel = false,
        )
        assertEquals(emptyList(), availableBackings(inp(null, null, null)))
        assertEquals(listOf(Backing.HOME), availableBackings(inp("z", null, null)))
        assertEquals(listOf(Backing.CLOUD), availableBackings(inp(null, "k", "openai")))
        assertEquals(
            listOf(Backing.HOME, Backing.CLOUD),
            availableBackings(inp("z", "k", "openai")),
        )
    }

    @Test
    fun everyModeHasItsOwnLabel() {
        val labels = PhoneMode.entries.map { modeLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
        assertTrue(modeLabel(null).contains("Setup"))
    }
}

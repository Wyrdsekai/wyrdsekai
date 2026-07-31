package org.wyrdsekai.app.engine.mode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mode selection — the KMP half of the shared contract.
 *
 * The canonical table is clients/parity/parity.json -> phoneMode, which the RN
 * twin reads directly. commonTest has no filesystem, so the cases are mirrored
 * here and MUST be kept in step. These bodies were generated from that table;
 * if it changes, regenerate.
 */
class PhoneModeTest {

    /** default: home zone becomes a terminal */
    @Test
    fun defaultHomeZoneBecomesATerminal() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = false,
            wantsOwnNode = true,
            hasHomeZone = true,
            hasCloudKey = false,
            hasOnDeviceModel = false,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.REMOTE_TERMINAL, d.mode)
    }

    /** default: home zone, terminal even if a model is downloaded */
    @Test
    fun defaultHomeZoneTerminalEvenIfAModelIsDownloaded() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = false,
            wantsOwnNode = true,
            hasHomeZone = true,
            hasCloudKey = false,
            hasOnDeviceModel = true,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.REMOTE_TERMINAL, d.mode)
    }

    /** default: home zone wins over a cloud key for a terminal user */
    @Test
    fun defaultHomeZoneWinsOverACloudKeyForATerminalUser() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = false,
            wantsOwnNode = false,
            hasHomeZone = true,
            hasCloudKey = true,
            hasOnDeviceModel = false,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.REMOTE_TERMINAL, d.mode)
    }

    /** default: own node plus a cloud key is mode 2 */
    @Test
    fun defaultOwnNodePlusACloudKeyIsMode2() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = false,
            wantsOwnNode = true,
            hasHomeZone = false,
            hasCloudKey = true,
            hasOnDeviceModel = false,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.LOCAL_CLOUD_NO_ZONE, d.mode)
    }

    /** default: own node plus a cloud key is mode 2 even with a home zone */
    @Test
    fun defaultOwnNodePlusACloudKeyIsMode2EvenWithAHomeZone() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = false,
            wantsOwnNode = true,
            hasHomeZone = true,
            hasCloudKey = true,
            hasOnDeviceModel = false,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.LOCAL_CLOUD_NO_ZONE, d.mode)
    }

    /** default: cloud key alone is mode 2 */
    @Test
    fun defaultCloudKeyAloneIsMode2() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = false,
            wantsOwnNode = false,
            hasHomeZone = false,
            hasCloudKey = true,
            hasOnDeviceModel = false,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.LOCAL_CLOUD_NO_ZONE, d.mode)
    }

    /** default: nothing configured is undecided, never a local model */
    @Test
    fun defaultNothingConfiguredIsUndecidedNeverALocalModel() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = false,
            wantsOwnNode = true,
            hasHomeZone = false,
            hasCloudKey = false,
            hasOnDeviceModel = true,
            preferredBacking = Backing.HOME,
        ))
        assertNull(d.mode)
        assertTrue(d.reason.isNotEmpty())
    }

    /** experimental: terminal onto the home zone */
    @Test
    fun experimentalTerminalOntoTheHomeZone() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = false,
            hasHomeZone = true,
            hasCloudKey = false,
            hasOnDeviceModel = false,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.REMOTE_TERMINAL, d.mode)
    }

    /** experimental: terminal ignores a cloud key */
    @Test
    fun experimentalTerminalIgnoresACloudKey() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = false,
            hasHomeZone = true,
            hasCloudKey = true,
            hasOnDeviceModel = true,
            preferredBacking = Backing.CLOUD,
        ))
        assertEquals(PhoneMode.REMOTE_TERMINAL, d.mode)
    }

    /** experimental: own node, home GPU behind it */
    @Test
    fun experimentalOwnNodeHomeGpuBehindIt() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = true,
            hasHomeZone = true,
            hasCloudKey = false,
            hasOnDeviceModel = true,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.LOCAL_HOME_GPU, d.mode)
    }

    /** experimental: own node, cloud behind it */
    @Test
    fun experimentalOwnNodeCloudBehindIt() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = true,
            hasHomeZone = true,
            hasCloudKey = true,
            hasOnDeviceModel = true,
            preferredBacking = Backing.CLOUD,
        ))
        assertEquals(PhoneMode.LOCAL_CLOUD, d.mode)
    }

    /** experimental: backing is the user's call, not the hardware's */
    @Test
    fun experimentalBackingIsTheUserSCallNotTheHardwareS() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = true,
            hasHomeZone = true,
            hasCloudKey = true,
            hasOnDeviceModel = false,
            preferredBacking = Backing.CLOUD,
        ))
        assertEquals(PhoneMode.LOCAL_CLOUD, d.mode)
    }

    /** experimental: no home zone, cloud key */
    @Test
    fun experimentalNoHomeZoneCloudKey() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = true,
            hasHomeZone = false,
            hasCloudKey = true,
            hasOnDeviceModel = false,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.LOCAL_CLOUD_NO_ZONE, d.mode)
    }

    /** experimental: no home zone, on-device only */
    @Test
    fun experimentalNoHomeZoneOnDeviceOnly() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = true,
            hasHomeZone = false,
            hasCloudKey = false,
            hasOnDeviceModel = true,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.LOCAL_ON_DEVICE, d.mode)
    }

    /** experimental: cloud key beats on-device when both exist */
    @Test
    fun experimentalCloudKeyBeatsOnDeviceWhenBothExist() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = true,
            hasHomeZone = false,
            hasCloudKey = true,
            hasOnDeviceModel = true,
            preferredBacking = Backing.HOME,
        ))
        assertEquals(PhoneMode.LOCAL_CLOUD_NO_ZONE, d.mode)
    }

    /** experimental: terminal with nothing to terminal onto */
    @Test
    fun experimentalTerminalWithNothingToTerminalOnto() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = false,
            hasHomeZone = false,
            hasCloudKey = true,
            hasOnDeviceModel = true,
            preferredBacking = Backing.HOME,
        ))
        assertNull(d.mode)
        assertTrue(d.reason.isNotEmpty())
    }

    /** experimental: own node but nothing can answer */
    @Test
    fun experimentalOwnNodeButNothingCanAnswer() {
        val d = decideMode(ModeInputs(
            onDeviceModelViable = true,
            wantsOwnNode = true,
            hasHomeZone = false,
            hasCloudKey = false,
            hasOnDeviceModel = false,
            preferredBacking = Backing.HOME,
        ))
        assertNull(d.mode)
        assertTrue(d.reason.isNotEmpty())
    }

    /** Same home zone, different products — a zone alone decides nothing. */
    @Test
    fun havingAHomeZoneAloneNeverDecidesTheMode() {
        fun m(wants: Boolean) = decideMode(ModeInputs(
            onDeviceModelViable = true, wantsOwnNode = wants, hasHomeZone = true,
            hasCloudKey = false, hasOnDeviceModel = true,
            preferredBacking = Backing.HOME,
        )).mode
        assertEquals(PhoneMode.REMOTE_TERMINAL, m(false))
        assertEquals(PhoneMode.LOCAL_HOME_GPU, m(true))
    }

    /**
     * The safety property in one test: with the gate closed, NO combination of
     * inputs can put a model on the phone.
     */
    @Test
    fun theDefaultReachesOnlyModes1And2() {
        val allowed = setOf(null, PhoneMode.REMOTE_TERMINAL, PhoneMode.LOCAL_CLOUD_NO_ZONE)
        for (wants in listOf(true, false)) {
            for (zone in listOf(true, false)) {
                for (key in listOf(true, false)) {
                    for (model in listOf(true, false)) {
                        val d = decideMode(ModeInputs(
                            onDeviceModelViable = false, wantsOwnNode = wants,
                            hasHomeZone = zone, hasCloudKey = key,
                            hasOnDeviceModel = model, preferredBacking = Backing.HOME,
                        ))
                        assertTrue(d.mode in allowed, "gate leaked mode ${d.mode}")
                    }
                }
            }
        }
    }

    @Test
    fun onlyMode1SkipsTheLocalNode() {
        assertEquals(false, runsLocalNode(PhoneMode.REMOTE_TERMINAL))
        for (m in listOf(
            PhoneMode.LOCAL_CLOUD_NO_ZONE, PhoneMode.LOCAL_ON_DEVICE,
            PhoneMode.LOCAL_HOME_GPU, PhoneMode.LOCAL_CLOUD,
        )) assertTrue(runsLocalNode(m))
    }

    /** Modes 1 and 2 are the defaults precisely because they need nothing here. */
    @Test
    fun onlyTheExperimentalModesDownloadAModel() {
        assertEquals(false, wantsOnDeviceModel(PhoneMode.REMOTE_TERMINAL))
        assertEquals(false, wantsOnDeviceModel(PhoneMode.LOCAL_CLOUD_NO_ZONE))
        for (m in listOf(
            PhoneMode.LOCAL_ON_DEVICE, PhoneMode.LOCAL_HOME_GPU, PhoneMode.LOCAL_CLOUD,
        )) assertTrue(wantsOnDeviceModel(m))
    }
}

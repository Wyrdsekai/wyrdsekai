package org.wyrdsekai.app.hermod

import kotlinx.coroutines.flow.MutableStateFlow
import org.wyrdsekai.app.network.PairingClient.PairingCredentials

/**
 * Seam for minting this device's identity over whatever remote transport
 * the platform has (androidMain installs the NATS-over-relay call —
 * `wyrd.zone.{zone}.pair.device`). The consent toggle tries HTTP first
 * (LAN) and falls back here, so a relay-resident phone earns its
 * identity where it lives. Install-pattern like MeshDispatch: absent
 * installation, callers simply skip — consent stays saved and the mint
 * completes on the next door that CAN mint.
 */
object RemoteMint {

    fun interface Minter {
        suspend fun pairDevice(
            sessionToken: String,
            deviceName: String,
            deviceType: String,
        ): PairingCredentials?
    }

    private val holder = MutableStateFlow<Minter?>(null)

    fun install(minter: Minter?) {
        holder.value = minter
    }

    fun installed(): Minter? = holder.value
}

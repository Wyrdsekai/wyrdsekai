package org.wyrdsekai.app.engine.transit

/**
 * No lock: this iOS target has no sealed-tunnel crypto, so a pipe never gets
 * past [SealedTunnelPipe.open] (it refuses and closes at once).
 */
internal actual class PipeLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = block()
}

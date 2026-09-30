package org.wyrdsekai.app.engine.transit

internal actual class PipeLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = synchronized(this, block)
}

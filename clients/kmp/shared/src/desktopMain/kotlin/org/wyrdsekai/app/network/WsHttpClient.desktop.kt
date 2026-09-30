package org.wyrdsekai.app.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Desktop actual: default engine with system trust only. Household-cert
 * pinning on desktop is not wired — desktop
 * nodes talk to the relay through the JVM zone stack instead. Plain ws://
 * is refused off the device.
 */
actual fun createWsHttpClient(): HttpClient = HttpClient {
    install(WebSockets)
    install(RefusePlaintextOffDevice)
}

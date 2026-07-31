package org.wyrdsekai.app.network

import io.ktor.client.HttpClient

/**
 * WebSocket-capable HttpClient that honors household-CA trust per platform
 * a wss:// connection to a relay must accept either
 * a system-trusted chain or the TOFU/invite-pinned household cert.
 *
 * - Android: OkHttp engine wired with [HouseholdTrustManager].
 * - iOS: Darwin engine with a challenge handler consulting the pinned store.
 * - Desktop: system trust only (pinning not wired, see parity matrix).
 *
 * Each actual installs the WebSockets plugin.
 */
expect fun createWsHttpClient(): HttpClient

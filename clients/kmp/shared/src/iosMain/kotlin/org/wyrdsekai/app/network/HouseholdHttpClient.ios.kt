package org.wyrdsekai.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * iOS actual: uses URLSession (Darwin engine) with system + user CAs.
 *
 * Phase 2 will add NSURLAuthenticationChallengeSender hooks via
 * `Darwin { configureSession {} }` to support per-host TOFU pinning
 * — parallel to Android's HouseholdTrustManager. Until then, the user
 * installs the household CA in iOS Settings → Profiles & Device Mgmt.
 */
actual fun createHouseholdHttpClient(): HttpClient = HttpClient(Darwin)

package org.wyrdsekai.app.network

import io.ktor.client.HttpClient

/**
 * Platform-specific HTTP client factory for household-relay traffic.
 *
 * On Android, the implementation installs a custom OkHttp [TrustManager]
 * that consults a SharedPreferences-backed [HouseholdTrustStore] and
 * accepts certs pinned per-host. This lets the phone talk HTTPS to a
 * relay using a household-CA leaf cert that the system trust store
 * (Let's Encrypt + system roots) would otherwise reject.
 *
 * On Desktop/iOS this falls through to the default `HttpClient()` — those
 * platforms either use the system / OS user trust store (Desktop: JDK
 * cacerts + JVM_OPTIONS user keystore; iOS: URLSession) or rely on the
 * user installing the household CA OS-wide. Per-host pinning equivalents
 * are TODO for those platforms.
 *
 */
expect fun createHouseholdHttpClient(): HttpClient

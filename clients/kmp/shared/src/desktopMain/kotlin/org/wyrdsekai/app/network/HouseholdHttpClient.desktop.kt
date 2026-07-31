package org.wyrdsekai.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java

/**
 * Desktop actual: uses the JDK default trust store. Users wanting to
 * trust a household CA install it via `keytool -import` into the JDK
 * cacerts, or supply a custom keystore via `JAVA_TOOL_OPTIONS`.
 *
 * Per-host pinning equivalent is a TODO — Desktop installs are
 * typically operator-managed nodes, not phone-style ephemeral clients,
 * so the operational footprint is lower.
 */
actual fun createHouseholdHttpClient(): HttpClient = HttpClient(Java)

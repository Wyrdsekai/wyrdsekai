package org.wyrdsekai.app.ui.screens

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope

/**
 * True where the zone-bank surface is REAL (androidMain). iOS/desktop actuals
 * are placeholders (iOS ships the RN client; desktop has no phone zone bank) —
 * flows that would land there (e.g. the Welcome invite path, the Node-Settings
 * "My zones" button) must fall back to the direct local-relay boot instead of
 * routing users into a stub screen.
 */
expect val zoneBankSurfaceSupported: Boolean

/**
 * ServersHost — the platform bridge for the "Your servers" +
 * "Find a zone" surface. WyrdApp lives in commonMain, but the screens, the NATS
 * client, OpenZone, and the bank/password stores are androidMain-only — so this
 * `expect` lets commonMain route into them.
 *
 * The androidMain actual loads the bank, renders ServersScreen (→ FindZoneScreen),
 * and on "Enter" persists the winning zone's relay credentials before calling
 * [onEnterLocal], so the existing, live-proven local-mode boot re-establishes the
 * relay leg and opens the world. Other targets render a short placeholder.
 *
 * @param onExit back out of the servers surface (e.g. to the welcome screen).
 * @param onEnterLocal sign-in succeeded + creds persisted → flip to local mode.
 */
@Composable
expect fun ServersHost(
    scope: CoroutineScope,
    onExit: () -> Unit,
    onEnterLocal: () -> Unit,
)

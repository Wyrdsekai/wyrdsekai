package org.wyrdsekai.app.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import kotlinx.coroutines.MainScope
import org.wyrdsekai.app.network.HouseholdTrustStore
import org.wyrdsekai.app.state.TokenStore
import org.wyrdsekai.app.ui.WyrdApp
import org.wyrdsekai.app.platform.PlatformContext

class MainActivity : ComponentActivity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize TokenStore with Android context (must happen before WyrdApp)
        TokenStore.init(this)
        PlatformContext.init(this)
        // Initialize household-CA pin store BEFORE any HTTPS handshake fires.
        // The custom OkHttp TrustManager (HouseholdTrustManager) reads from
        // this on every TLS connect — empty store means system-trust-only.
        HouseholdTrustStore.init(this)

        // Set data directories to app-private storage
        System.setProperty("wyrdsekai.data.dir", filesDir.absolutePath)
        val modelsDir = filesDir.resolve("models")
        modelsDir.mkdirs()
        System.setProperty("wyrdsekai.models.dir", modelsDir.absolutePath)

        // Handle share intent (text/URL from other apps → Study)
        handleShareIntent(intent)

        setContent {
            // Box.semantics(testTagsAsResourceId=true) makes Compose `testTag` strings
            // visible as Android `resource-id` to UIAutomator/Maestro. Without this,
            // Maestro's `id:` selectors can't find any Compose-only nodes.
            @OptIn(ExperimentalComposeUiApi::class)
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                WyrdApp(scope)
            }
        }
        // A pin mismatch is refused in the TLS layer and shown by WyrdApp as a
        // plain "pair again" message (SecurityNotices). There is no "trust the
        // new certificate" prompt: the pin came from the invite, and only a
        // fresh invite changes it (D6, ).
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Handle ACTION_SEND from other apps.
     * Saves shared text/URL as a Study pin.
     * The Oracle will pick it up as an interest signal.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""

        // Store as a system property for WyrdApp to pick up
        // (WyrdApp reads this and writes to Study via PhoneNode)
        System.setProperty("wyrdsekai.shared.text", sharedText)
        System.setProperty("wyrdsekai.shared.subject", subject)

        android.util.Log.i("WyrdShare", "Received share: ${subject.take(50)} — ${sharedText.take(100)}")
    }
}

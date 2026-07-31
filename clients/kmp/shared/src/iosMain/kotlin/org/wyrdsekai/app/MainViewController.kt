package org.wyrdsekai.app

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.MainScope
import org.wyrdsekai.app.inference.installLlamatikBridge
import org.wyrdsekai.app.ui.WyrdApp

fun MainViewController() = ComposeUIViewController {
    installLlamatikBridge()
    WyrdApp(MainScope())
}

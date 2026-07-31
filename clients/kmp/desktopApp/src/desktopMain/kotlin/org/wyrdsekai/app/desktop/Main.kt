package org.wyrdsekai.app.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.MainScope
import org.wyrdsekai.app.ui.WyrdApp

fun main() = application {
    val scope = MainScope()

    Window(
        onCloseRequest = ::exitApplication,
        title = "Wyrdsekai",
    ) {
        WyrdApp(scope)
    }
}

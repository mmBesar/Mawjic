package app.mawjic.tv.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.mawjic.tv.Platform
import app.mawjic.tv.MawjicApp

fun main() = application {
    Platform.setPreference("platform", "desktop")
    Platform.configureTlsPolicy()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Mawjic · IPTV",
        state = rememberWindowState(width = androidx.compose.ui.unit.Dp(1280f), height = androidx.compose.ui.unit.Dp(850f)),
    ) {
        MaterialTheme {
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) { MawjicApp() }
        }
    }
}

package app.mawjic.tv.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.mawjic.tv.Platform
import app.mawjic.tv.MawjicApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Platform.initialize(applicationContext)
        Platform.setPreference("platform", "android")
        setContent { MawjicApp() }
    }
}

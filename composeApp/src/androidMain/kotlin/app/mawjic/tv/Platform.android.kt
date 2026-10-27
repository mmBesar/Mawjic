package app.mawjic.tv

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun SystemBarsEffect(backgroundArgb: Long, useDarkIcons: Boolean) {
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = Color(backgroundArgb.toULong()).toArgb()
        window.navigationBarColor = Color(backgroundArgb.toULong()).toArgb()
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = useDarkIcons
        controller.isAppearanceLightNavigationBars = useDarkIcons
    }
}

@Composable
actual fun DocumentPickerButton(label: String, onSelected: (String) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { Platform.documentContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onSelected(uri.toString())
        }
    }
    TextButton(onClick = { launcher.launch(arrayOf("*/*")) }) { Text(label) }
}

actual object Platform {
    private lateinit var appContext: Context
    fun initialize(context: Context) { appContext = context.applicationContext }
    fun documentContext(): Context = appContext
    actual fun getPreference(key: String, defaultValue: String): String = appContext.getSharedPreferences("mawjic", Context.MODE_PRIVATE).getString(key, defaultValue) ?: defaultValue
    actual fun setPreference(key: String, value: String) { appContext.getSharedPreferences("mawjic", Context.MODE_PRIVATE).edit().putString(key, value).apply() }

    actual suspend fun readText(source: String): String = withContext(Dispatchers.IO) {
        require(source.isNotBlank()) { "Enter a URL or local file path." }
        when {
            source.startsWith("content://", true) -> {
                val stream = appContext.contentResolver.openInputStream(Uri.parse(source))
                    ?: error("Android could not open that document URI.")
                stream.use { it.readBoundedUtf8() }
            }
            source.startsWith("file://", true) -> File(Uri.parse(source).path ?: error("Invalid file URI"))
                .inputStream().use { it.readBoundedUtf8() }
            source.startsWith("https://", true) || source.startsWith("http://", true) -> {
                val connection = URL(source).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "Mawjic/0.1.0")
                    connection.connect()
                    val code = connection.responseCode
                    if (code !in 200..299) error("Source returned HTTP $code. Check the URL or authentication.")
                    connection.inputStream.use { it.readBoundedUtf8() }
                } finally { connection.disconnect() }
            }
            else -> File(source).inputStream().use { it.readBoundedUtf8() }
        }
    }

    actual fun parseM3u(text: String): String = NativeCore.call(text, NativeCore::parseM3u)
    actual fun parseXmltv(text: String): String = NativeCore.call(text, NativeCore::parseXmltv)
    actual fun systemDarkMode(): Boolean? = null
    actual fun colorsFromSystem(dark: Boolean): PlatformColors? {
        if (Build.VERSION.SDK_INT < 31 || !::appContext.isInitialized) return null
        val scheme = if (dark) dynamicDarkColorScheme(appContext) else dynamicLightColorScheme(appContext)
        fun value(color: androidx.compose.ui.graphics.Color): Long = color.value.toLong()
        return PlatformColors(value(scheme.primary), value(scheme.secondary), value(scheme.background), value(scheme.surface), value(scheme.surfaceVariant), value(scheme.onPrimary), value(scheme.onBackground), value(scheme.onSurface), value(scheme.outline))
    }
    actual fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L
}

private interface CoreNative : Library {
    fun iptv_parse_m3u(input: String): Pointer?
    fun iptv_parse_xmltv(input: String): Pointer?
    fun iptv_string_free(value: Pointer?)
    fun iptv_core_abi_version(): Int
}
private object NativeCore {
    private val library: CoreNative by lazy {
        Native.load("mawjic_core", CoreNative::class.java).also {
            check(it.iptv_core_abi_version() == 1) { "Mawjic Rust core ABI version mismatch" }
        }
    }
    fun parseM3u(text: String): Pointer? = library.iptv_parse_m3u(text)
    fun parseXmltv(text: String): Pointer? = library.iptv_parse_xmltv(text)
    fun call(text: String, invoke: (String) -> Pointer?): String {
        require(!text.contains('\u0000')) { "Source contains a NUL character and cannot cross the native text boundary." }
        val pointer = invoke(text) ?: error("Rust parser returned a null pointer")
        return try { pointer.getString(0, "UTF-8") } finally { library.iptv_string_free(pointer) }
    }
}

private fun InputStream.readBoundedUtf8(): String {
    val limit = 64 * 1024 * 1024
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Source exceeds the 64 MiB safety limit." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

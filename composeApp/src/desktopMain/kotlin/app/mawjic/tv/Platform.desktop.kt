package app.mawjic.tv

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.prefs.Preferences
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun SystemBarsEffect(backgroundArgb: Long, useDarkIcons: Boolean) {
    // Window decorations are managed by the Linux desktop environment.
}

@Composable
actual fun DocumentPickerButton(label: String, onSelected: (String) -> Unit) {
    TextButton(onClick = {
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose a playlist or XMLTV file"
            isAcceptAllFileFilterUsed = true
            fileFilter = FileNameExtensionFilter("TV source files (*.m3u, *.m3u8, *.xml, *.xmltv)", "m3u", "m3u8", "xml", "xmltv")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) onSelected(chooser.selectedFile.toURI().toString())
    }) { Text(label) }
}

private object DesktopInsecureTlsSupport {
    val systemContext: SSLContext = SSLContext.getDefault()
    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val insecureContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }
}

actual object Platform {
    private val preferences = Preferences.userNodeForPackage(Platform::class.java)
    actual fun getPreference(key: String, defaultValue: String): String = preferences.get(key, defaultValue)
    actual fun setPreference(key: String, value: String) {
        preferences.put(key, value); preferences.flush()
        if (key == "allowInsecureTls") configureTlsPolicy()
    }
    actual fun configureTlsPolicy() {
        val context = if (getPreference("allowInsecureTls", "false").toBoolean()) DesktopInsecureTlsSupport.insecureContext else DesktopInsecureTlsSupport.systemContext
        SSLContext.setDefault(context)
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
        // Deliberately retain the JVM's default HostnameVerifier.
    }

    actual suspend fun readText(source: String): String = withContext(Dispatchers.IO) {
        require(source.isNotBlank()) { "Enter a URL or local file path." }
        when {
            source.startsWith("https://", true) || source.startsWith("http://", true) -> {
                val connection = URL(source).openConnection() as HttpURLConnection
                if (connection is HttpsURLConnection && getPreference("allowInsecureTls", "false").toBoolean()) {
                    connection.sslSocketFactory = DesktopInsecureTlsSupport.insecureContext.socketFactory
                }
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
            source.startsWith("file://", true) -> File(URI(source)).inputStream().use { it.readBoundedUtf8() }
            else -> File(source).inputStream().use { it.readBoundedUtf8() }
        }
    }

    actual fun parseM3u(text: String): String = NativeCore.call(text, NativeCore::parseM3u)
    actual fun parseXmltv(text: String): String = NativeCore.call(text, NativeCore::parseXmltv)
    actual fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L

    actual fun systemDarkMode(): Boolean? {
        val value = portalRead("color-scheme") ?: return null
        return when (value) { 1 -> true; 2 -> false; else -> null }
    }

    actual fun colorsFromSystem(dark: Boolean): PlatformColors? {
        val rgb = portalAccent() ?: return null
        val primary = argb(rgb[0], rgb[1], rgb[2])
        val secondary = argb((rgb[0] * .82 + if (dark) .18 else .0), (rgb[1] * .82 + if (dark) .18 else .0), (rgb[2] * .82 + if (dark) .18 else .0))
        val background = if (dark) 0xFF1E1E2EL else 0xFFEFF1F5L
        val surface = if (dark) 0xFF181825L else 0xFFE6E9EFL
        val surfaceVariant = if (dark) 0xFF313244L else 0xFFDCE0E8L
        val onSurface = if (dark) 0xFFCDD6F4L else 0xFF4C4F69L
        return PlatformColors(primary, secondary, background, surface, surfaceVariant, if (dark) 0xFF1E1E2EL else 0xFFFFFFFFL, onSurface, onSurface, if (dark) 0xFF585B70L else 0xFF9CA0B0L)
    }

    private fun portalRead(key: String): Int? {
        val output = portalCall(key) ?: return null
        val match = Regex("\\b(?:uint32|uint)\\s+(\\d+)").find(output) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
    private fun portalAccent(): List<Double>? {
        val output = portalCall("accent-color") ?: return null
        val values = Regex("(?<![A-Za-z])(?:0?\\.\\d+|1(?:\\.0+)?)").findAll(output).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (values.size < 3) return null
        val rgb = values.takeLast(3)
        return rgb.takeIf { it.all { value -> value in 0.0..1.0 } }
    }
    private fun portalCall(key: String): String? = try {
        val process = ProcessBuilder(
            "gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop", "--method",
            "org.freedesktop.portal.Settings.Read", "org.freedesktop.appearance", key,
        ).redirectErrorStream(true).start()
        if (!process.waitFor(900, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); return null }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        output.takeIf { process.exitValue() == 0 }
    } catch (_: Exception) { null }

    private fun argb(r: Double, g: Double, b: Double): Long {
        fun channel(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (0xFF000000L or (channel(r).toLong() shl 16) or (channel(g).toLong() shl 8) or channel(b).toLong())
    }
}

private interface CoreNative : Library {
    fun iptv_parse_m3u(input: String): Pointer?
    fun iptv_parse_xmltv(input: String): Pointer?
    fun iptv_string_free(value: Pointer?)
    fun iptv_core_abi_version(): Int
}

private object NativeCore {
    private val library: CoreNative by lazy {
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val resourceArch = if (arch == "aarch64" || arch == "arm64") "linux-arm64" else "linux-x64"
        val resource = "/native/$resourceArch/libmawjic_core.so"
        val stream = NativeCore::class.java.getResourceAsStream(resource)
            ?: error("Rust core not packaged ($resource). Build with ./gradlew :composeApp:packageDistributionForCurrentOS.")
        val dir = Files.createTempDirectory("mawjic-native-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val file = dir.resolve("libmawjic_core.so")
        stream.use { input -> Files.copy(input, file) }
        val loaded = try {
            Native.load(file.toAbsolutePath().toString(), CoreNative::class.java)
        } finally {
            // Linux keeps a loaded shared object mapped after its file is unlinked.
            // Remove the private extracted copy so launches do not accumulate temp files.
            runCatching { Files.deleteIfExists(file) }
            runCatching { Files.deleteIfExists(dir) }
        }
        loaded.also {
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

private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024

private fun InputStream.readBoundedBytes(limit: Int = MAX_SOURCE_BYTES): ByteArray {
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
    return output.toByteArray()
}

private fun InputStream.readBoundedUtf8(): String {
    val bytes = use { it.readBoundedBytes() }
    val looksGzipped = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    val decoded = if (looksGzipped) {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBoundedBytes() }
    } else bytes
    return decoded.toString(Charsets.UTF_8)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
actual fun GuideVerticalScrollbar(
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: androidx.compose.ui.Modifier,
) {
    androidx.compose.foundation.VerticalScrollbar(
        modifier = modifier,
        adapter = androidx.compose.foundation.rememberScrollbarAdapter(scrollState = state),
    )
}

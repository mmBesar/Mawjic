package app.mawjic.tv

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

val flavours = listOf("Latte", "Frappé", "Macchiato", "Mocha")
val accents = listOf("Rosewater", "Flamingo", "Pink", "Mauve", "Red", "Maroon", "Peach", "Yellow", "Green", "Teal", "Sky", "Sapphire", "Blue", "Lavender")
val appearanceModes = listOf("System", "Light", "Dark", "AMOLED Black")
val accentSources = listOf("Catppuccin", "Android Dynamic", "Linux Native System Colours")

data class AppSettings(
    val flavour: String = "Mocha",
    val accent: String = "Mauve",
    val appearance: String = "System",
    val accentSource: String = "Catppuccin",
    val language: String = "English",
    val allowInsecureTls: Boolean = false,
    val playlistSource: String = "",
    val guideSource: String = "",
    val favourites: Set<String> = emptySet(),
) {
    fun save() {
        Platform.setPreference("flavour", flavour)
        Platform.setPreference("accent", accent)
        Platform.setPreference("appearance", appearance)
        Platform.setPreference("accentSource", accentSource)
        Platform.setPreference("language", language)
        Platform.setPreference("allowInsecureTls", allowInsecureTls.toString())
        Platform.setPreference("playlistSource", playlistSource)
        Platform.setPreference("guideSource", guideSource)
        Platform.setPreference("favourites", favourites.joinToString("\n"))
    }
    companion object {
        fun load(): AppSettings {
            val favs = Platform.getPreference("favourites", "").lineSequence().filter(String::isNotBlank).toSet()
            return AppSettings(
                flavour = Platform.getPreference("flavour", "Mocha").takeIf(flavours::contains) ?: "Mocha",
                accent = Platform.getPreference("accent", "Mauve").takeIf(accents::contains) ?: "Mauve",
                appearance = Platform.getPreference("appearance", "System").takeIf(appearanceModes::contains) ?: "System",
                accentSource = Platform.getPreference("accentSource", "Catppuccin").takeIf(accentSources::contains) ?: "Catppuccin",
                language = Platform.getPreference("language", "English").takeIf(supportedLanguages::contains) ?: "English",
                allowInsecureTls = Platform.getPreference("allowInsecureTls", "false").toBoolean(),
                playlistSource = Platform.getPreference("playlistSource", ""),
                guideSource = Platform.getPreference("guideSource", ""), favourites = favs,
            )
        }
    }
}

private val accentHexes = mapOf(
    "Latte" to listOf("#dc8a78", "#dd7878", "#ea76cb", "#8839ef", "#d20f39", "#e64553", "#fe640b", "#df8e1d", "#40a02b", "#179299", "#04a5e5", "#209fb5", "#1e66f5", "#7287fd"),
    "Frappé" to listOf("#f2d5cf", "#eebebe", "#f4b8e4", "#ca9ee6", "#e78284", "#ea999c", "#ef9f76", "#e5c890", "#a6d189", "#81c8be", "#99d1db", "#85c1dc", "#8caaee", "#babbf1"),
    "Macchiato" to listOf("#f4dbd6", "#f0c6c6", "#f5bde6", "#c6a0f6", "#ed8796", "#ee99a0", "#f5a97f", "#eed49f", "#a6da95", "#8bd5ca", "#91d7e3", "#7dc4e4", "#8aadf4", "#b7bdf8"),
    "Mocha" to listOf("#f5e0dc", "#f2cdcd", "#f5c2e7", "#cba6f7", "#f38ba8", "#eba0ac", "#fab387", "#f9e2af", "#a6e3a1", "#94e2d5", "#89dceb", "#74c7ec", "#89b4fa", "#b4befe"),
)
internal fun catppuccinAccentHex(flavour: String, accent: String): String {
    val palette = accentHexes[flavour] ?: accentHexes.getValue("Mocha")
    val index = accents.indexOf(accent)
    return if (index >= 0) palette[index] else palette[3]
}

internal fun relativeLuminance(hex: String): Double {
    val raw = hex.removePrefix("#")
    fun channel(offset: Int): Double {
        val value = raw.substring(offset, offset + 2).toInt(16) / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
}

internal fun primaryForegroundHex(hex: String): String = if (relativeLuminance(hex) > 0.179) "#000000" else "#FFFFFF"

// Compose Color(ULong) is its packed internal representation, not 0xAARRGGBB.
// Use Color(Int) for ARGB and keep PlatformColors normalized to ARGB across platforms.
internal fun colorFromArgb(argb: Long): Color = Color(argb.toInt())

internal fun colorFromHex(hex: String): Color =
    colorFromArgb(0xFF000000L or hex.removePrefix("#").toLong(16))

private fun color(hex: String): Color = colorFromHex(hex)
private fun platformColor(value: Long): Color = colorFromArgb(value)

private fun contrastingText(background: Color): Color {
    fun channel(component: Float): String {
        // Avoid a crash if a malformed external colour ever reaches this boundary.
        val safe = if (component.isFinite()) component.coerceIn(0f, 1f) else 0f
        return (safe * 255).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    }
    val hex = "#${channel(background.red)}${channel(background.green)}${channel(background.blue)}"
    return if (primaryForegroundHex(hex) == "#000000") Color.Black else Color.White
}

@Composable
fun resolveTheme(settings: AppSettings, systemTick: Int): ColorScheme {
    val mode = settings.appearance
    val systemDark = Platform.systemDarkMode()
    val dark = when (mode) {
        "Light" -> false
        "Dark", "AMOLED Black" -> true
        else -> systemDark ?: isSystemInDarkTheme()
    }
    // Desktop portal settings are re-read periodically; Android dynamic palette is OS-provided.
    val platform = when (settings.accentSource) {
        "Android Dynamic" -> Platform.colorsFromSystem(dark).takeIf { Platform.getPreference("platform", "android") == "android" }
        "Linux Native System Colours" -> Platform.colorsFromSystem(dark).takeIf { Platform.getPreference("platform", "desktop") == "desktop" }
        else -> null
    }
    @Suppress("UNUSED_VARIABLE") val tick = systemTick
    val accent = platform?.primary?.let(::platformColor) ?: color(catppuccinAccentHex(settings.flavour, settings.accent))
    val surface = if (dark) when (settings.flavour) {
        "Frappé" -> color("#303446")
        "Macchiato" -> color("#24273a")
        "Latte" -> color("#1e1e2e")
        else -> color("#1e1e2e")
    } else color("#eff1f5")
    val mantle = if (dark) when (settings.flavour) {
        "Frappé" -> color("#292c3c")
        "Macchiato" -> color("#1e2030")
        else -> color("#181825")
    } else color("#e6e9ef")
    val text = if (dark) when (settings.flavour) {
        "Frappé" -> color("#c6d0f5")
        "Macchiato" -> color("#cad3f5")
        else -> color("#cdd6f4")
    } else color("#4c4f69")
    val trueBlack = mode == "AMOLED Black"
    val bg = if (trueBlack) Color.Black else platform?.background?.let(::platformColor) ?: surface
    val cardSurface = if (trueBlack) Color.Black else platform?.surface?.let(::platformColor) ?: mantle
    val onText = platform?.onSurface?.let(::platformColor) ?: text
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = accent,
        onPrimary = contrastingText(accent),
        secondary = platform?.secondary?.let(::platformColor) ?: accent.copy(alpha = 0.82f),
        background = bg,
        surface = cardSurface,
        surfaceVariant = if (trueBlack) color("#101010") else platform?.surfaceVariant?.let(::platformColor) ?: if (dark) color("#313244") else color("#dce0e8"),
        onBackground = platform?.onBackground?.let(::platformColor) ?: onText,
        onSurface = onText,
        outline = platform?.outline?.let(::platformColor) ?: if (dark) color("#585b70") else color("#9ca0b0"),
    )
}

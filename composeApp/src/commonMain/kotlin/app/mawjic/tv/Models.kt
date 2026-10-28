package app.mawjic.tv

import androidx.compose.runtime.Composable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = "",
    val xmltvName: String = "",
)

data class Programme(
    val channelId: String,
    val title: String,
    val description: String,
    val image: String,
    val start: Long,
    val end: Long,
    val subtitle: String = "",
    val episodeNumber: String = "",
)

data class GuideData(val channelLogos: Map<String, String>, val channelNames: Map<String, String>, val programmes: List<Programme>)

/** Visible EPG horizon. The window starts at the previous half-hour boundary and spans 12 hours. */
internal const val GUIDE_TIMELINE_HOURS = 12
internal const val GUIDE_TIMELINE_TICK_SECONDS = 300L
private const val GUIDE_TIMELINE_SECONDS_PER_HOUR = 3600L
private const val GUIDE_TIMELINE_ALIGNMENT_SECONDS = 1800L

internal data class GuideTimelineRange(val startEpoch: Long, val endEpoch: Long)

/** Tick timestamps at five-minute intervals, including the end boundary. */
internal fun guideTimelineTicks(rangeStartEpochSeconds: Long): List<Long> =
    (0..(GUIDE_TIMELINE_HOURS * 12)).map { index ->
        rangeStartEpochSeconds + index * GUIDE_TIMELINE_TICK_SECONDS
    }

internal fun guideTimelineRange(nowEpochSeconds: Long): GuideTimelineRange {
    val start = nowEpochSeconds - (nowEpochSeconds % GUIDE_TIMELINE_ALIGNMENT_SECONDS)
    return GuideTimelineRange(
        startEpoch = start,
        endEpoch = start + GUIDE_TIMELINE_HOURS * GUIDE_TIMELINE_SECONDS_PER_HOUR,
    )
}

/** Indexed programme groups with exact ID and normalized display-name fallback. */
class EpgIndex(guide: GuideData) {
    // Keep per-channel schedules in start-time order for current/next and range queries.
    private val programmesById = guide.programmes.groupBy(Programme::channelId)
        .mapValues { (_, programmes) -> programmes.sortedBy(Programme::start) }
    // Prefix maxima allow binary search to find the earliest programme that can
    // overlap a range, including unusually long programmes overlapping later entries.
    private val prefixMaxEndById = programmesById.mapValues { (_, programmes) ->
        var maxEnd = Long.MIN_VALUE
        LongArray(programmes.size) { index ->
            maxEnd = maxOf(maxEnd, programmes[index].end)
            maxEnd
        }
    }
    private val idByNormalizedName = guide.channelNames.entries
        .groupBy { normalize(it.value) }
        .mapValues { (_, matches) -> matches.first().key }

    private fun resolvedChannelId(channel: Channel): String {
        val name = normalize(channel.name)
        return when {
            channel.id in programmesById -> channel.id
            name in idByNormalizedName -> idByNormalizedName.getValue(name)
            else -> channel.id
        }
    }

    fun programmesFor(channel: Channel): List<Programme> =
        programmesById[resolvedChannelId(channel)].orEmpty()

    /** Returns programmes intersecting [startEpoch, endEpoch), sorted by start time. */
    fun programmesInRange(channel: Channel, startEpoch: Long, endEpoch: Long): List<Programme> {
        if (endEpoch <= startEpoch) return emptyList()
        val id = resolvedChannelId(channel)
        val programmes = programmesById[id].orEmpty()
        val prefixMaxEnds = prefixMaxEndById[id] ?: return emptyList()
        if (programmes.isEmpty()) return emptyList()

        // First index whose prefix contains an end strictly after the range start.
        var low = 0
        var high = programmes.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (prefixMaxEnds[middle] <= startEpoch) low = middle + 1 else high = middle
        }
        val first = low
        if (first >= programmes.size) return emptyList()

        // First programme whose start is at or after the exclusive range end.
        low = first
        high = programmes.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (programmes[middle].start < endEpoch) low = middle + 1 else high = middle
        }
        val last = low
        if (first >= last) return emptyList()
        return programmes.subList(first, last).filter { it.end > startEpoch && it.start < endEpoch }
    }

    fun current(channel: Channel, nowEpoch: Long): Programme? =
        programmesFor(channel).firstOrNull { it.start <= nowEpoch && it.end > nowEpoch }

    fun next(channel: Channel, nowEpoch: Long): Programme? {
        val current = current(channel, nowEpoch)
        return programmesFor(channel).firstOrNull { it.start >= (current?.end ?: nowEpoch) }
    }

    private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
}

data class PlatformColors(
    val primary: Long, val secondary: Long, val background: Long, val surface: Long,
    val surfaceVariant: Long, val onPrimary: Long, val onBackground: Long,
    val onSurface: Long, val outline: Long,
)

object PlatformBridge {
    fun parseChannelsJson(raw: String): List<Channel> {
        val root = Json.parseToJsonElement(raw)
        if (root is JsonObject && root["error"] != null) {
            error(root["error"]!!.jsonPrimitive.content)
        }
        return root.jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val url = obj.string("url")
            if (url.isBlank()) null else Channel(
                id = obj.string("id", url),
                name = obj.string("name", url),
                url = url,
                group = obj.string("group"),
                logo = ArtworkResolver.safeUrl(obj.string("logo")),
            )
        }
    }

    fun parseGuideJson(raw: String): GuideData {
        val root = Json.parseToJsonElement(raw)
        if (root is JsonObject && root["error"] != null) error(root["error"]!!.jsonPrimitive.content)
        val obj = root.jsonObject
        val xmltvChannels = obj["channels"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val id = item.string("id")
            if (id.isBlank()) null else id to (item.string("name") to ArtworkResolver.safeUrl(item.string("logo")))
        }.toMap()
        val logos = xmltvChannels.mapNotNull { (id, pair) -> pair.second.takeIf(String::isNotBlank)?.let { id to it } }.toMap()
        val names = xmltvChannels.mapNotNull { (id, pair) -> pair.first.takeIf(String::isNotBlank)?.let { id to it } }.toMap()
        val programmes = obj["programmes"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val start = item.long("start")
            val end = item.long("end")
            val channel = item.string("channel")
            val title = item.string("title")
            if (channel.isBlank() || title.isBlank() || end <= start) null else Programme(
                channelId = channel,
                title = title,
                description = item.string("description"),
                image = ArtworkResolver.safeUrl(item.string("image")),
                start = start,
                end = end,
                subtitle = item.string("subtitle"),
                episodeNumber = item.string("episodeNumber"),
            )
        }.sortedBy(Programme::start)
        return GuideData(logos, names, programmes)
    }

    private fun JsonObject.string(key: String, fallback: String = ""): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: fallback

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
}

/** Shared, conservative artwork resolution. Never invents endpoints or scrapes metadata sites. */
object ArtworkResolver {
    private val mappings = mutableMapOf<String, String>()
    fun safeUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length > 4096) return ""
        val scheme = trimmed.substringBefore(':', "").lowercase()
        if (scheme !in setOf("https", "http") || !trimmed.startsWith("$scheme://", ignoreCase = true)) return ""
        val authority = trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return trimmed.takeIf { authority.isNotBlank() }.orEmpty()
    }
    fun resolve(
        channel: Channel,
        xmltvLogos: Map<String, String>,
        mappings: Map<String, String> = emptyMap(),
        xmltvNames: Map<String, String> = emptyMap(),
    ): String {
        val mapped = safeUrl(mappings[channel.id].orEmpty())
        if (mapped.isNotEmpty()) return mapped
        safeUrl(channel.logo).takeIf(String::isNotEmpty)?.let { return it }
        safeUrl(xmltvLogos[channel.id].orEmpty()).takeIf(String::isNotEmpty)?.let { return it }
        val name = normalize(channel.name)
        val matchingId = xmltvNames.entries.firstOrNull { normalize(it.value) == name }?.key
        return safeUrl(xmltvLogos[matchingId].orEmpty())
    }

    private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
    fun rememberMapping(channelId: String, url: String) {
        val safe = safeUrl(url)
        if (channelId.isNotBlank() && safe.isNotBlank()) mappings[channelId] = safe
    }
    fun mappings(): Map<String, String> = mappings.toMap()
}

@Composable
expect fun DocumentPickerButton(label: String, onSelected: (String) -> Unit)

@Composable
expect fun SystemBarsEffect(backgroundArgb: Long, useDarkIcons: Boolean)

expect object Platform {
    fun getPreference(key: String, defaultValue: String): String
    fun setPreference(key: String, value: String)
    fun configureTlsPolicy()
    suspend fun readText(source: String): String
    fun parseM3u(text: String): String
    fun parseXmltv(text: String): String
    fun systemDarkMode(): Boolean?
    fun colorsFromSystem(dark: Boolean): PlatformColors?
    fun nowEpochSeconds(): Long
}

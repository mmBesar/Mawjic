package app.mawjic.tv

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Page(val label: String, val glyph: String) {
    LIVE("Live TV", "◉"), GUIDE("TV Guide", "▦"), FAVOURITES("Favourites", "☆"), SETTINGS("Settings", "⚙"),
}

@Composable
fun MawjicApp() {
    // Coil 3 intentionally has no default network fetcher. Register Ktor explicitly
    // at the root composable so the same loader works with the JVM and Android engines.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = { HttpClient() }))
            }
            .crossfade(true)
            .build()
    }
    var settings by remember { mutableStateOf(AppSettings.load()) }
    var systemTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(settings.appearance, settings.accentSource) {
        while (true) { delay(4_000); systemTick++ }
    }
    val colorScheme = resolveTheme(settings, systemTick)
    val useDarkSystemIcons = when (settings.appearance) {
        "Light" -> true
        "Dark", "AMOLED Black" -> false
        else -> !androidx.compose.foundation.isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(
            LocalAppLanguage provides settings.language,
            LocalLayoutDirection provides (if (settings.language == "Arabic") LayoutDirection.Rtl else LayoutDirection.Ltr),
        ) {
            SystemBarsEffect(colorScheme.background.toArgb().toLong(), useDarkSystemIcons)
            MainScreen(settings, onSettings = { settings = it.also(AppSettings::save) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MainScreen(settings: AppSettings, onSettings: (AppSettings) -> Unit) {
    var page by remember { mutableStateOf(Page.LIVE) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var guide by remember { mutableStateOf(GuideData(emptyMap(), emptyMap(), emptyList())) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf("All channels") }
    var showSources by remember { mutableStateOf(false) }
    var selectedProgramme by remember { mutableStateOf<Programme?>(null) }
    var selectedChannel by remember { mutableStateOf<Channel?>(null) }
    var loading by remember { mutableStateOf(false) }
    var sourceReloadVersion by remember { mutableIntStateOf(0) }
    var status by remember(settings.language) { mutableStateOf(if (settings.language == "Arabic") "أضف قائمة تشغيل M3U لبدء المشاهدة." else "Add an M3U playlist to start watching.") }
    val groups = remember(channels) { listOf("All channels") + channels.map(Channel::group).filter(String::isNotBlank).distinct().sorted() }
    var clockEpoch by remember { mutableStateOf(Platform.nowEpochSeconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            clockEpoch = Platform.nowEpochSeconds()
        }
    }

    suspend fun loadSources(playlistSource: String, guideSource: String) {
        if (playlistSource.isBlank()) {
            status = if (settings.language == "Arabic") "أضف قائمة تشغيل M3U لبدء المشاهدة." else "Add an M3U playlist to start watching."
            return
        }
        loading = true
        try {
            status = if (settings.language == "Arabic") "جارٍ تحميل قائمة التشغيل…" else "Loading playlist…"
            val parsed = try {
                val playlistText = Platform.readText(playlistSource.trim())
                withContext(Dispatchers.Default) {
                    PlatformBridge.parseChannelsJson(Platform.parseM3u(playlistText))
                }
            } catch (error: Exception) {
                status = "${if (settings.language == "Arabic") "تعذّر تحميل قائمة التشغيل" else "Could not load playlist"}: ${error.message ?: error::class.simpleName ?: "unknown error"}"
                return
            }

            // Commit the playlist immediately. A broken or unavailable guide must not hide channels.
            channels = parsed
            group = "All channels"
            guide = GuideData(emptyMap(), emptyMap(), emptyList())
            if (guideSource.isBlank()) {
                status = if (settings.language == "Arabic") "تم تحميل ${parsed.size} قناة · لم يُحدَّد دليل البرامج." else "${parsed.size} channels loaded · no EPG source configured."
                return
            }

            status = if (settings.language == "Arabic") "جارٍ تحميل دليل البرامج…" else "Loading programme guide…"
            try {
                val guideText = Platform.readText(guideSource.trim())
                val nextGuide = withContext(Dispatchers.Default) {
                    PlatformBridge.parseGuideJson(Platform.parseXmltv(guideText))
                }
                guide = nextGuide
                status = if (settings.language == "Arabic") "تم تحميل ${parsed.size} قناة · ${nextGuide.programmes.size} برنامجًا." else "${parsed.size} channels loaded · ${nextGuide.programmes.size} programmes loaded."
            } catch (error: Exception) {
                status = "${parsed.size} channels loaded · ${if (settings.language == "Arabic") "تعذّر تحميل دليل البرامج" else "EPG failed"}: ${error.message ?: error::class.simpleName ?: "unknown error"}"
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(settings.playlistSource, settings.guideSource, sourceReloadVersion) {
        if (settings.playlistSource.isNotBlank()) loadSources(settings.playlistSource, settings.guideSource)
    }

    val now = clockEpoch
    val hour = formatTime(now).substringBefore(":").toIntOrNull() ?: 12
    val liveGreeting = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    val epgIndex = remember(guide.programmes, guide.channelNames) { EpgIndex(guide) }
    fun programmesFor(channel: Channel): List<Programme> = epgIndex.programmesFor(channel)
    fun current(channel: Channel): Programme? = epgIndex.current(channel, now)
    fun next(channel: Channel): Programme? = epgIndex.next(channel, now)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactLayout = maxWidth < 680.dp
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Text("M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                        }
                        Column {
                            Text("Mawjic", fontWeight = FontWeight.Bold)
                            if (!compactLayout) Text(tr("Streaming, your wave."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                        }
                    }
                },
                actions = {
                    if (page != Page.SETTINGS) {
                        TextButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) query = "" }) { Text(if (searchExpanded && compactLayout) "×" else "⌕") }
                    }
                    if (!compactLayout) TextButton(onClick = { showSources = true }) { Text(tr("＋  Add source")) }
                    else TextButton(onClick = { showSources = true }) { Text(tr("＋")) }
                    Spacer(Modifier.width(4.dp))
                },
                scrollBehavior = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()),
            )
        },
        bottomBar = {
            if (compactLayout) {
                NavigationBar {
                    Page.entries.forEach { candidate ->
                        NavigationBarItem(
                            selected = page == candidate,
                            onClick = { page = candidate },
                            icon = { Text(candidate.glyph) },
                            label = { Text(tr(candidate.label), maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(Modifier.fillMaxSize().padding(innerPadding).background(MaterialTheme.colorScheme.background)) {
            if (!compactLayout) {
            Column(
                Modifier.width(176.dp).fillMaxHeight().padding(start = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Page.entries.forEach { candidate ->
                    val chosen = page == candidate
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent)
                            .clickable { page = candidate }
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(candidate.glyph, color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                        Text(tr(candidate.label), fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.weight(1f))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(tr("SOURCE STATUS"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                        Text(tr(if (channels.isNotEmpty()) "Connected" else "Not connected"), fontWeight = FontWeight.SemiBold)
                        Text(if (channels.isNotEmpty()) if (settings.language == "Arabic") "${channels.size} قناة" else "${channels.size} channels" else "M3U / XMLTV", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                    }
                }
            }
            Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (compactLayout) 12.dp else 24.dp, end = if (compactLayout) 12.dp else 24.dp, top = 12.dp, bottom = 12.dp)) {
                when {
                    page == Page.SETTINGS -> SettingsPage(settings, onSettings, compactLayout, onDone = { page = Page.LIVE })
                    else -> {
                        if (!compactLayout && page != Page.GUIDE) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tr(when (page) { Page.FAVOURITES -> "Your favourites"; else -> liveGreeting }), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                }
                                if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                            Spacer(Modifier.height(10.dp))
                        } else if (compactLayout && loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (!compactLayout || searchExpanded) {
                            OutlinedTextField(
                                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                                label = { Text(tr("Search channels or programmes")) }, singleLine = true,
                                leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
                            )
                            Spacer(Modifier.height(if (compactLayout) 6.dp else 12.dp))
                        }
                        if (page == Page.GUIDE) {
                            GuideTimeline(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                compactLayout = compactLayout,
                                channels = channels,
                                nowEpoch = now,
                                epgIndex = epgIndex,
                                query = query,
                                logoFor = { channel -> ArtworkResolver.resolve(channel, guide.channelLogos, ArtworkResolver.mappings(), guide.channelNames) },
                                onChannelSelected = { selectedChannel = it },
                                onProgrammeSelected = { selectedProgramme = it },
                            )
                        } else {
                        if (channels.isNotEmpty() && !compactLayout) {
                            Card(
                                modifier = Modifier.fillMaxWidth().height(156.dp).clickable { selectedChannel = channels.firstOrNull() },
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f)),
                            ) {
                                Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(90.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                        val hero = channels.firstOrNull()
                                        val logo = hero?.let { ArtworkResolver.resolve(it, guide.channelLogos, ArtworkResolver.mappings(), guide.channelNames) }.orEmpty()
                                        ChannelLogo(
                                            name = hero?.name.orEmpty(),
                                            logo = logo,
                                            modifier = Modifier.fillMaxSize().padding(9.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(18.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(tr("READY TO WATCH"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        Text(channels.first().name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(current(channels.first())?.title ?: tr("Choose a channel to start streaming"), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Button(onClick = { selectedChannel = channels.first() }) { Text(tr("Watch now  →")) }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            groups.forEach { candidate -> FilterChip(selected = group == candidate, onClick = { group = candidate }, label = { Text(tr(candidate), maxLines = 1) }) }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (channels.isEmpty()) {
                            EmptyState(loading = loading, status = status, onAdd = { showSources = true })
                        } else {
                            val filtered = channels.filter { channel ->
                                (group == "All channels" || channel.group == group) &&
                                    (page != Page.FAVOURITES || channel.url in settings.favourites) &&
                                    (query.isBlank() || channel.name.contains(query, true) || channel.group.contains(query, true) || programmesFor(channel).any { it.title.contains(query, true) || it.subtitle.contains(query, true) || it.episodeNumber.contains(query, true) })
                            }
                            if (status.isNotBlank()) {
                                val guideFailed = status.contains("EPG failed", ignoreCase = true) || status.contains("تعذّر تحميل دليل البرامج")
                                Text(status, style = MaterialTheme.typography.bodySmall, color = if (guideFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .62f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(if (settings.language == "Arabic") "${filtered.size} قناة" else "${filtered.size} CHANNELS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f), fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                                items(filtered, key = { it.url }) { channel ->
                                    val currentProgramme = current(channel)
                                    val nextProgramme = next(channel)
                                    ChannelRow(
                                        channel = channel,
                                        logo = ArtworkResolver.resolve(channel, guide.channelLogos, ArtworkResolver.mappings(), guide.channelNames),
                                        current = currentProgramme,
                                        next = nextProgramme,
                                        favourite = channel.url in settings.favourites,
                                        onFavourite = {
                                            val next = settings.favourites.toMutableSet().apply { if (!add(channel.url)) remove(channel.url) }
                                            onSettings(settings.copy(favourites = next))
                                        },
                                        onPlay = { selectedChannel = channel },
                                        onProgramme = { selectedProgramme = it },
                                    )
                                }
                            }
                        }
                        if (status.isNotBlank() && channels.isEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                        }
                    }
                }
            }
        }
    }
    }

    if (showSources) SourceDialog(
        playlistSource = settings.playlistSource, guideSource = settings.guideSource,
        loading = loading,
        onDismiss = { showSources = false },
        onSave = { playlist, xmltv ->
            val next = settings.copy(playlistSource = playlist.trim(), guideSource = xmltv.trim())
            onSettings(next)
            sourceReloadVersion++ // Also reload when the URLs are unchanged but TLS settings were updated.
            showSources = false
        },
    )
    selectedProgramme?.let { ProgrammeDialog(it) { selectedProgramme = null } }
    selectedChannel?.let { channel ->
        val channelIndex = channels.indexOfFirst { it.url == channel.url }
        val currentProgramme = current(channel)
        PlaybackPane(
            url = channel.url,
            channelName = channel.name,
            channelLogo = ArtworkResolver.resolve(channel, guide.channelLogos, ArtworkResolver.mappings(), guide.channelNames),
            currentProgrammeTitle = currentProgramme?.title.orEmpty(),
            currentProgrammeSubtitle = currentProgramme?.subtitle.orEmpty(),
            previousChannel = channels.getOrNull(channelIndex - 1),
            nextChannel = channels.getOrNull(channelIndex + 1),
            onChannelSelected = { selectedChannel = it },
            onClose = { selectedChannel = null },
            preferredExternalPlayer = settings.externalPlayer,
        )
    }
}

@Composable
private fun GuideTimeline(
    modifier: Modifier = Modifier,
    compactLayout: Boolean,
    channels: List<Channel>,
    nowEpoch: Long,
    epgIndex: EpgIndex,
    query: String,
    logoFor: (Channel) -> String,
    onChannelSelected: (Channel) -> Unit,
    onProgrammeSelected: (Programme) -> Unit,
) {
    val scroll = rememberScrollState()
    val range = guideTimelineRange(nowEpoch)
    val rangeStart = range.startEpoch
    val rangeEnd = range.endEpoch
    val channelColumnWidth = if (compactLayout) 112.dp else 170.dp
    val hourWidth = if (compactLayout) 124.dp else 170.dp
    val timelineWidth = hourWidth * GUIDE_TIMELINE_HOURS
    val markerX = (((nowEpoch - rangeStart).coerceAtLeast(0L).toDouble() / 3600.0) * hourWidth.value).toFloat().dp
    val clockTicks = (0..GUIDE_TIMELINE_HOURS).map { index -> rangeStart + index * 3600L }
    // Cache search results, invalidating them when the playlist, query, or loaded EPG changes.
    val filteredChannels = remember(channels, query, epgIndex) {
        if (query.isBlank()) channels else channels.filter { channel ->
            channel.name.contains(query, true) || channel.group.contains(query, true) || epgIndex.programmesFor(channel).any {
                it.title.contains(query, true) || it.subtitle.contains(query, true) || it.episodeNumber.contains(query, true)
            }
        }
    }
    val markerColor = Color(0xFFFF536D)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("CHANNEL"), Modifier.width(channelColumnWidth).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                Box(Modifier.width(timelineWidth).fillMaxHeight()) {
                    Row(Modifier.fillMaxSize()) {
                        clockTicks.dropLast(1).forEach { tick ->
                            Box(Modifier.width(hourWidth).fillMaxHeight().border(.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f)).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                                Text(formatTime(tick), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .76f))
                            }
                        }
                    }
                    Box(Modifier.offset(x = markerX - 1.dp).width(2.dp).fillMaxHeight().background(markerColor))
                    Box(Modifier.offset(x = markerX - 4.dp, y = 1.dp).size(8.dp).clip(CircleShape).background(markerColor))
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
        if (channels.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(tr("Add a playlist and XMLTV guide to build your programme timeline."), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(filteredChannels, key = { "guide:${it.url}" }) { channel ->
                    val logo = logoFor(channel)
                    val programmes = remember(channel, epgIndex, rangeStart, rangeEnd) {
                        epgIndex.programmesFor(channel).filter { it.end > rangeStart && it.start < rangeEnd }
                    }
                    // The clock marker moves every 15 seconds; programme geometry does not.
                    // Cache clipped x/width values until the EPG, range, or compact width changes.
                    val timelineProgrammes = remember(programmes, rangeStart, rangeEnd, hourWidth) {
                        val pixelsPerHour = hourWidth.value.toDouble()
                        programmes.mapNotNull { programme ->
                            val from = maxOf(programme.start, rangeStart)
                            val until = minOf(programme.end, rangeEnd)
                            if (until <= from) null else {
                                val x = ((from - rangeStart).toDouble() / 3600.0 * pixelsPerHour).toFloat().dp
                                val width = (((until - from).toDouble() / 3600.0 * pixelsPerHour).toFloat().coerceAtLeast(36f)).dp
                                Triple(programme, x, width)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.width(channelColumnWidth).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface).clickable { onChannelSelected(channel) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                ChannelLogo(channel.name, logo, Modifier.fillMaxSize().padding(3.dp))
                            }
                            Text(channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                                .scrollable(state = scroll, orientation = Orientation.Horizontal),
                        ) {
                            Box(
                                Modifier.offset { IntOffset(-scroll.value, 0) }
                                    .requiredWidth(timelineWidth)
                                    .fillMaxHeight(),
                            ) {
                                val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = .18f)
                                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                    val lineWidth = .5.dp.toPx()
                                    val hourPx = hourWidth.toPx()
                                    for (hour in (0 until GUIDE_TIMELINE_HOURS)) {
                                        val x = hour * hourPx
                                        drawLine(
                                            color = gridColor,
                                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                                            strokeWidth = lineWidth,
                                        )
                                    }
                                    drawLine(
                                        color = gridColor,
                                        start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                                        end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                                        strokeWidth = lineWidth,
                                    )
                                    drawLine(
                                        color = gridColor,
                                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                                        strokeWidth = lineWidth,
                                    )
                                    drawLine(
                                        color = gridColor,
                                        start = androidx.compose.ui.geometry.Offset(0f, size.height),
                                        end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                                        strokeWidth = lineWidth,
                                    )
                                }
                                timelineProgrammes.forEach { (programme, x, width) ->
                                        Card(
                                            modifier = Modifier.offset(x = x, y = 7.dp).width(width).height(64.dp).padding(end = 3.dp).clickable { onProgrammeSelected(programme) },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (programme.start <= nowEpoch && programme.end > nowEpoch) .28f else .12f)),
                                        ) {
                                            Column(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(programme.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                                val episodeLine = listOf(programme.subtitle, programme.episodeNumber)
                                                    .filter(String::isNotBlank).distinct().joinToString(" · ")
                                                if (episodeLine.isNotBlank() && width.value >= 42f) Text(episodeLine, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
                                                if (width.value >= 90f) Text("${formatTime(programme.start)}–${formatTime(programme.end)}", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f))
                                            }
                                        }
                                }
                                Box(Modifier.offset(x = markerX - 1.dp).width(2.dp).fillMaxHeight().background(markerColor))
                                Box(Modifier.offset(x = markerX - 4.dp, y = 0.dp).size(8.dp).clip(CircleShape).background(markerColor))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, status: String, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Text(if (loading) "…" else "▶", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(tr(if (loading) "Tuning in…" else "Your TV starts here"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(status, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        Button(onClick = onAdd, enabled = !loading) { Text(tr("Add playlist & guide")) }
    }
}

@Composable
private fun ChannelLogo(name: String, logo: String, modifier: Modifier = Modifier) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo.isNotBlank() && !failed) {
        AsyncImage(
            model = logo,
            contentDescription = "$name logo",
            modifier = modifier,
            contentScale = ContentScale.Fit,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    failed = true
                }
            },
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull()?.uppercase() ?: "TV",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel, logo: String, current: Programme?, next: Programme?, favourite: Boolean,
    onFavourite: () -> Unit, onPlay: () -> Unit, onProgramme: (Programme) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 560.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
                            ChannelLogo(channel.name, logo, Modifier.fillMaxSize().padding(4.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (channel.group.isBlank()) tr("Uncategorized") else channel.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = onFavourite) { Text(if (favourite) "★" else "☆", color = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)) }
                        Button(onClick = onPlay, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { Text(tr("Watch")) }
                    }
                    Column(Modifier.fillMaxWidth().padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                            Text(current?.title ?: tr("No programme information"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        }
                        Text(next?.let { "${tr("Next · ")}${it.title}" } ?: current?.let { "${tr("Until ")}${formatTime(it.end)}" } ?: tr("Guide not available"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        current?.let {
                            val now = Platform.nowEpochSeconds()
                            val fraction = ((now - it.start).toFloat() / (it.end - it.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(MaterialTheme.colorScheme.primary))
                            }
                        }
                        if (current != null) TextButton(onClick = { onProgramme(current) }, contentPadding = PaddingValues(0.dp)) { Text(tr("Details")) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
                        ChannelLogo(channel.name, logo, Modifier.fillMaxSize().padding(5.dp))
                    }
                    Column(Modifier.width(145.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (channel.group.isBlank()) tr("Uncategorized") else channel.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                            Text(current?.title ?: tr("No programme information"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        }
                        Text(next?.let { "${tr("Next · ")}${it.title}" } ?: current?.let { "${tr("Until ")}${formatTime(it.end)}" } ?: tr("Guide not available"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        current?.let {
                            val now = Platform.nowEpochSeconds()
                            val fraction = ((now - it.start).toFloat() / (it.end - it.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                    if (current != null) TextButton(onClick = { onProgramme(current) }) { Text(tr("Details")) }
                    IconButton(onClick = onFavourite) { Text(if (favourite) "★" else "☆", color = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)) }
                    Button(onClick = onPlay, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) { Text(tr("Watch")) }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(settings: AppSettings, onSettings: (AppSettings) -> Unit, compactLayout: Boolean, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compactLayout) {
            Text(tr("Make it yours"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(tr("Your theme, your colours, your screen."), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        }
        SettingsChoices(settings, onSettings)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Playback & sources"), fontWeight = FontWeight.SemiBold)
                Text(tr("Android uses Media3. Linux opens streams in mpv. Add M3U and XMLTV sources from the ‘Add source’ action in the top bar."), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
                Text(tr("Artwork is loaded from playlist and XMLTV image URLs. No third-party scraping is performed."), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
                Text(tr("Self-signed TLS is disabled by default. The unsafe opt-in is for trusted servers only; certificate hostname checks remain enabled."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedButton(onClick = onDone) { Text(tr("Back to TV")) }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoices(settings: AppSettings, onSettings: (AppSettings) -> Unit) {
    var colourOptionsExpanded by remember { mutableStateOf(false) }
    val platform = Platform.getPreference("platform", "desktop")
    val isAndroid = platform == "android"
    val availableAccentSources = if (isAndroid) listOf("Catppuccin", "Android Dynamic") else listOf("Catppuccin", "Linux Native System Colours")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("LANGUAGE"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    supportedLanguages.forEach { candidate ->
                        FilterChip(selected = settings.language == candidate, onClick = { onSettings(settings.copy(language = candidate)) }, label = { Text(tr(candidate)) })
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Playback & network"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (isAndroid) {
                    Text(tr("Preferred Android player"), style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("In-app", "VLC", "MPV").forEach { candidate ->
                            FilterChip(selected = settings.externalPlayer == candidate, onClick = { onSettings(settings.copy(externalPlayer = candidate)) }, label = { Text(tr(candidate)) })
                        }
                    }
                    Text(tr("Choose an installed player; selecting a channel opens it directly."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(tr("Allow self-signed HTTPS certificates"), fontWeight = FontWeight.SemiBold)
                        Text(tr("Unsafe: disables certificate-chain verification for sources and playback. Keep off unless you trust the server/network."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Switch(checked = settings.allowInsecureTls, onCheckedChange = { onSettings(settings.copy(allowInsecureTls = it)) })
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { colourOptionsExpanded = !colourOptionsExpanded }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(tr("Appearance settings"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("${settings.flavour} · ${settings.accent} · ${tr(settings.appearance)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                    }
                    TextButton(onClick = { colourOptionsExpanded = !colourOptionsExpanded }) { Text(tr(if (colourOptionsExpanded) "Hide colour options" else "Show colour options")) }
                }
                if (colourOptionsExpanded) {
                    Text(tr("CATPPUCCIN FLAVOUR"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        flavours.forEach { candidate -> FilterChip(selected = settings.flavour == candidate, onClick = { onSettings(settings.copy(flavour = candidate)) }, label = { Text(candidate) }) }
                    }
                    Text("${tr("ACCENT COLOUR · ")}${settings.accent.uppercase()}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        accents.forEach { candidate ->
                            val selected = settings.accent == candidate
                            val swatch = colorForAccent(settings.flavour, accents.indexOf(candidate))
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .15f) else MaterialTheme.colorScheme.surface)
                                    .border(if (selected) 1.5.dp else .5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .45f), RoundedCornerShape(50))
                                    .clickable { onSettings(settings.copy(accent = candidate)) }.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(swatch))
                                Text(candidate, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text(tr("APPEARANCE"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        appearanceModes.forEach { candidate -> FilterChip(selected = settings.appearance == candidate, onClick = { onSettings(settings.copy(appearance = candidate)) }, label = { Text(tr(candidate)) }) }
                    }
                    Text(tr("ACCENT SOURCE"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        availableAccentSources.forEach { candidate -> FilterChip(selected = settings.accentSource == candidate, onClick = { onSettings(settings.copy(accentSource = candidate)) }, label = { Text(tr(candidate)) }) }
                    }
                    Text(tr("Dynamic colour requires Android 12+. Linux Native System Colours reads XDG Desktop Portal settings; unavailable values fall back to Catppuccin. AMOLED keeps the main backgrounds pure black."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                }
            }
        }
    }
}

private fun colorForAccent(flavour: String, index: Int): Color {
    val hex = catppuccinAccentHex(flavour, accents.getOrElse(index) { "Mauve" })
    return colorFromHex(hex)
}

@Composable
private fun SourceDialog(playlistSource: String, guideSource: String, loading: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var playlist by remember(playlistSource) { mutableStateOf(playlistSource) }
    var xmltv by remember(guideSource) { mutableStateOf(guideSource) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Connect your TV sources")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text(tr("Use an HTTP(S) URL, a local file path, file:// URI, or an Android document URI. Gzip XMLTV (.xml.gz) is supported."), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(playlist, { playlist = it }, label = { Text(tr("M3U / M3U8 playlist *")) }, modifier = Modifier.weight(1f), singleLine = true)
                    DocumentPickerButton(tr("Browse")) { playlist = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(xmltv, { xmltv = it }, label = { Text(tr("XMLTV guide (optional)")) }, modifier = Modifier.weight(1f), singleLine = true)
                    DocumentPickerButton(tr("Browse")) { xmltv = it }
                }
                Text(tr("Tunarr and ErsatzTV work through their standard M3U and XMLTV endpoints; enter the URLs copied from their UI."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f))
            }
        },
        confirmButton = { Button(onClick = { onSave(playlist, xmltv) }, enabled = playlist.isNotBlank() && !loading) { Text(tr(if (loading) "Loading…" else "Save & load")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun ProgrammeDialog(programme: Programme, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(programme.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (programme.image.isNotBlank()) AsyncImage(programme.image, contentDescription = tr("Programme artwork"), modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
                Text("${formatTime(programme.start)} – ${formatTime(programme.end)}")
                val episodeDetails = listOf(programme.subtitle, programme.episodeNumber).filter(String::isNotBlank).distinct()
                if (episodeDetails.isNotEmpty()) Text(episodeDetails.joinToString(" · "), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                Text(programme.description.ifBlank { tr("No programme description was provided by the guide.") }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}

expect fun formatTime(epoch: Long): String

expect @Composable fun PlaybackPane(
    url: String,
    channelName: String,
    channelLogo: String,
    currentProgrammeTitle: String,
    currentProgrammeSubtitle: String,
    previousChannel: Channel?,
    nextChannel: Channel?,
    onChannelSelected: (Channel) -> Unit,
    onClose: () -> Unit,
    preferredExternalPlayer: String = "In-app",
)

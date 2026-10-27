package app.mawjic.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Page(val label: String, val glyph: String) {
    LIVE("Live TV", "◉"), GUIDE("TV Guide", "▦"), FAVOURITES("Favourites", "☆"), SETTINGS("Settings", "⚙"),
}

@Composable
fun MawjicApp() {
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
    var group by remember { mutableStateOf("All channels") }
    var showSources by remember { mutableStateOf(false) }
    var selectedProgramme by remember { mutableStateOf<Programme?>(null) }
    var selectedChannel by remember { mutableStateOf<Channel?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember(settings.language) { mutableStateOf(if (settings.language == "Arabic") "أضف قائمة تشغيل M3U لبدء المشاهدة." else "Add an M3U playlist to start watching.") }
    val groups = remember(channels) { listOf("All channels") + channels.map(Channel::group).filter(String::isNotBlank).distinct().sorted() }

    suspend fun loadSources(playlistSource: String, guideSource: String) {
        if (playlistSource.isBlank()) { status = if (settings.language == "Arabic") "أضف قائمة تشغيل M3U لبدء المشاهدة." else "Add an M3U playlist to start watching."; return }
        loading = true
        status = if (settings.language == "Arabic") "جارٍ تحميل قائمة التشغيل…" else "Loading playlist…"
        try {
            val playlistText = Platform.readText(playlistSource.trim())
            val parsed = withContext(Dispatchers.Default) {
                PlatformBridge.parseChannelsJson(Platform.parseM3u(playlistText))
            }
            val nextGuide = if (guideSource.isBlank()) GuideData(emptyMap(), emptyMap(), emptyList()) else {
                status = if (settings.language == "Arabic") "جارٍ تحميل دليل البرامج…" else "Loading programme guide…"
                val guideText = Platform.readText(guideSource.trim())
                withContext(Dispatchers.Default) {
                    PlatformBridge.parseGuideJson(Platform.parseXmltv(guideText))
                }
            }
            channels = parsed
            guide = nextGuide
            status = if (settings.language == "Arabic") "تم تحميل ${parsed.size} قناة${if (nextGuide.programmes.isNotEmpty()) " · ${nextGuide.programmes.size} برنامجًا" else " · لم يتم تحميل الدليل"}." else "${parsed.size} channels loaded${if (nextGuide.programmes.isNotEmpty()) " · ${nextGuide.programmes.size} programmes" else " · no EPG loaded"}."
            group = "All channels"
        } catch (error: Exception) {
            status = "${if (settings.language == "Arabic") "تعذّر تحميل المصدر" else "Could not load source"}: ${error.message ?: error::class.simpleName ?: "unknown error"}"
        } finally { loading = false }
    }

    LaunchedEffect(settings.playlistSource, settings.guideSource) {
        if (settings.playlistSource.isNotBlank()) loadSources(settings.playlistSource, settings.guideSource)
    }

    val now = Platform.nowEpochSeconds()
    val epgIndex = remember(guide.programmes, guide.channelNames) { EpgIndex(guide) }
    fun programmesFor(channel: Channel): List<Programme> = epgIndex.programmesFor(channel)
    fun current(channel: Channel): Programme? = epgIndex.current(channel, now)
    fun next(channel: Channel): Programme? = epgIndex.next(channel, now)

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
                            Text(tr("Streaming, your wave."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { showSources = true }) { Text(tr("＋  Add source")) }
                    Spacer(Modifier.width(8.dp))
                },
                scrollBehavior = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()),
            )
        },
    ) { innerPadding ->
        Row(Modifier.fillMaxSize().padding(innerPadding).background(MaterialTheme.colorScheme.background)) {
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
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 14.dp)) {
                when {
                    page == Page.SETTINGS -> SettingsPage(settings, onSettings, onDone = { page = Page.LIVE })
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(when (page) { Page.GUIDE -> "TV Guide"; Page.FAVOURITES -> "Your favourites"; else -> "Good evening" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(when (page) { Page.GUIDE -> "What's on now — and what's coming up."; Page.FAVOURITES -> "The channels you keep close."; else -> "Live television, without the clutter." }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .66f))
                            }
                            if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.height(18.dp))
                        if (page == Page.GUIDE) {
                            GuideTimeline(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                channels = channels,
                                nowEpoch = now,
                                programmesFor = { channel -> programmesFor(channel) },
                                logoFor = { channel -> ArtworkResolver.resolve(channel, guide.channelLogos, ArtworkResolver.mappings(), guide.channelNames) },
                                onChannelSelected = { selectedChannel = it },
                                onProgrammeSelected = { selectedProgramme = it },
                            )
                        } else {
                        if (channels.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().height(156.dp).clickable { selectedChannel = channels.firstOrNull() },
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f)),
                            ) {
                                Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(90.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                        val hero = channels.firstOrNull()
                                        val logo = hero?.let { ArtworkResolver.resolve(it, guide.channelLogos, ArtworkResolver.mappings(), guide.channelNames) }.orEmpty()
                                        if (logo.isNotBlank()) AsyncImage(logo, contentDescription = "${hero?.name} logo", modifier = Modifier.fillMaxSize().padding(9.dp), contentScale = ContentScale.Fit)
                                        else Text("▶", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
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
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(tr("Search channels or programmes")) }, singleLine = true,
                            leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
                        )
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            groups.forEach { candidate -> FilterChip(selected = group == candidate, onClick = { group = candidate }, label = { Text(tr(candidate)) }) }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (channels.isEmpty()) {
                            EmptyState(loading = loading, status = status, onAdd = { showSources = true })
                        } else {
                            val filtered = channels.filter { channel ->
                                (group == "All channels" || channel.group == group) &&
                                    (page != Page.FAVOURITES || channel.url in settings.favourites) &&
                                    (query.isBlank() || channel.name.contains(query, true) || channel.group.contains(query, true) || programmesFor(channel).any { it.title.contains(query, true) })
                            }
                            Text(if (settings.language == "Arabic") "${filtered.size} قناة" else "${filtered.size} CHANNELS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f), fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
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
                        if (channels.isNotEmpty() && guide.programmes.isEmpty()) Text(tr("No EPG data yet · add an XMLTV URL in Sources"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
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
            onSettings(next); showSources = false
        },
    )
    selectedProgramme?.let { ProgrammeDialog(it) { selectedProgramme = null } }
    selectedChannel?.let { channel ->
        val channelIndex = channels.indexOfFirst { it.url == channel.url }
        PlaybackPane(
            url = channel.url, channelName = channel.name,
            previousChannel = channels.getOrNull(channelIndex - 1),
            nextChannel = channels.getOrNull(channelIndex + 1),
            onChannelSelected = { selectedChannel = it },
            onClose = { selectedChannel = null },
        )
    }
}

@Composable
private fun GuideTimeline(
    modifier: Modifier = Modifier,
    channels: List<Channel>,
    nowEpoch: Long,
    programmesFor: (Channel) -> List<Programme>,
    logoFor: (Channel) -> String,
    onChannelSelected: (Channel) -> Unit,
    onProgrammeSelected: (Programme) -> Unit,
) {
    val scroll = rememberScrollState()
    val rangeStart = nowEpoch - (nowEpoch % 1800L)
    val rangeEnd = rangeStart + 6L * 3600L
    val hourWidth = 170.dp
    val timelineWidth = hourWidth * 6
    val clockTicks = (0..6).map { index -> rangeStart + index * 3600L }
    Column(modifier.fillMaxSize()) {
        Text(tr("6-HOUR GUIDE"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("CHANNEL"), Modifier.width(170.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                Row(Modifier.width(timelineWidth).fillMaxHeight()) {
                    clockTicks.dropLast(1).forEach { tick ->
                        Box(Modifier.width(hourWidth).fillMaxHeight().border(.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f)).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                            Text(formatTime(tick), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .76f))
                        }
                    }
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
                items(channels, key = { "guide:${it.url}" }) { channel ->
                    val logo = logoFor(channel)
                    val programmes = programmesFor(channel).filter { it.end > rangeStart && it.start < rangeEnd }
                    Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.width(170.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface).clickable { onChannelSelected(channel) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                if (logo.isNotBlank()) AsyncImage(logo, contentDescription = "${channel.name} logo", modifier = Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                                else Text(channel.name.firstOrNull()?.uppercase() ?: "TV", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Text(channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                            Box(Modifier.width(timelineWidth).fillMaxHeight()) {
                                Row(Modifier.fillMaxSize()) {
                                    (0..5).forEach { Box(Modifier.width(hourWidth).fillMaxHeight().border(.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = .18f))) }
                                }
                                programmes.forEach { programme ->
                                    val from = maxOf(programme.start, rangeStart)
                                    val until = minOf(programme.end, rangeEnd)
                                    if (until > from) {
                                        val x = ((from - rangeStart).toDouble() / 3600.0 * 170.0).toFloat().dp
                                        val width = (((until - from).toDouble() / 3600.0 * 170.0).toFloat().coerceAtLeast(36f)).dp
                                        Card(
                                            modifier = Modifier.offset(x = x, y = 7.dp).width(width).height(64.dp).padding(end = 3.dp).clickable { onProgrammeSelected(programme) },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (programme.start <= nowEpoch && programme.end > nowEpoch) .28f else .12f)),
                                        ) {
                                            Column(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(programme.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                                Text("${formatTime(programme.start)}–${formatTime(programme.end)}", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(tr("Programme times follow each XMLTV timestamp offset; timestamps without an offset are interpreted as UTC."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .56f))
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
private fun ChannelRow(
    channel: Channel, logo: String, current: Programme?, next: Programme?, favourite: Boolean,
    onFavourite: () -> Unit, onPlay: () -> Unit, onProgramme: (Programme) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
                if (logo.isNotBlank()) AsyncImage(model = logo, contentDescription = "${channel.name} logo", modifier = Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
                else Text(channel.name.firstOrNull()?.uppercase() ?: "TV", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
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

@Composable
private fun SettingsPage(settings: AppSettings, onSettings: (AppSettings) -> Unit, onDone: () -> Unit) {
    Text(tr("Make it yours"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(tr("Your theme, your colours, your screen."), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
    Spacer(Modifier.height(18.dp))
    SettingsChoices(settings, onSettings)
    Spacer(Modifier.height(20.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(tr("Playback & sources"), fontWeight = FontWeight.SemiBold)
            Text(tr("Android uses Media3. Linux opens streams in mpv. Add M3U and XMLTV sources from the ‘Add source’ action in the top bar."), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
            Text(tr("Artwork is loaded from trusted HTTP(S) logo and XMLTV icon URLs. No third-party scraping is performed."), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onDone) { Text(tr("Back to TV")) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoices(settings: AppSettings, onSettings: (AppSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("LANGUAGE"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                supportedLanguages.forEach { candidate ->
                    FilterChip(selected = settings.language == candidate, onClick = { onSettings(settings.copy(language = candidate)) }, label = { Text(tr(candidate)) })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("CATPPUCCIN FLAVOUR"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                flavours.forEach { candidate ->
                    FilterChip(selected = settings.flavour == candidate, onClick = { onSettings(settings.copy(flavour = candidate)) }, label = { Text(candidate) })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${tr("ACCENT COLOUR · ")}${settings.accent.uppercase()}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accents.forEach { candidate ->
                    val selected = settings.accent == candidate
                    val index = accents.indexOf(candidate)
                    val swatch = colorForAccent(settings.flavour, index)
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .15f) else MaterialTheme.colorScheme.surface)
                            .border(if (selected) 1.5.dp else .5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .45f), RoundedCornerShape(50))
                            .clickable { onSettings(settings.copy(accent = candidate)) }.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(Modifier.size(13.dp).clip(CircleShape).background(swatch))
                        Text(candidate, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("APPEARANCE"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                appearanceModes.forEach { candidate ->
                    FilterChip(selected = settings.appearance == candidate, onClick = { onSettings(settings.copy(appearance = candidate)) }, label = { Text(tr(candidate)) })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ACCENT SOURCE"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accentSources.forEach { candidate ->
                    FilterChip(selected = settings.accentSource == candidate, onClick = { onSettings(settings.copy(accentSource = candidate)) }, label = { Text(tr(candidate)) })
                }
            }
            Text(tr("Dynamic colour requires Android 12+. Linux Native System Colours reads XDG Desktop Portal settings; unavailable values fall back to Catppuccin. AMOLED keeps the main backgrounds pure black."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
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
                Text(tr("Use an HTTP(S) URL, a local file path, file:// URI, or an Android document URI."), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
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
                if (programme.image.isNotBlank()) AsyncImage(programme.image, contentDescription = tr("Programme artwork"), modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                Text("${formatTime(programme.start)} – ${formatTime(programme.end)}")
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
    previousChannel: Channel?,
    nextChannel: Channel?,
    onChannelSelected: (Channel) -> Unit,
    onClose: () -> Unit,
)

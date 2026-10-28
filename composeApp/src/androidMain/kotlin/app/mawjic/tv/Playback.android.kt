package app.mawjic.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        val next = (current as? ContextWrapper)?.baseContext ?: return null
        if (next === current) return null
        current = next
    }
    return null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun PlaybackPane(
    url: String,
    channelName: String,
    channelLogo: String,
    currentProgrammeTitle: String,
    currentProgrammeSubtitle: String,
    previousChannel: Channel?,
    nextChannel: Channel?,
    onChannelSelected: (Channel) -> Unit,
    onClose: () -> Unit,
    preferredExternalPlayer: String,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val originalRequestedOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // Resolve composable localisation during composition; click callbacks are not composable.
    val audioTrackLabel = tr("Audio")
    val subtitlesTrackLabel = tr("Subtitles")
    var channelLogoFailed by remember(channelLogo) { mutableStateOf(false) }
    var selectedExternalPlayer by remember(url, preferredExternalPlayer) { mutableStateOf(preferredExternalPlayer) }
    var useInAppFallback by remember(url, preferredExternalPlayer) { mutableStateOf(preferredExternalPlayer == "In-app") }
    var externalLaunchError by remember(url, preferredExternalPlayer) { mutableStateOf("") }
    if (selectedExternalPlayer != "In-app" && !useInAppFallback) {
        LaunchedEffect(url, selectedExternalPlayer, useInAppFallback) {
            val scheme = url.substringBefore(':', "").lowercase()
            val safeExternalSchemes = setOf("http", "https", "rtsp", "rtmp", "rtmps", "rtp", "mms", "mmst", "mmsh", "tcp", "udp", "file", "content")
            if (scheme !in safeExternalSchemes) {
                externalLaunchError = "Unsupported stream URL scheme: $scheme"
                return@LaunchedEffect
            }
            val packageName = when (selectedExternalPlayer) {
                "VLC" -> "org.videolan.vlc"
                "MPV" -> "is.xyz.mpv"
                else -> null
            }
            if (packageName == null) {
                externalLaunchError = "Unknown external player preference."
            } else {
                try {
                    val uri = Uri.parse(url)
                    val protocolScheme = scheme in setOf("rtmp", "rtmps", "rtp", "rtsp", "mms", "mmst", "mmsh", "tcp", "udp")
                    val intent = if (protocolScheme) {
                        // mpv-android registers these protocols without a MIME type; adding
                        // video/any can prevent Android from matching its protocol intent filter.
                        Intent(Intent.ACTION_VIEW, uri)
                    } else {
                        val mediaType = if (selectedExternalPlayer == "MPV") "video/any" else "video/*"
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mediaType)
                    }
                    intent.setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title", channelName)
                    context.startActivity(intent)
                    onClose()
                } catch (error: ActivityNotFoundException) {
                    externalLaunchError = "$selectedExternalPlayer is not installed or cannot open this stream."
                } catch (error: Exception) {
                    externalLaunchError = "Could not launch $selectedExternalPlayer (${error::class.simpleName ?: "error"})."
                }
            }
        }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(tr("Open in external player")) },
            text = { Text(if (externalLaunchError.isBlank()) tr("Opening channel in preferred external player…") else externalLaunchError) },
            confirmButton = { Button(onClick = { useInAppFallback = true }) { Text(tr("Use in-app player")) } },
            dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
        )
        return
    }
    val scheme = url.substringBefore(':', "").lowercase()
    var retryCount by remember(url) { mutableIntStateOf(0) }
    var lastError by remember(url) { mutableStateOf("") }
    val retryHandler = remember(url) { Handler(Looper.getMainLooper()) }
    if (scheme !in setOf("http", "https", "rtsp", "file", "content")) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Unsupported in-app stream") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Android Media3 does not natively handle the '$scheme' scheme in this player. Choose an installed external player.")
                    if (lastError.isNotBlank()) Text(lastError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selectedExternalPlayer = "VLC"; useInAppFallback = false; externalLaunchError = "" }) { Text("VLC") }
                    TextButton(onClick = { selectedExternalPlayer = "MPV"; useInAppFallback = false; externalLaunchError = "" }) { Text("MPV") }
                }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(tr("Close")) } },
        )
        return
    }
    val player = remember(url) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mawjic/0.1.0")
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Accept" to "*/*", "Icy-MetaData" to "1"))
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build().apply {
            playWhenReady = true
            setHandleAudioBecomingNoisy(true)
        }
    }
    var showOsd by remember(url) { mutableStateOf(true) }
    var showAudioTracks by remember(url) { mutableStateOf(false) }
    var showSubtitleTracks by remember(url) { mutableStateOf(false) }
    var isFullscreen by remember(url) { mutableStateOf(false) }
    // A fullscreen request rotates phones to landscape without permanently locking
    // the app; the previous Activity request is restored on exit and disposal.
    LaunchedEffect(activity, isFullscreen) {
        activity?.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            originalRequestedOrientation
        }
    }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = originalRequestedOrientation }
    }
    var playbackIsPlaying by remember(url) { mutableStateOf(false) }
    var osdRevision by remember(url) { mutableIntStateOf(0) }
    val playerViewRef = remember(player) { AtomicReference<PlayerView?>(null) }
    fun revealOsd() {
        showOsd = true
        osdRevision++
    }

    DisposableEffect(player, url) {
        val lowerUrl = url.substringBefore('?').lowercase()
        val mimeType = when {
            lowerUrl.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lowerUrl.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lowerUrl.endsWith(".ts") -> "video/mp2t"
            else -> null
        }
        var triedHlsFallback = false
        fun mediaItem(type: String? = mimeType): MediaItem = MediaItem.Builder().setUri(url).apply {
            if (type != null) setMimeType(type)
        }.build()
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val causeText = generateSequence(error as Throwable?) { it.cause }
                    .mapNotNull { it.message?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?.replace(Regex("https?://\\S+"), "[stream URL]")
                    ?.take(140)
                lastError = if (causeText == null) error.errorCodeName else "${error.errorCodeName}: $causeText"
                showOsd = true
                osdRevision++

                // Many IPTV servers use extensionless /live/UUID endpoints for HLS.
                // Media3 otherwise treats them as progressive files. On the first
                // failure only, retry that common case once as an HLS playlist.
                if (mimeType == null && url.startsWith("http", ignoreCase = true) && !triedHlsFallback) {
                    triedHlsFallback = true
                    retryHandler.post {
                        player.setMediaItem(mediaItem(MimeTypes.APPLICATION_M3U8))
                        player.prepare()
                        player.playWhenReady = true
                    }
                    return
                }
                if (retryCount < 3) {
                    retryCount++
                    retryHandler.postDelayed({
                        if (player.isCommandAvailable(Player.COMMAND_PREPARE)) {
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }, 1_500L * retryCount)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_READY) lastError = "" }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playbackIsPlaying = isPlaying
                playerViewRef.get()?.keepScreenOn = isPlaying
                if (isPlaying) revealOsd()
            }
        }
        // Attach before prepare so initial DNS/HTTP/decoder errors cannot be missed.
        player.addListener(listener)
        player.setMediaItem(mediaItem())
        player.prepare()
        onDispose {
            retryHandler.removeCallbacksAndMessages(null)
            player.removeListener(listener)
            playerViewRef.getAndSet(null)?.keepScreenOn = false
            playbackIsPlaying = false
            player.release()
        }
    }
    LaunchedEffect(showOsd, osdRevision, playbackIsPlaying, lastError) {
        // Keep controls available while paused or diagnosing an error. During active
        // playback, hide Mawjic's OSD after five seconds of inactivity.
        if (showOsd && playbackIsPlaying && lastError.isBlank()) {
            delay(5_000)
            showOsd = false
        }
    }

    BackHandler(onBack = {
        if (isFullscreen) {
            isFullscreen = false
            revealOsd()
        } else {
            onClose()
        }
    })

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow, isFullscreen) {
            dialogWindow?.let { window ->
                val bars = WindowInsetsControllerCompat(window, window.decorView)
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (isFullscreen) bars.hide(WindowInsetsCompat.Type.systemBars())
                else bars.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        DisposableEffect(dialogWindow) {
            onDispose {
                dialogWindow?.let { window ->
                    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        playerViewRef.set(this)
                        useController = true
                        setControllerShowTimeoutMs(5_000)
                        setControllerHideOnTouch(true)
                        keepScreenOn = player.isPlaying
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                update = {
                    if (it.player !== player) it.player = player
                    it.keepScreenOn = player.isPlaying
                    playerViewRef.set(it)
                },
                onRelease = { view ->
                    view.keepScreenOn = false
                    view.player = null
                    playerViewRef.compareAndSet(view, null)
                },
                modifier = Modifier.fillMaxSize(),
            )

            // The transparent surface restores Mawjic's controls with a tap once the
            // OSD has hidden; it leaves the native Media3 controller available while shown.
            if (!showOsd) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        revealOsd()
                        playerViewRef.get()?.showController()
                    },
                )
            }

            if (showOsd) {
                Column(
                    Modifier.align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .background(Color.Black.copy(alpha = .72f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = .12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (channelLogo.isNotBlank() && !channelLogoFailed) {
                                AsyncImage(
                                    model = channelLogo,
                                    contentDescription = "$channelName logo",
                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                    contentScale = ContentScale.Fit,
                                    onState = { state -> if (state is AsyncImagePainter.State.Error) channelLogoFailed = true },
                                )
                            } else {
                                Text(channelName.take(1).ifBlank { "TV" }, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(channelName, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (currentProgrammeTitle.isNotBlank()) {
                                Text(currentProgrammeTitle, color = Color.White.copy(alpha = .92f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (currentProgrammeSubtitle.isNotBlank()) {
                                Text(currentProgrammeSubtitle, color = Color.White.copy(alpha = .76f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(onClick = { isFullscreen = !isFullscreen; revealOsd() }) {
                            Text(tr(if (isFullscreen) "Exit fullscreen" else "Fullscreen"), color = Color.White, maxLines = 1)
                        }
                        TextButton(onClick = onClose) { Text("✕", color = Color.White) }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        TextButton(onClick = { revealOsd(); previousChannel?.let(onChannelSelected) }, enabled = previousChannel != null) { Text("← ${tr("Previous")}", color = Color.White) }
                        TextButton(onClick = { revealOsd(); nextChannel?.let(onChannelSelected) }, enabled = nextChannel != null) { Text("${tr("Next")} →", color = Color.White) }
                        TextButton(onClick = { revealOsd(); showAudioTracks = true }) { Text(audioTrackLabel, color = Color.White) }
                        TextButton(onClick = { revealOsd(); showSubtitleTracks = true }) { Text("CC", color = Color.White) }
                    }
                    if (lastError.isNotBlank()) {
                        Text("Playback error: $lastError · retry $retryCount/3", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (preferredExternalPlayer != "In-app") {
                                OutlinedButton(onClick = { selectedExternalPlayer = preferredExternalPlayer; useInAppFallback = false; externalLaunchError = "" }) {
                                    Text(tr("Retry with preferred external player"), color = Color.White)
                                }
                            } else {
                                OutlinedButton(onClick = { selectedExternalPlayer = "VLC"; useInAppFallback = false; externalLaunchError = "" }) { Text("VLC", color = Color.White) }
                                OutlinedButton(onClick = { selectedExternalPlayer = "MPV"; useInAppFallback = false; externalLaunchError = "" }) { Text("MPV", color = Color.White) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAudioTracks) {
        TrackSelectionDialog(player, C.TRACK_TYPE_AUDIO, audioTrackLabel) { showAudioTracks = false }
    }
    if (showSubtitleTracks) {
        TrackSelectionDialog(player, C.TRACK_TYPE_TEXT, subtitlesTrackLabel) { showSubtitleTracks = false }
    }
}

private data class TrackSelectionOption(
    val group: Tracks.Group,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
)

@Composable
private fun TrackSelectionDialog(
    player: Player,
    trackType: Int,
    title: String,
    onDismiss: () -> Unit,
) {
    var tracksRevision by remember(player) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { tracksRevision++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val trackFallbackLabel = tr("Track")
    val trackGroups = player.currentTracks.groups.filter { it.type == trackType }
    val parameters = player.trackSelectionParameters
    val isTrackTypeDisabled = parameters.disabledTrackTypes.contains(trackType)
    val hasExplicitOverride = trackGroups.any { parameters.overrides.containsKey(it.mediaTrackGroup) }
    val automaticSelected = !isTrackTypeDisabled && !hasExplicitOverride
    val options = remember(player, trackType, tracksRevision, trackFallbackLabel) {
        trackGroups.flatMap { group ->
            (0 until group.length).mapNotNull { index ->
                if (!group.isTrackSupported(index)) null
                else {
                    val format = group.getTrackFormat(index)
                    val label = listOfNotNull(
                        format.label?.takeIf(String::isNotBlank),
                        format.language?.takeIf { it.isNotBlank() && it != "und" }?.uppercase(),
                        format.sampleMimeType?.substringAfter('/')?.uppercase(),
                    ).distinct().joinToString(" · ").ifBlank { "$trackFallbackLabel ${index + 1}" }
                    TrackSelectionOption(group, index, label, group.isTrackSelected(index))
                }
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                        player.setTrackSelectionParameters(
                            player.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(trackType)
                                .setTrackTypeDisabled(trackType, false)
                                .build(),
                        )
                        onDismiss()
                    }.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = automaticSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = scheme.primary),
                    )
                    Text(tr("Auto"), color = scheme.onSurface)
                }
                if (trackType == C.TRACK_TYPE_TEXT) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            player.setTrackSelectionParameters(
                                player.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(trackType)
                                    .setTrackTypeDisabled(trackType, true)
                                    .build(),
                            )
                            onDismiss()
                        }.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isTrackTypeDisabled,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = scheme.primary),
                        )
                        Text(tr("Off"), color = scheme.onSurface)
                    }
                }
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            player.setTrackSelectionParameters(
                                player.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(trackType)
                                    .setTrackTypeDisabled(trackType, false)
                                    .addOverride(TrackSelectionOverride(option.group.mediaTrackGroup, listOf(option.trackIndex)))
                                    .build(),
                            )
                            onDismiss()
                        }.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option.selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = scheme.primary),
                        )
                        Text(option.label, color = scheme.onSurface)
                    }
                }
                if (options.isEmpty()) {
                    Text(tr("No supported tracks available"), color = scheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
        colors = AlertDialogDefaults.colors(
            containerColor = scheme.surfaceContainerHigh,
            titleContentColor = scheme.onSurface,
            textContentColor = scheme.onSurface,
        ),
    )
}

actual fun formatTime(epoch: Long): String = try {
    val date = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault())
    "%02d:%02d".format(date.hour, date.minute)
} catch (_: Exception) { "--:--" }

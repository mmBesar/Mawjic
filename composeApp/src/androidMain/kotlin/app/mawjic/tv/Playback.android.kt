package app.mawjic.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder

@Composable
actual fun PlaybackPane(
    url: String,
    channelName: String,
    previousChannel: Channel?,
    nextChannel: Channel?,
    onChannelSelected: (Channel) -> Unit,
    onClose: () -> Unit,
    preferredExternalPlayer: String,
) {
    val context = LocalContext.current
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
        }
        // Attach before prepare so initial DNS/HTTP/decoder errors cannot be missed.
        player.addListener(listener)
        player.setMediaItem(mediaItem())
        player.prepare()
        onDispose { retryHandler.removeCallbacksAndMessages(null); player.removeListener(listener); player.release() }
    }
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                update = { if (it.player !== player) it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(channelName, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    TextButton(onClick = onClose) { Text("Close ✕", color = Color.White) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { previousChannel?.let(onChannelSelected) }, enabled = previousChannel != null) { Text("← Previous") }
                    OutlinedButton(onClick = { nextChannel?.let(onChannelSelected) }, enabled = nextChannel != null) { Text("Next →") }
                    TextButton(onClick = { TrackSelectionDialogBuilder(context, "Audio track", player, C.TRACK_TYPE_AUDIO).build().show() }) { Text("Audio", color = Color.White) }
                    TextButton(onClick = { TrackSelectionDialogBuilder(context, "Subtitles", player, C.TRACK_TYPE_TEXT).build().show() }) { Text("CC", color = Color.White) }
                }
                if (lastError.isNotBlank()) {
                    Text("Playback error: $lastError · retry $retryCount/3", color = Color.White)
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

actual fun formatTime(epoch: Long): String = try {
    val date = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault())
    "%02d:%02d".format(date.hour, date.minute)
} catch (_: Exception) { "--:--" }

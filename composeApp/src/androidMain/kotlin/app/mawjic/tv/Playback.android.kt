package app.mawjic.tv

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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
) {
    val context = LocalContext.current
    val scheme = url.substringBefore(':', "").lowercase()
    var retryCount by remember(url) { mutableIntStateOf(0) }
    var lastError by remember(url) { mutableStateOf("") }
    if (scheme !in setOf("http", "https", "rtsp", "file", "content")) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Unsupported in-app stream") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Android Media3 does not natively handle the '$scheme' scheme in this player.")
                    if (lastError.isNotBlank()) Text(lastError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open stream with")) }
                        .onFailure { lastError = "Could not open an external player: ${it::class.simpleName ?: "error"}" }
                }) { Text(tr("Open in external player")) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        )
        return
    }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                lastError = error.errorCodeName
                if (retryCount < 3) {
                    retryCount++
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (player.isCommandAvailable(Player.COMMAND_PREPARE)) {
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }, 1_500L * retryCount)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_READY) lastError = "" }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
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
                // Some providers expose stream variants Media3 cannot play. Let the user hand off explicitly.
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open stream with"))
                    }.onFailure { lastError = "No external player can handle this stream: ${it.message ?: "unknown error"}" }
                }) { Text(tr("Open in external player"), color = Color.White) }
                if (lastError.isNotBlank()) Text("Playback error: $lastError · retry $retryCount/3", color = Color.White)
            }
        }
    }
}

actual fun formatTime(epoch: Long): String = try {
    val date = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault())
    "%02d:%02d".format(date.hour, date.minute)
} catch (_: Exception) { "--:--" }

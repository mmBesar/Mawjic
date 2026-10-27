package app.mawjic.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

@Composable
@Suppress("UNUSED_PARAMETER")
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
    var state by remember { mutableStateOf("Starting mpv…") }
    var process by remember { mutableStateOf<Process?>(null) }
    var socketPath by remember { mutableStateOf("") }
    var requestedUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme !in setOf("http", "https", "rtsp", "rtmp", "udp", "file")) {
            state = "Unsupported stream scheme: $scheme"
            return@LaunchedEffect
        }
        try {
            val dir = Files.createTempDirectory("mawjic-mpv-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val socket = dir.resolve("mpv.sock")
            socketPath = socket.toString()
            requestedUrl = url
            val command = mutableListOf(
                "mpv", "--force-window=yes", "--fullscreen", "--title=Mawjic · $channelName",
                "--input-ipc-server=${socket.toAbsolutePath()}",
            )
            if (Platform.getPreference("allowInsecureTls", "false").toBoolean()) command += "--tls-verify=no"
            command += listOf("--", url)
            val launched = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process = launched
            state = "mpv is playing in its fullscreen window. Audio/subtitles use mpv's on-screen controls."
            val exit = withContext(Dispatchers.IO) { launched.waitFor() }
            if (exit != 0) state = "mpv exited with status $exit. Check that the stream URL is valid and mpv supports its format."
        } catch (error: Exception) {
            state = if (error is java.io.IOException) "Could not start mpv. Install mpv from your Linux distribution's package manager." else "Playback failed: ${error.message ?: "unknown error"}"
        }
    }
    LaunchedEffect(url, socketPath) {
        if (socketPath.isNotBlank() && url != requestedUrl) {
            val scheme = url.substringBefore(':', "").lowercase()
            if (scheme !in setOf("http", "https", "rtsp", "rtmp", "udp", "file")) {
                state = "Unsupported stream scheme: $scheme"
            } else {
                runCatching { sendMpvCommand(socketPath, "[\"loadfile\",${jsonString(url)},\"replace\"]") }
                    .onSuccess { requestedUrl = url; state = "Switching channel…" }
                    .onFailure { state = "Channel switch failed: ${it.message ?: "mpv IPC unavailable"}" }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            process?.let { if (it.isAlive) it.destroy() }
            if (socketPath.isNotBlank()) runCatching { File(socketPath).delete(); File(socketPath).parentFile?.delete() }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("▶  $channelName", maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .78f))
                Text("Playback opens in mpv's own fullscreen window. If it is behind this dialog, switch to the mpv window. mpv's on-screen controller includes audio and subtitle track selection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .63f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { previousChannel?.let(onChannelSelected) }, enabled = previousChannel != null) { Text("← Previous") }
                    Button(onClick = { nextChannel?.let(onChannelSelected) }, enabled = nextChannel != null) { Text("Next →") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close player") } },
    )
}

private fun sendMpvCommand(socketPath: String, json: String) {
    SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
        socket.connect(UnixDomainSocketAddress.of(socketPath))
        val bytes = ("{\"command\":$json}\n").toByteArray(Charsets.UTF_8)
        socket.write(ByteBuffer.wrap(bytes))
    }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { ch -> when (ch) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
    } }
    append('"')
}

actual fun formatTime(epoch: Long): String = try {
    val date = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault())
    "%02d:%02d".format(date.hour, date.minute)
} catch (_: Exception) { "--:--" }

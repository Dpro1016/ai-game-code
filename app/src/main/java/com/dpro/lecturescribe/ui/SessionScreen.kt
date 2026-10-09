@file:OptIn(ExperimentalMaterial3Api::class)

package com.dpro.lecturescribe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dpro.lecturescribe.Line
import com.dpro.lecturescribe.SessionStore
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun SessionScreen(id: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val info = remember(id, version) { SessionStore.info(context, id) }
    val lines = remember(id) { SessionStore.readLines(context, id) }
    if (info == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val player = remember(id) {
        SessionStore.audioFile(context, id).takeIf { it.length() > 0 }?.let { AudioPlayer(it) }
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search this lecture") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(info.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    IconButton(onClick = { shareTranscript(context, id, info.title) }) {
                        Icon(Icons.Default.Share, "Share transcript")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy all text") },
                                leadingIcon = { Icon(AppIcons.Copy, null) },
                                onClick = { menu = false; copyText(context, id, info.title) },
                            )
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                            if (info.hasAudio) {
                                DropdownMenuItem(
                                    text = { Text("Share audio") },
                                    onClick = { menu = false; shareAudio(context, id, info.title) },
                                )
                            }
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
        bottomBar = { if (player != null) PlayerBar(player) },
    ) { padding ->
        Centered(Modifier.padding(padding)) {
            val shown = if (query.isBlank()) lines else lines.filter { it.text.contains(query.trim(), ignoreCase = true) }
            val playingIndex = player?.let { p -> lines.indexOfLast { it.startMs <= p.positionMs }.takeIf { p.started } }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (info.status == SessionStore.INTERRUPTED) {
                    item {
                        Text(
                            "This recording was cut short (the app was closed or the tablet restarted). " +
                                "Everything up to that point was saved.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                if (lines.isEmpty()) {
                    item { Text("No speech was transcribed in this recording.") }
                } else if (shown.isEmpty()) {
                    item { Text("No matches for “$query”.") }
                }
                items(shown) { line ->
                    LineRow(
                        line = line,
                        highlighted = playingIndex != null && lines.getOrNull(playingIndex) === line,
                        onTap = player?.let { p -> { p.seekTo(line.startMs) } },
                    )
                }
            }
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(info.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename lecture") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    SessionStore.update(context, id, title = title.trim().ifEmpty { info.title })
                    version++
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this lecture?") },
            text = { Text("The transcript and audio will be removed from the tablet. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    player?.release()
                    SessionStore.delete(context, id)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LineRow(line: Line, highlighted: Boolean, onTap: (() -> Unit)?) {
    val bg = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            SessionStore.clock(line.startMs) + if (onTap != null) "  ▶" else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(line.text, fontSize = 19.sp, lineHeight = 28.sp)
    }
}

@Composable
private fun PlayerBar(player: AudioPlayer) {
    LaunchedEffect(player) {
        while (true) {
            player.poll()
            delay(250)
        }
    }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledIconButton(onClick = { player.toggle() }, enabled = player.ready) {
                Icon(if (player.playing) AppIcons.Pause else Icons.Default.PlayArrow, if (player.playing) "Pause" else "Play")
            }
            val pos = if (dragging) (dragValue * player.durationMs).toLong() else player.positionMs
            Text(SessionStore.clock(pos), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = if (dragging) dragValue else player.fraction,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    player.seekTo((dragValue * player.durationMs).toLong(), play = player.playing)
                    dragging = false
                },
                enabled = player.ready,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (player.error) "can't play audio" else SessionStore.clock(player.durationMs),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Thin MediaPlayer wrapper exposing Compose state. */
private class AudioPlayer(file: File) {
    private val mp = MediaPlayer()
    var ready by mutableStateOf(false)
    var error by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var started by mutableStateOf(false)
    var durationMs by mutableLongStateOf(0L)
    var positionMs by mutableLongStateOf(0L)
    private var released = false
    val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    init {
        try {
            mp.setDataSource(file.path)
            mp.setOnPreparedListener {
                durationMs = it.duration.toLong().coerceAtLeast(0)
                ready = true
            }
            mp.setOnCompletionListener { playing = false }
            mp.setOnErrorListener { _, _, _ -> error = true; ready = false; playing = false; true }
            mp.prepareAsync()
        } catch (e: Exception) {
            error = true
        }
    }

    fun toggle() {
        if (!ready) return
        if (mp.isPlaying) mp.pause() else mp.start()
        playing = mp.isPlaying
        started = true
    }

    fun seekTo(ms: Long, play: Boolean = true) {
        if (!ready) return
        mp.seekTo(ms.toInt())
        positionMs = ms
        if (play && !mp.isPlaying) mp.start()
        playing = mp.isPlaying
        started = true
    }

    fun poll() {
        if (ready && !released) positionMs = mp.currentPosition.toLong()
    }

    fun release() {
        if (released) return
        released = true
        ready = false
        mp.release()
    }
}

// ------------------------------------------------------------------ sharing

private fun safeName(title: String) = title.replace(Regex("[^A-Za-z0-9 ,._-]"), " ").replace(Regex("\\s+"), " ").trim()
    .ifEmpty { "Lecture" }

private fun shareTranscript(context: Context, id: String, title: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, safeName(title) + ".txt")
    file.writeText(SessionStore.exportText(context, id, timestamps = true))
    share(context, file, "text/plain", title, "Share transcript")
}

private fun shareAudio(context: Context, id: String, title: String) {
    share(context, SessionStore.audioFile(context, id), "audio/aac", title, "Share audio")
}

private fun share(context: Context, file: File, type: String, title: String, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

private fun copyText(context: Context, id: String, title: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(title, SessionStore.exportText(context, id, timestamps = false)))
    Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
}

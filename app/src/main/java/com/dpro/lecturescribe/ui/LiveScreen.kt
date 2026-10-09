@file:OptIn(ExperimentalMaterial3Api::class)

package com.dpro.lecturescribe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpro.lecturescribe.LiveSession
import com.dpro.lecturescribe.LiveState
import com.dpro.lecturescribe.Phase
import com.dpro.lecturescribe.SessionStore

@Composable
fun LiveScreen(live: LiveState, onBack: () -> Unit, onStop: () -> Unit) {
    BackHandler(onBack = onBack)
    var confirmStop by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (live.phase == Phase.Finishing) "Finishing up" else "Recording") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to lectures") }
                },
            )
        },
    ) { padding ->
        Centered(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                Header(live)
                Box(Modifier.weight(1f)) { Transcript(live) }
                Surface(tonalElevation = 3.dp) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        if (live.phase == Phase.Finishing) {
                            Text(
                                "Recording stopped. Transcribing the last ${live.backlog} part(s)…",
                                Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        } else {
                            Button(
                                onClick = { confirmStop = true },
                                modifier = Modifier.fillMaxWidth().height(60.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                Icon(AppIcons.Stop, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Stop recording", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop recording?") },
            text = { Text("Everything so far is already saved.") },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; onStop() }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("Keep recording") }
            },
        )
    }
}

@Composable
private fun Header(live: LiveState) {
    val level by LiveSession.level.collectAsState()
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SessionStore.clock(live.elapsedMs), fontSize = 44.sp, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { level },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(6.dp))
                Text(status(live), style = MaterialTheme.typography.bodyMedium)
            }
        }
        live.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        live.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}

private fun status(live: LiveState): String = when (live.phase) {
    Phase.LoadingModel -> "Loading speech model… (audio is already being captured)"
    Phase.Finishing -> "Finishing…"
    else -> buildString {
        append(if (live.speaking) "Hearing speech" else "Listening")
        if (live.backlog > 1) append(" · transcription catching up (${live.backlog} parts queued)")
    }
}

@Composable
private fun Transcript(live: LiveState) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    // Follow new text unless the user has scrolled up to re-read something.
    LaunchedEffect(live.lines.size) {
        if (live.lines.isNotEmpty() && atBottom) listState.animateScrollToItem(live.lines.size - 1)
    }
    if (live.lines.isEmpty()) {
        Text(
            "Text appears here a few seconds after each sentence.",
            Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(live.lines) { line ->
            Column {
                Text(
                    SessionStore.clock(line.startMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(line.text, fontSize = 19.sp, lineHeight = 28.sp)
            }
        }
    }
    if (!atBottom && live.lines.isNotEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
            var jump by remember { mutableStateOf(0) }
            LaunchedEffect(jump) { if (jump > 0) listState.animateScrollToItem(live.lines.size - 1) }
            SmallFloatingActionButton(onClick = { jump++ }) { Icon(AppIcons.Down, "Jump to latest", Modifier.size(20.dp)) }
        }
    }
}

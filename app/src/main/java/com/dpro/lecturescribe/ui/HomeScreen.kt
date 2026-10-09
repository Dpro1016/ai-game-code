package com.dpro.lecturescribe.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dpro.lecturescribe.LiveState
import com.dpro.lecturescribe.Phase
import com.dpro.lecturescribe.SessionInfo
import com.dpro.lecturescribe.SessionStore
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    live: LiveState,
    onStart: (saveAudio: Boolean) -> Unit,
    onOpenLive: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var saveAudio by remember { mutableStateOf(prefs.getBoolean("save_audio", true)) }
    var refresh by remember { mutableIntStateOf(0) }
    var batteryOk by remember { mutableStateOf(isBatteryExempt(context)) }
    LifecycleResumeEffect(Unit) {
        refresh++
        batteryOk = isBatteryExempt(context)
        onPauseOrDispose { }
    }
    val sessions = remember(refresh, live.phase) { SessionStore.list(context) }

    Scaffold(topBar = { TopAppBar(title = { Text("Lecture Scribe") }) }) { padding ->
        Centered(Modifier.padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    if (live.phase != Phase.Idle) {
                        RecordingBanner(live, onOpenLive)
                    } else {
                        StartCard(
                            saveAudio = saveAudio,
                            onSaveAudio = {
                                saveAudio = it
                                prefs.edit().putBoolean("save_audio", it).apply()
                            },
                            onStart = { onStart(saveAudio) },
                        )
                    }
                }
                if (!batteryOk) item { BatteryCard(context) }
                live.error?.let { error ->
                    item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
                item {
                    Text(
                        "Past lectures",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (sessions.isEmpty()) {
                    item {
                        Text(
                            "Nothing yet. Your transcripts will show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(sessions, key = { it.id }) { s ->
                    SessionRow(s, isLive = s.id == live.sessionId) {
                        if (s.id == live.sessionId) onOpenLive() else onOpenSession(s.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun StartCard(saveAudio: Boolean, onSaveAudio: (Boolean) -> Unit, onStart: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(24.dp).fillMaxWidth()) {
            Text("Ready when class starts", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap start, then lock the screen or use other apps. Recording continues for as long as " +
                    "the lecture runs. Sitting closer to the front gives a better transcript.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Icon(AppIcons.Mic, null, Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Start recording", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = saveAudio, onCheckedChange = onSaveAudio)
                Text(
                    "Also keep the audio so I can replay parts (about 14 MB per hour)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RecordingBanner(live: LiveState, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Mic, null, Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (live.phase == Phase.Finishing) "Finishing transcription…" else "Recording in progress",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(SessionStore.clock(live.elapsedMs), style = MaterialTheme.typography.bodyLarge)
            }
            Text("Open", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun BatteryCard(context: Context) {
    Card {
        Column(Modifier.padding(20.dp)) {
            Text("Allow background recording", style = MaterialTheme.typography.titleMedium)
            Text(
                "Samsung can pause apps to save battery. Allowing this keeps 3+ hour recordings " +
                    "running with the screen off.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { requestBatteryExemption(context) }) { Text("Allow") }
        }
    }
}

@Composable
private fun SessionRow(s: SessionInfo, isLive: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                isLive -> " · recording now"
                s.status == SessionStore.INTERRUPTED -> " · interrupted"
                else -> ""
            }
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.startedAt)) +
                    " · " + SessionStore.clock(s.durationMs) + status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.preview.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(s.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun isBatteryExempt(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife") // sideloaded personal app; long recordings need it
private fun requestBatteryExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

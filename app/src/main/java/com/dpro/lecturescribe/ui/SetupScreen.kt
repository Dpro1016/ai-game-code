package com.dpro.lecturescribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dpro.lecturescribe.ModelSpec
import com.dpro.lecturescribe.ModelState

@Composable
fun SetupScreen(state: ModelState, onDownload: () -> Unit) {
    val busy = state is ModelState.Downloading || state is ModelState.Unpacking
    if (busy) {
        val view = LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }
    Surface(Modifier.systemBarsPadding()) {
        Centered {
            Column(
                Modifier.padding(32.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(48.dp))
                Icon(AppIcons.Mic, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Lecture Scribe", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Transcribes your lectures on the tablet itself, with no internet needed in class " +
                        "and nothing uploaded anywhere.\n\nFirst it needs a one-time download of its speech " +
                        "model (about ${ModelSpec.DOWNLOAD_MB} MB). Use Wi-Fi and keep the app open until it finishes.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                when (state) {
                    is ModelState.Downloading -> Progress(
                        "Downloading… ${state.done / 1_000_000} MB" +
                            if (state.total > 0) " of ${state.total / 1_000_000} MB" else "",
                        if (state.total > 0) state.done.toFloat() / state.total else null,
                    )
                    is ModelState.Unpacking -> Progress(
                        "Unpacking… this takes a few minutes",
                        state.done.toFloat() / state.total.coerceAtLeast(1),
                    )
                    is ModelState.Failed -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Button(onClick = onDownload) { Text("Retry (it resumes where it left off)") }
                    }
                    else -> Button(onClick = onDownload, Modifier.height(56.dp)) {
                        Text("Download speech model", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(label: String, fraction: Float?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp))
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

package com.dpro.lecturescribe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpro.lecturescribe.ui.AppTheme
import com.dpro.lecturescribe.ui.HomeScreen
import com.dpro.lecturescribe.ui.LiveScreen
import com.dpro.lecturescribe.ui.SessionScreen
import com.dpro.lecturescribe.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ModelManager.refresh(this)
        SessionStore.repairInterrupted(this, activeId = LiveSession.state.value.sessionId)
        setContent { AppTheme { App() } }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val model by ModelManager.state.collectAsStateWithLifecycle()
    val live by LiveSession.state.collectAsStateWithLifecycle()
    var openSession by rememberSaveable { mutableStateOf<String?>(null) }
    var liveHidden by rememberSaveable { mutableStateOf(false) }
    var pendingSaveAudio by remember { mutableStateOf(true) }

    // When a recording finishes, jump straight to its transcript.
    var lastLiveId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(live.phase, live.sessionId) {
        if (live.phase != Phase.Idle) {
            lastLiveId = live.sessionId
        } else if (lastLiveId != null) {
            openSession = lastLiveId
            lastLiveId = null
            liveHidden = false
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            liveHidden = false
            TranscriptionService.start(context, pendingSaveAudio)
        } else {
            Toast.makeText(context, "Lecture Scribe needs the microphone to transcribe.", Toast.LENGTH_LONG).show()
        }
    }

    val start = { saveAudio: Boolean ->
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            liveHidden = false
            TranscriptionService.start(context, saveAudio)
        } else {
            pendingSaveAudio = saveAudio
            permissions.launch(needed.toTypedArray())
        }
    }

    when {
        model !is ModelState.Ready -> SetupScreen(model, onDownload = { ModelManager.download(context) })
        live.phase != Phase.Idle && !liveHidden -> LiveScreen(
            live = live,
            onBack = { liveHidden = true },
            onStop = { TranscriptionService.stop(context) },
        )
        openSession != null -> SessionScreen(openSession!!, onBack = { openSession = null })
        else -> HomeScreen(
            live = live,
            onStart = start,
            onOpenLive = { liveHidden = false },
            onOpenSession = { openSession = it },
        )
    }
}

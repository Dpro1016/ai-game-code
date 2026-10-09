package com.dpro.lecturescribe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase { Idle, LoadingModel, Recording, Finishing }

data class LiveState(
    val phase: Phase = Phase.Idle,
    val sessionId: String? = null,
    val lines: List<Line> = emptyList(),
    val elapsedMs: Long = 0,
    val speaking: Boolean = false,
    val backlog: Int = 0,
    val warning: String? = null,
    val error: String? = null,
)

/** State shared between [TranscriptionService] and the UI (same process). */
object LiveSession {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    /** Mic level 0..1, kept separate so the meter doesn't redraw the transcript. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    val isActive get() = _state.value.phase != Phase.Idle

    fun begin(sessionId: String) {
        _state.value = LiveState(phase = Phase.LoadingModel, sessionId = sessionId)
        _level.value = 0f
    }

    fun update(transform: (LiveState) -> LiveState) = _state.update(transform)

    fun addLine(line: Line) = _state.update { it.copy(lines = it.lines + line) }

    fun setLevel(value: Float) {
        _level.value = value
    }

    fun end() {
        _state.update { LiveState(error = it.error) }
        _level.value = 0f
    }
}

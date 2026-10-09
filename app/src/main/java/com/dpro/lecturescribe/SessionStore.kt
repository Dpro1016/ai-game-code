package com.dpro.lecturescribe

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Line(val startMs: Long, val text: String)

data class SessionInfo(
    val id: String,
    val title: String,
    val startedAt: Long,
    val durationMs: Long,
    val status: String,
    val preview: String,
    val hasAudio: Boolean,
)

/**
 * Each lecture lives in files/sessions/<id>/:
 *   transcript.txt  "[hh:mm:ss] text" per line, appended as it is recognized
 *   audio.aac       the recording (ADTS AAC, playable even if the app is killed)
 *   meta.json       title, start time, duration, status
 */
object SessionStore {
    const val RECORDING = "recording"
    const val DONE = "done"
    const val INTERRUPTED = "interrupted"

    private val lineRegex = Regex("""^\[(\d+):(\d\d):(\d\d)] (.*)$""")

    fun root(context: Context) = File(context.filesDir, "sessions").apply { mkdirs() }
    fun dir(context: Context, id: String) = File(root(context), id)
    fun transcriptFile(context: Context, id: String) = File(dir(context, id), "transcript.txt")
    fun audioFile(context: Context, id: String) = File(dir(context, id), "audio.aac")
    private fun metaFile(context: Context, id: String) = File(dir(context, id), "meta.json")

    fun create(context: Context, now: Long = System.currentTimeMillis()): String {
        var id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        while (dir(context, id).exists()) id += "x"
        dir(context, id).mkdirs()
        val title = "Lecture · " + SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(Date(now))
        writeMeta(context, id, JSONObject().put("title", title).put("startedAt", now).put("durationMs", 0L).put("status", RECORDING))
        transcriptFile(context, id).createNewFile()
        return id
    }

    fun appendLine(context: Context, id: String, line: Line) {
        transcriptFile(context, id).appendText(format(line) + "\n")
    }

    fun format(line: Line): String = "[${clock(line.startMs)}] ${line.text}"

    fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    fun readLines(context: Context, id: String): List<Line> {
        val f = transcriptFile(context, id)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { raw ->
            val m = lineRegex.find(raw) ?: return@mapNotNull null
            val (h, mi, s, text) = m.destructured
            Line(((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000, text)
        }
    }

    fun list(context: Context): List<SessionInfo> =
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { info(context, it.name) }
            .sortedByDescending { it.startedAt }

    fun info(context: Context, id: String): SessionInfo? {
        val meta = readMeta(context, id) ?: return null
        val preview = transcriptFile(context, id).takeIf { it.exists() }?.let { f ->
            f.bufferedReader().use { r -> r.readLine() }?.substringAfter("] ")
        }.orEmpty()
        return SessionInfo(
            id = id,
            title = meta.optString("title", id),
            startedAt = meta.optLong("startedAt"),
            durationMs = meta.optLong("durationMs"),
            status = meta.optString("status", DONE),
            preview = preview,
            hasAudio = audioFile(context, id).length() > 0,
        )
    }

    fun update(context: Context, id: String, durationMs: Long? = null, status: String? = null, title: String? = null) {
        val meta = readMeta(context, id) ?: return
        durationMs?.let { meta.put("durationMs", it) }
        status?.let { meta.put("status", it) }
        title?.let { meta.put("title", it) }
        writeMeta(context, id, meta)
    }

    fun delete(context: Context, id: String) {
        dir(context, id).deleteRecursively()
    }

    /** Marks sessions left "recording" by a crash or force-stop as interrupted. */
    fun repairInterrupted(context: Context, activeId: String?) {
        for (s in list(context)) {
            if (s.status == RECORDING && s.id != activeId) {
                val lastLine = readLines(context, s.id).lastOrNull()?.startMs ?: 0L
                update(context, s.id, durationMs = maxOf(s.durationMs, lastLine), status = INTERRUPTED)
            }
        }
    }

    /** A shareable plain-text copy with a short header. */
    fun exportText(context: Context, id: String, timestamps: Boolean): String {
        val info = info(context, id)
        val lines = readLines(context, id)
        return buildString {
            if (info != null) {
                appendLine(info.title)
                appendLine("Duration: ${clock(info.durationMs)}")
                appendLine()
            }
            if (timestamps) {
                lines.forEach { appendLine(format(it)) }
            } else {
                lines.forEach { appendLine(it.text) }
            }
        }
    }

    private fun readMeta(context: Context, id: String): JSONObject? =
        runCatching { JSONObject(metaFile(context, id).readText()) }.getOrNull()

    private fun writeMeta(context: Context, id: String, meta: JSONObject) {
        val f = metaFile(context, id)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(meta.toString())
        tmp.renameTo(f)
    }
}

package com.dpro.lecturescribe

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The speech model: NVIDIA Parakeet TDT 0.6B v2 (English, int8), packaged by
 * sherpa-onnx. Produces punctuated, capitalized text and runs fully offline.
 */
object ModelSpec {
    const val NAME = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$NAME.tar.bz2"
    const val DOWNLOAD_MB = 460
    val FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
}

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val done: Long, val total: Long) : ModelState
    data class Unpacking(val done: Long, val total: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/** Downloads the model once (resumable), unpacks it, and reports progress. */
object ModelManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state

    fun modelDir(context: Context) = File(context.filesDir, "models/${ModelSpec.NAME}")

    fun isReady(context: Context) = File(modelDir(context), ".complete").exists()

    fun refresh(context: Context) {
        if (job?.isActive == true) return
        _state.value = if (isReady(context)) ModelState.Ready else ModelState.Missing
    }

    fun download(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = try {
                install(app)
                ModelState.Ready
            } catch (e: Exception) {
                ModelState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun install(context: Context) {
        val root = File(context.filesDir, "models").apply { mkdirs() }
        val archive = File(root, "${ModelSpec.NAME}.tar.bz2")
        val part = File(root, "${ModelSpec.NAME}.tar.bz2.part")
        val neededGb = if (archive.exists()) 0.8 else 1.3
        if (root.usableSpace < neededGb * 1e9) {
            throw IOException("Not enough free storage: about $neededGb GB is needed.")
        }
        if (!archive.exists()) {
            fetchWithRetries(ModelSpec.URL, part)
            if (!part.renameTo(archive)) throw IOException("Could not save the download")
        }
        try {
            unpack(archive, root)
        } catch (e: Exception) {
            archive.delete() // probably corrupt: download it again next time
            throw IOException("Unpacking failed (${e.message}). Tap retry to download again.", e)
        }
        archive.delete()
    }

    private fun unpack(archive: File, root: File) {
        val tmp = File(root, "${ModelSpec.NAME}.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val total = archive.length()
        var lastReport = 0L
        // Counts archive bytes for the progress bar. The buffer sits on top so the
        // bzip2 decoder's single-byte reads never bypass the count.
        val counting = object : FilterInputStream(FileInputStream(archive)) {
            var count = 0L
            override fun read(): Int {
                val b = super.read()
                if (b >= 0) count++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    count += n
                    if (count - lastReport > 2_000_000) {
                        lastReport = count
                        _state.value = ModelState.Unpacking(count, total)
                    }
                }
                return n
            }
        }
        _state.value = ModelState.Unpacking(0, total)
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 1 shl 16), true)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in ModelSpec.FILES) continue
                FileOutputStream(File(tmp, name)).use { tar.copyTo(it, 1 shl 16) }
            }
        }
        for (f in ModelSpec.FILES) {
            if (File(tmp, f).length() == 0L) throw IOException("$f missing from archive")
        }
        File(tmp, ".complete").createNewFile()
        val dest = File(root, ModelSpec.NAME)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IOException("Could not move model into place")
    }

    private fun fetchWithRetries(url: String, out: File) {
        var attempt = 0
        while (true) {
            try {
                fetch(url, out)
                return
            } catch (e: IOException) {
                if (++attempt >= 6) throw IOException("Download failed: ${e.message}", e)
                Thread.sleep(3000L * attempt)
            }
        }
    }

    /** Downloads [url] into [out], resuming from whatever is already there. */
    private fun fetch(url: String, out: File) {
        val have = if (out.exists()) out.length() else 0L
        val conn = open(url, have)
        try {
            val code = conn.responseCode
            if (code == 416 && have > 0) return // already complete
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val append = code == 206
            var done = if (append) have else 0L
            val length = conn.contentLengthLong
            val total = if (length > 0) done + length else -1L
            var lastReport = 0L
            conn.inputStream.use { input ->
                FileOutputStream(out, append).use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 1_000_000) {
                            lastReport = done
                            _state.value = ModelState.Downloading(done, total)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("connection dropped")
        } finally {
            conn.disconnect()
        }
    }

    /** Follows redirects by hand so the Range header survives the hop to the CDN. */
    private fun open(url: String, from: Long): HttpURLConnection {
        var target = URL(url)
        repeat(8) {
            val conn = target.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
            val code = conn.responseCode
            if (code !in 300..399) return conn
            val location = conn.getHeaderField("Location") ?: throw IOException("Bad redirect")
            conn.disconnect()
            target = URL(target, location)
        }
        throw IOException("Too many redirects")
    }
}

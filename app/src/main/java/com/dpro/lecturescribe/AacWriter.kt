package com.dpro.lecturescribe

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * Encodes 16 kHz mono PCM to AAC-LC and writes it as a raw ADTS stream.
 * ADTS needs no trailer, so the file stays playable even if the app is killed
 * mid-lecture. ~32 kbit/s is about 14 MB per hour.
 */
class AacWriter(file: File, private val sampleRate: Int = 16000, bitRate: Int = 32000) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file, true), 1 shl 16)
    private val codec: MediaCodec = openEncoder(sampleRate, bitRate)
    private val info = MediaCodec.BufferInfo()
    private var samplesQueued = 0L
    private var lastFlush = System.nanoTime()

    fun write(pcm: ShortArray, count: Int) {
        var off = 0
        var stalls = 0
        while (off < count) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(false)
                if (++stalls > 200) throw IllegalStateException("AAC encoder stalled")
                continue
            }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            buf.order(ByteOrder.nativeOrder())
            val n = minOf(count - off, buf.remaining() / 2)
            buf.asShortBuffer().put(pcm, off, n)
            codec.queueInputBuffer(idx, 0, n * 2, samplesQueued * 1_000_000 / sampleRate, 0)
            samplesQueued += n
            off += n
            drain(false)
        }
        if (System.nanoTime() - lastFlush > 5_000_000_000L) {
            out.flush()
            lastFlush = System.nanoTime()
        }
    }

    override fun close() {
        try {
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, samplesQueued * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            out.close()
        }
    }

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++waits > 100) return
                continue
            }
            if (idx < 0) continue // format / buffers changed
            val buf = codec.getOutputBuffer(idx)!!
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0) {
                val frame = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(frame)
                out.write(adtsHeader(info.size + 7))
                out.write(frame)
            }
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(idx, false)
            if (eos) return
        }
    }

    private fun adtsHeader(length: Int): ByteArray {
        val profile = 2 // AAC LC
        val freqIndex = FREQUENCIES.indexOf(sampleRate)
        val channels = 1
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((profile - 1) shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    companion object {
        private val FREQUENCIES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)

        /** Tries every AAC encoder on the device until one accepts 16 kHz mono. */
        private fun openEncoder(sampleRate: Int, bitRate: Int): MediaCodec {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val names = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) } }
                .map { it.name }
            var last: Exception? = null
            for (name in names) {
                val codec = MediaCodec.createByCodecName(name)
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    return codec
                } catch (e: Exception) {
                    last = e
                    codec.release()
                }
            }
            throw IllegalStateException("No usable AAC encoder", last)
        }
    }
}

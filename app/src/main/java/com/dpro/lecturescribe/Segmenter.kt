package com.dpro.lecturescribe

/**
 * Splits the microphone stream into speech segments for the recognizer.
 *
 * Fed one [WINDOW]-sample window at a time together with Silero's speech
 * probability for it. A segment normally ends at a pause; once it is long the
 * pause needed shrinks, and at [hardMax] it is force-split at the quietest
 * point of the last few seconds. That keeps the live transcript at most ~25 s
 * behind even when a lecturer talks without pausing.
 */
class Segmenter(
    private val threshold: Float = 0.5f,
    minSilenceSec: Float = 0.5f,
    softMaxSec: Float = 15f,
    softMinSilenceSec: Float = 0.15f,
    hardMaxSec: Float = 25f,
    splitSearchSec: Float = 5f,
    minSpeechSec: Float = 0.25f,
    preRollSec: Float = 0.3f,
    postRollSec: Float = 0.2f,
) {
    class Segment(val startSample: Long, val samples: FloatArray)

    private fun windows(sec: Float) = maxOf(1, Math.round(sec * SAMPLE_RATE / WINDOW))

    private val silenceThreshold = threshold - 0.15f
    private val minSilence = windows(minSilenceSec)
    private val softMax = windows(softMaxSec)
    private val softMinSilence = windows(softMinSilenceSec)
    private val hardMax = windows(hardMaxSec)
    private val splitSearch = windows(splitSearchSec)
    private val minSpeech = windows(minSpeechSec)
    private val preRoll = windows(preRollSec)
    private val postRoll = windows(postRollSec)

    private var index = 0L
    private var start = 0L
    private var silence = 0
    private val pre = ArrayDeque<FloatArray>()
    private val seg = ArrayList<FloatArray>()
    private val probs = ArrayList<Float>()

    var inSpeech = false
        private set

    /** [window] must not be reused by the caller afterwards. */
    fun accept(window: FloatArray, p: Float): List<Segment> {
        val out = ArrayList<Segment>(1)
        val i = index++
        if (!inSpeech) {
            if (p >= threshold) {
                inSpeech = true
                seg.clear(); probs.clear()
                seg.addAll(pre); repeat(pre.size) { probs.add(0f) }
                seg.add(window); probs.add(p)
                start = i - pre.size
                pre.clear()
                silence = 0
            } else {
                pre.addLast(window)
                if (pre.size > preRoll) pre.removeFirst()
            }
            return out
        }
        seg.add(window); probs.add(p)
        silence = if (p < silenceThreshold) silence + 1 else 0
        val needed = if (seg.size >= softMax) softMinSilence else minSilence
        if (silence >= needed) {
            val cut = seg.size - silence + minOf(silence, postRoll)
            emit(cut, out)
            pre.clear()
            seg.takeLast(preRoll).forEach { pre.addLast(it) }
            seg.clear(); probs.clear()
            inSpeech = false
        } else if (seg.size >= hardMax) {
            val lo = seg.size - splitSearch
            var j = lo
            for (k in lo until seg.size) if (probs[k] < probs[j]) j = k
            emit(j + 1, out)
            silence = 0
        }
        return out
    }

    /** Emits whatever speech is still buffered (call when recording stops). */
    fun flush(): List<Segment> {
        val out = ArrayList<Segment>(1)
        if (inSpeech && seg.isNotEmpty()) emit(seg.size, out)
        inSpeech = false
        seg.clear(); probs.clear(); pre.clear()
        return out
    }

    private fun emit(n: Int, out: MutableList<Segment>) {
        var voiced = 0
        for (k in 0 until n) if (probs[k] >= threshold) voiced++
        if (voiced >= minSpeech) {
            val samples = FloatArray(n * WINDOW)
            for (k in 0 until n) System.arraycopy(seg[k], 0, samples, k * WINDOW, WINDOW)
            out.add(Segment(start * WINDOW, samples))
        }
        seg.subList(0, n).clear()
        probs.subList(0, n).clear()
        start += n
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW = 512
    }
}

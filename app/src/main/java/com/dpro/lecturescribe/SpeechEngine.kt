package com.dpro.lecturescribe

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable

/** Parakeet speech recognizer: turns one speech segment into punctuated text. */
class SpeechEngine(context: Context) : Closeable {
    private val recognizer: OfflineRecognizer

    init {
        val dir = ModelManager.modelDir(context).path
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.int8.onnx",
                    joiner = "$dir/joiner.int8.onnx",
                ),
                tokens = "$dir/tokens.txt",
                modelType = "nemo_transducer",
                numThreads = 4,
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(config = config)
    }

    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, Segmenter.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

/** Silero voice-activity model; only its per-window speech probability is used. */
class SpeechDetector(context: Context) : Closeable {
    private val vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                windowSize = Segmenter.WINDOW,
            ),
            sampleRate = Segmenter.SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    /** Speech probability (0..1) for exactly [Segmenter.WINDOW] samples. */
    fun probability(window: FloatArray): Float = vad.compute(window)

    override fun close() = vad.release()
}

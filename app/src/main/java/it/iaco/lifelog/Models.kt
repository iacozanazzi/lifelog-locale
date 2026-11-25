package it.iaco.lifelog

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** Dove stanno i modelli sul telefono e come configurarli. */
object Models {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    const val PARAKEET_NAME = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    const val PARAKEET_URL = "$BASE/$PARAKEET_NAME.tar.bz2"
    const val VAD_URL = "$BASE/silero_vad.onnx"
    const val PARAKEET_MB = 487

    const val WHISPER_NAME = "sherpa-onnx-whisper-turbo"
    const val WHISPER_URL = "$BASE/$WHISPER_NAME.tar.bz2"
    const val WHISPER_MB = 564

    const val SAMPLE_RATE = 16000

    fun whisperDir(ctx: Context) = File(dir(ctx), WHISPER_NAME)
    fun whisperReady(ctx: Context) = File(whisperDir(ctx), ".complete").exists()
    fun markWhisperComplete(ctx: Context) = File(whisperDir(ctx), ".complete").writeText("ok")

    /** Whisper large-v3-turbo con lingua forzata all'italiano: più preciso, più lento. */
    fun whisperConfig(ctx: Context, threads: Int): OfflineRecognizerConfig {
        val d = whisperDir(ctx)
        val files = d.listFiles().orEmpty()
        fun find(suffix: String) = (files.firstOrNull { it.name.endsWith("$suffix.int8.onnx") }
            ?: files.firstOrNull { it.name.endsWith("$suffix.onnx") } ?: error("File Whisper mancante: $suffix")).absolutePath
        val tokens = files.firstOrNull { it.name.endsWith("tokens.txt") } ?: error("tokens Whisper mancante")
        return OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                    encoder = find("encoder"), decoder = find("decoder"),
                    language = "it", task = "transcribe",
                ),
                tokens = tokens.absolutePath,
                modelType = "whisper",
                numThreads = threads,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun vadFile(ctx: Context) = File(dir(ctx), "silero_vad.onnx")
    fun parakeetDir(ctx: Context) = File(dir(ctx), PARAKEET_NAME)
    private fun okMarker(ctx: Context) = File(parakeetDir(ctx), ".complete")

    fun vadReady(ctx: Context) = vadFile(ctx).length() > 100_000
    fun parakeetReady(ctx: Context) = okMarker(ctx).exists()
    fun allReady(ctx: Context) = vadReady(ctx) && parakeetReady(ctx)

    fun markParakeetComplete(ctx: Context) {
        okMarker(ctx).writeText("ok")
    }

    fun vadConfig(ctx: Context) = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = vadFile(ctx).absolutePath,
            threshold = 0.5f,
            minSilenceDuration = 0.6f,
            minSpeechDuration = 0.3f,
            windowSize = 512,
            maxSpeechDuration = 25f,
        ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
    )

    private fun pick(dir: File, prefix: String): String {
        val files = dir.listFiles().orEmpty()
        val f = files.firstOrNull { it.name == "$prefix.int8.onnx" }
            ?: files.firstOrNull { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            ?: error("File del modello mancante: $prefix")
        return f.absolutePath
    }

    /** Parakeet TDT 0.6B v3 (multilingue, italiano incluso). */
    fun recognizerConfig(ctx: Context, threads: Int): OfflineRecognizerConfig {
        val d = parakeetDir(ctx)
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = pick(d, "encoder"),
                    decoder = pick(d, "decoder"),
                    joiner = pick(d, "joiner"),
                ),
                tokens = File(d, "tokens.txt").absolutePath,
                modelType = "nemo_transducer",
                numThreads = threads,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
    }
}

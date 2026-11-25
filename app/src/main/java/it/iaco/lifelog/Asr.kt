package it.iaco.lifelog

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import kotlin.coroutines.resume

/** I motori di trascrizione tra cui si può scegliere. */
enum class Engine(val label: String, val detail: String) {
    PARAKEET("Parakeet", "Veloce. Riconosce la lingua da solo."),
    WHISPER("Whisper turbo", "Più preciso in italiano, circa 3 volte più lento. Scarica 564 MB."),
    GOOGLE("Google sul telefono", "Il riconoscimento vocale di Google, offline. Serve Android 13 e il pacchetto italiano."),
}

/** Un motore pronto a trascrivere pezzi di audio a 16 kHz. */
interface Asr {
    suspend fun transcribe(samples: FloatArray): String
    fun release()
}

/**
 * Whisper a volte "si incanta" e ripete la stessa frase: tengo una sola copia
 * di qualunque gruppo di 1-8 parole ripetuto 3 o più volte di fila.
 */
object Loops {
    fun clean(text: String): String {
        var w = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (n in 8 downTo 1) {
            val out = ArrayList<String>()
            var i = 0
            while (i < w.size) {
                if (i + n <= w.size) {
                    val unit = w.subList(i, i + n).map { it.lowercase().trim(',', '.', '!', '?') }
                    var reps = 1
                    while (i + (reps + 1) * n <= w.size &&
                        w.subList(i + reps * n, i + (reps + 1) * n).map { it.lowercase().trim(',', '.', '!', '?') } == unit) reps++
                    if (reps >= 3) { out += w.subList(i, i + n); i += reps * n; continue }
                }
                out += w[i]; i++
            }
            w = out
        }
        return w.joinToString(" ")
    }
}

class SherpaAsr(private val rec: OfflineRecognizer, private val fixLoops: Boolean = false) : Asr {
    override suspend fun transcribe(samples: FloatArray): String {
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, Models.SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text.trim().let { if (fixLoops) Loops.clean(it) else it }
        } finally {
            stream.release()
        }
    }
    override fun release() = rec.release()
}

/**
 * Riconoscimento vocale di Google sul telefono (Android 13+), a cui passiamo
 * il nostro audio già registrato: niente microfono, niente internet.
 */
class GoogleAsr(private val ctx: Context) : Asr {
    private val main = Handler(Looper.getMainLooper())

    override suspend fun transcribe(samples: FloatArray): String {
        if (Build.VERSION.SDK_INT < 33) throw IllegalStateException("Serve Android 13 o più recente")
        return withTimeoutOrNull(90_000) { recognize(samples) } ?: throw IllegalStateException("Google non ha risposto in tempo")
    }

    private suspend fun recognize(samples: FloatArray): String = suspendCancellableCoroutine { cont ->
        val pipe = ParcelFileDescriptor.createPipe()
        val parts = mutableListOf<String>()
        var recognizer: SpeechRecognizer? = null
        fun finish(result: Result<String>) {
            main.post {
                recognizer?.destroy(); recognizer = null
                try { pipe[0].close() } catch (_: Exception) {}
            }
            if (cont.isActive) cont.resumeWith(result)
        }
        main.post {
            try {
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                    finish(Result.failure(IllegalStateException("Riconoscimento Google sul telefono non disponibile")))
                    return@post
                }
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                recognizer = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onSegmentResults(segmentResults: Bundle) {
                        segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { parts += it }
                    }
                    override fun onEndOfSegmentedSession() = finish(Result.success(parts.joinToString(" ").trim()))
                    override fun onResults(results: Bundle) {
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { parts += it }
                        finish(Result.success(parts.joinToString(" ").trim()))
                    }
                    override fun onError(error: Int) {
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                finish(Result.success(parts.joinToString(" ").trim()))
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                                try { r.triggerModelDownload(intent()) } catch (_: Exception) {}
                                finish(Result.failure(IllegalStateException("Manca il pacchetto italiano di Google: lo sto scaricando, riprova più tardi")))
                            }
                            else -> finish(Result.failure(IllegalStateException("Errore Google $error")))
                        }
                    }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                })
                r.startListening(intent().apply {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Models.SAMPLE_RATE)
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                })
                // Scrive l'audio nel tubo da un altro thread, poi lo chiude: per Google è "fine audio".
                Thread {
                    try {
                        FileOutputStream(pipe[1].fileDescriptor).use { it.write(Pcm.toBytes(samples)) }
                    } catch (_: Exception) {
                    } finally {
                        try { pipe[1].close() } catch (_: Exception) {}
                    }
                }.start()
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
        cont.invokeOnCancellation { finish(Result.failure(it ?: IllegalStateException("annullato"))) }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    override fun release() {}
}

object AsrFactory {
    fun ready(ctx: Context, e: Engine) = when (e) {
        Engine.PARAKEET -> Models.parakeetReady(ctx)
        Engine.WHISPER -> Models.whisperReady(ctx)
        Engine.GOOGLE -> Build.VERSION.SDK_INT >= 33
    }

    fun create(ctx: Context, e: Engine, threads: Int): Asr = when (e) {
        Engine.PARAKEET -> SherpaAsr(OfflineRecognizer(null, Models.recognizerConfig(ctx, threads)))
        Engine.WHISPER -> SherpaAsr(OfflineRecognizer(null, Models.whisperConfig(ctx, threads)), fixLoops = true)
        Engine.GOOGLE -> GoogleAsr(ctx.applicationContext)
    }
}

/** Utilità per l'audio salvato: PCM 16 bit little-endian, 16 kHz mono. */
object Pcm {
    fun toBytes(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[2 * i] = (v and 0xff).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return bytes
    }

    fun read(f: java.io.File): FloatArray {
        val bytes = f.readBytes()
        val out = FloatArray(bytes.size / 2)
        for (i in out.indices) {
            val lo = bytes[2 * i].toInt() and 0xff
            val hi = bytes[2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }

    /** Porta il picco a circa -1 dB, senza amplificare più di 20 volte (per non gonfiare il rumore). */
    fun normalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
        if (peak < 1e-4f) return x
        val g = minOf(0.9f / peak, 20f)
        return FloatArray(x.size) { (x[it] * g).coerceIn(-1f, 1f) }
    }
}

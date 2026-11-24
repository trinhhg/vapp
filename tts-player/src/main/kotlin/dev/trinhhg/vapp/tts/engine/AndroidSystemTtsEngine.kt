package dev.trinhhg.vapp.tts.engine

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.trinhhg.vapp.tts.api.TtsEngine
import dev.trinhhg.vapp.tts.api.TtsUtteranceResult
import dev.trinhhg.vapp.tts.api.TtsVoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Offline-capable Android System [TextToSpeech] implementation of [TtsEngine].
 */
class AndroidSystemTtsEngine(
    private val appContext: Context,
) : TtsEngine {
    private val lock = Any()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var initDeferred: CompletableDeferred<Boolean>? = null

    private val waiters = ConcurrentHashMap<String, (TtsUtteranceResult) -> Unit>()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun ensureInitialized(): TextToSpeech? {
        val deferred: CompletableDeferred<Boolean>
        synchronized(lock) {
            tts?.let { existing ->
                val existingInit = initDeferred
                if (existingInit != null && existingInit.isCompleted && existingInit.getCompleted()) {
                    return existing
                }
            }
            val currentDeferred = initDeferred
            if (currentDeferred != null && !currentDeferred.isCompleted) {
                deferred = currentDeferred
            } else {
                deferred = CompletableDeferred()
                initDeferred = deferred
                val engine =
                    TextToSpeech(appContext.applicationContext) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            tts?.let { instance ->
                                val viLocale = Locale("vi", "VN")
                                if (instance.isLanguageAvailable(viLocale) >= TextToSpeech.LANG_AVAILABLE) {
                                    instance.language = viLocale
                                }
                                instance.setOnUtteranceProgressListener(
                                    object : UtteranceProgressListener() {
                                        override fun onStart(utteranceId: String?) = Unit

                                        override fun onDone(utteranceId: String?) {
                                            if (utteranceId != null) {
                                                waiters.remove(utteranceId)?.invoke(TtsUtteranceResult(completed = true))
                                            }
                                        }

                                        @Deprecated("Deprecated in Android API")
                                        override fun onError(utteranceId: String?) {
                                            if (utteranceId != null) {
                                                waiters.remove(utteranceId)?.invoke(
                                                    TtsUtteranceResult(completed = false, errorMessage = "TTS error"),
                                                )
                                            }
                                        }

                                        override fun onError(
                                            utteranceId: String?,
                                            errorCode: Int,
                                        ) {
                                            if (utteranceId != null) {
                                                waiters.remove(utteranceId)?.invoke(
                                                    TtsUtteranceResult(
                                                        completed = false,
                                                        errorMessage = "TTS error code $errorCode",
                                                    ),
                                                )
                                            }
                                        }

                                        override fun onStop(
                                            utteranceId: String?,
                                            interrupted: Boolean,
                                        ) {
                                            if (utteranceId != null) {
                                                waiters.remove(utteranceId)?.invoke(
                                                    TtsUtteranceResult(completed = false, errorMessage = null),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                            deferred.complete(true)
                        } else {
                            deferred.complete(false)
                        }
                    }
                tts = engine
            }
        }
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { deferred.await() } ?: false
        return if (ok) tts else null
    }

    override suspend fun listVoices(): List<TtsVoiceInfo> {
        val engine = ensureInitialized() ?: return emptyList()
        val voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
        return voices
            .map { v ->
                TtsVoiceInfo(
                    id = v.name,
                    name = "${v.locale.displayName} (${v.name})",
                    localeTag = v.locale.toLanguageTag(),
                    isNetworkConnectionRequired = v.isNetworkConnectionRequired,
                )
            }.sortedWith(
                compareByDescending<TtsVoiceInfo> { it.localeTag.startsWith("vi", ignoreCase = true) }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenBy { it.name },
            )
    }

    override suspend fun speakParagraph(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): TtsUtteranceResult {
        val clean = text.trim()
        if (clean.isEmpty()) return TtsUtteranceResult(completed = true)

        val engine =
            ensureInitialized()
                ?: return TtsUtteranceResult(completed = false, errorMessage = "Không khởi tạo được Android TextToSpeech")

        if (!voiceId.isNullOrBlank()) {
            runCatching {
                engine.voices?.firstOrNull { it.name == voiceId }?.let { engine.voice = it }
            }
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.5f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        val utteranceId = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            waiters[utteranceId] = { result ->
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation {
                waiters.remove(utteranceId)
                runCatching { engine.stop() }
            }
            val params =
                Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
            val code = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (code != TextToSpeech.SUCCESS) {
                waiters.remove(utteranceId)
                if (cont.isActive) {
                    cont.resume(TtsUtteranceResult(completed = false, errorMessage = "TTS speak failed ($code)"))
                }
            }
        }
    }

    override fun stop() {
        val keys = waiters.keys.toList()
        keys.forEach { id ->
            waiters.remove(id)?.invoke(TtsUtteranceResult(completed = false, errorMessage = null))
        }
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        initDeferred = null
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 8_000L
    }
}

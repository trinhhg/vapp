package dev.trinhhg.vapp.tts.api

import kotlinx.coroutines.flow.StateFlow

data class TtsVoiceInfo(
    val id: String,
    val name: String,
    val localeTag: String,
    val isNetworkConnectionRequired: Boolean = false,
)

data class TtsUtteranceResult(
    val completed: Boolean,
    val errorMessage: String? = null,
)

data class AudiobookState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val currentParagraph: Int = 0,
    val totalParagraphs: Int = 0,
    val isPlaying: Boolean = false,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val sleepTimerRemainingMinutes: Int? = null,
    val ambienceTrackId: String? = null,
    val ambienceVolume: Float = 0.25f,
    val errorMessage: String? = null,
)

interface TtsEngine {
    suspend fun listVoices(): List<TtsVoiceInfo>

    suspend fun speakParagraph(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): TtsUtteranceResult

    fun stop()
}

interface AudiobookController {
    val playbackState: StateFlow<AudiobookState>

    fun playChapter(
        bookId: String,
        chapterIndex: Int,
        startParagraph: Int = 0,
    )

    fun pause()

    fun resume()

    fun seekToParagraph(index: Int)

    fun skipChapter(delta: Int)

    fun setSpeed(rate: Float)

    fun setSleepTimer(minutes: Int?)

    fun setAmbience(
        trackId: String?,
        volume: Float,
    )
}

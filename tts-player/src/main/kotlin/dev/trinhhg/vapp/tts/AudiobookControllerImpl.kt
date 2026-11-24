package dev.trinhhg.vapp.tts

import dev.trinhhg.vapp.tts.api.AudiobookController
import dev.trinhhg.vapp.tts.api.AudiobookState
import dev.trinhhg.vapp.tts.api.TtsEngine
import dev.trinhhg.vapp.tts.api.TtsVoiceInfo
import dev.trinhhg.vapp.tts.audio.AmbienceAudioMixer
import dev.trinhhg.vapp.tts.audio.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudiobookChapterPayload(
    val bookId: String,
    val bookTitle: String,
    val author: String = "",
    val coverPath: String? = null,
    val chapterIndex: Int,
    val chapterTitle: String,
    val totalChapters: Int,
    val paragraphs: List<String>,
)

fun interface AudiobookChapterSource {
    suspend fun loadChapter(
        bookId: String,
        chapterIndex: Int,
    ): AudiobookChapterPayload?
}

/**
 * Coordinates offline TTS audiobook playback across paragraphs and chapters, sleep timer countdown,
 * and optional local ambience mixing.
 */
class AudiobookControllerImpl(
    private val ttsEngine: TtsEngine,
    private val chapterSource: AudiobookChapterSource,
    private val sleepTimer: SleepTimer,
    private val ambienceMixer: AmbienceAudioMixer?,
    private val scope: CoroutineScope,
    private val onStartMediaService: (() -> Unit)? = null,
    private val onProgressPersist: (suspend (bookId: String, chapterIndex: Int, paragraphIndex: Int, totalChapters: Int) -> Unit)? = null,
) : AudiobookController {
    private val _playbackState = MutableStateFlow(AudiobookState())
    override val playbackState: StateFlow<AudiobookState> = _playbackState.asStateFlow()

    private val _currentPayload = MutableStateFlow<AudiobookChapterPayload?>(null)
    val currentPayload: StateFlow<AudiobookChapterPayload?> = _currentPayload.asStateFlow()

    private val _selectedVoiceId = MutableStateFlow<String?>(null)
    val selectedVoiceId: StateFlow<String?> = _selectedVoiceId.asStateFlow()

    private val _stopAfterChapter = MutableStateFlow(false)
    val stopAfterChapter: StateFlow<Boolean> = _stopAfterChapter.asStateFlow()

    private var playJob: Job? = null

    init {
        scope.launch {
            sleepTimer.remainingMinutes.collect { mins ->
                _playbackState.update { it.copy(sleepTimerRemainingMinutes = mins) }
            }
        }
    }

    override fun playChapter(
        bookId: String,
        chapterIndex: Int,
        startParagraph: Int,
    ) {
        val wasActive = playJob?.isActive == true
        playJob?.cancel()
        if (wasActive) {
            ttsEngine.stop()
        }
        playJob =
            scope.launch {
                val payload = chapterSource.loadChapter(bookId, chapterIndex)
                if (payload == null) {
                    _playbackState.update {
                        it.copy(
                            isPlaying = false,
                            errorMessage = "Không tải được chương #${chapterIndex + 1}",
                        )
                    }
                    ambienceMixer?.setPlaying(false)
                    return@launch
                }
                _currentPayload.value = payload
                val safeStart =
                    if (payload.paragraphs.isEmpty()) {
                        0
                    } else {
                        startParagraph.coerceIn(0, payload.paragraphs.lastIndex)
                    }
                _playbackState.update {
                    it.copy(
                        bookId = payload.bookId,
                        bookTitle = payload.bookTitle,
                        chapterIndex = payload.chapterIndex,
                        chapterTitle = payload.chapterTitle,
                        currentParagraph = safeStart,
                        totalParagraphs = payload.paragraphs.size,
                        isPlaying = true,
                        errorMessage = null,
                    )
                }
                onStartMediaService?.invoke()
                ambienceMixer?.setPlaying(true)
                runPlaybackLoop(payload, safeStart)
            }
    }

    private suspend fun runPlaybackLoop(
        initialPayload: AudiobookChapterPayload,
        startParagraph: Int,
    ) {
        var payload = initialPayload
        var paragraphIdx = startParagraph

        while (scope.isActive) {
            if (paragraphIdx >= payload.paragraphs.size) {
                if (_stopAfterChapter.value) {
                    _stopAfterChapter.value = false
                    _playbackState.update { it.copy(isPlaying = false) }
                    ambienceMixer?.setPlaying(false)
                    return
                }
                val nextIndex = payload.chapterIndex + 1
                val nextPayload = chapterSource.loadChapter(payload.bookId, nextIndex)
                if (nextPayload == null) {
                    _playbackState.update { it.copy(isPlaying = false) }
                    ambienceMixer?.setPlaying(false)
                    return
                }
                payload = nextPayload
                _currentPayload.value = payload
                paragraphIdx = 0
                _playbackState.update {
                    it.copy(
                        chapterIndex = payload.chapterIndex,
                        chapterTitle = payload.chapterTitle,
                        currentParagraph = 0,
                        totalParagraphs = payload.paragraphs.size,
                        errorMessage = null,
                    )
                }
                onProgressPersist?.invoke(payload.bookId, payload.chapterIndex, 0, payload.totalChapters)
                continue
            }

            _playbackState.update { it.copy(currentParagraph = paragraphIdx) }
            onProgressPersist?.invoke(payload.bookId, payload.chapterIndex, paragraphIdx, payload.totalChapters)

            val st = _playbackState.value
            val result =
                ttsEngine.speakParagraph(
                    text = payload.paragraphs[paragraphIdx],
                    voiceId = _selectedVoiceId.value,
                    rate = st.speechRate,
                    pitch = st.pitch,
                )

            if (!result.completed) {
                if (!result.errorMessage.isNullOrBlank()) {
                    _playbackState.update { it.copy(isPlaying = false, errorMessage = result.errorMessage) }
                    ambienceMixer?.setPlaying(false)
                }
                return
            }
            paragraphIdx++
        }
    }

    override fun pause() {
        playJob?.cancel()
        playJob = null
        ttsEngine.stop()
        ambienceMixer?.setPlaying(false)
        _playbackState.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
        val st = _playbackState.value
        val bookId = st.bookId ?: return
        playChapter(bookId, st.chapterIndex, st.currentParagraph)
    }

    override fun seekToParagraph(index: Int) {
        val st = _playbackState.value
        val bookId = st.bookId ?: return
        val clamped = index.coerceIn(0, (st.totalParagraphs - 1).coerceAtLeast(0))
        if (st.isPlaying) {
            playChapter(bookId, st.chapterIndex, clamped)
        } else {
            _playbackState.update { it.copy(currentParagraph = clamped) }
        }
    }

    override fun skipChapter(delta: Int) {
        val st = _playbackState.value
        val bookId = st.bookId ?: return
        val total = _currentPayload.value?.totalChapters ?: Int.MAX_VALUE
        val target = (st.chapterIndex + delta).coerceIn(0, (total - 1).coerceAtLeast(0))
        if (st.isPlaying) {
            playChapter(bookId, target, 0)
        } else {
            scope.launch {
                val payload = chapterSource.loadChapter(bookId, target) ?: return@launch
                _currentPayload.value = payload
                _playbackState.update {
                    it.copy(
                        chapterIndex = payload.chapterIndex,
                        chapterTitle = payload.chapterTitle,
                        currentParagraph = 0,
                        totalParagraphs = payload.paragraphs.size,
                    )
                }
            }
        }
    }

    override fun setSpeed(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.5f)
        _playbackState.update { it.copy(speechRate = clamped) }
        val st = _playbackState.value
        if (st.isPlaying && st.bookId != null) {
            playChapter(st.bookId, st.chapterIndex, st.currentParagraph)
        }
    }

    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        _playbackState.update { it.copy(pitch = clamped) }
        val st = _playbackState.value
        if (st.isPlaying && st.bookId != null) {
            playChapter(st.bookId, st.chapterIndex, st.currentParagraph)
        }
    }

    fun setVoice(voiceId: String?) {
        _selectedVoiceId.value = voiceId?.takeIf { it.isNotBlank() }
        val st = _playbackState.value
        if (st.isPlaying && st.bookId != null) {
            playChapter(st.bookId, st.chapterIndex, st.currentParagraph)
        }
    }

    suspend fun listVoices(): List<TtsVoiceInfo> = ttsEngine.listVoices()

    override fun setSleepTimer(minutes: Int?) {
        if (minutes == SLEEP_END_OF_CHAPTER) {
            sleepTimer.cancel()
            _stopAfterChapter.value = true
            return
        }
        _stopAfterChapter.value = false
        sleepTimer.start(minutes) {
            pause()
        }
    }

    override fun setAmbience(
        trackId: String?,
        volume: Float,
    ) {
        val cleanTrack = trackId?.takeIf { it.isNotBlank() && it != AmbienceAudioMixer.TRACK_NONE }
        val clampedVol = volume.coerceIn(0f, 1f)
        _playbackState.update { it.copy(ambienceTrackId = cleanTrack, ambienceVolume = clampedVol) }
        ambienceMixer?.configure(cleanTrack, clampedVol, _playbackState.value.isPlaying)
    }

    fun stop() {
        sleepTimer.cancel()
        _stopAfterChapter.value = false
        pause()
    }

    companion object {
        const val SLEEP_END_OF_CHAPTER = -1
    }
}

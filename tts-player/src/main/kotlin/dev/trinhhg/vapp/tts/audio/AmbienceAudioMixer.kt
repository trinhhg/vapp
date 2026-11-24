package dev.trinhhg.vapp.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Optional offline background ambience synthesizer (`rain`, `cafe`, `white_noise`, `forest`)
 * rendered locally onto an [AudioTrack] without network streams or copyrighted audio assets.
 */
class AmbienceAudioMixer(
    private val scope: CoroutineScope,
) {
    @Volatile
    private var trackId: String? = null

    @Volatile
    private var volume: Float = 0.25f

    @Volatile
    private var active: Boolean = false

    private var renderJob: Job? = null
    private var audioTrack: AudioTrack? = null

    fun configure(
        trackId: String?,
        volume: Float,
        isPlaying: Boolean,
    ) {
        val cleanTrack = trackId?.takeIf { it.isNotBlank() && it != TRACK_NONE }
        this.trackId = cleanTrack
        this.volume = volume.coerceIn(0f, 1f)
        this.active = isPlaying && cleanTrack != null
        updatePlaybackState()
    }

    fun setPlaying(isPlaying: Boolean) {
        this.active = isPlaying && trackId != null
        updatePlaybackState()
    }

    private fun updatePlaybackState() {
        if (!active || trackId == null) {
            stopRenderer()
        } else if (renderJob?.isActive != true) {
            startRenderer()
        }
    }

    private fun startRenderer() {
        stopRenderer()
        val chosen = trackId ?: return
        renderJob =
            scope.launch(Dispatchers.Default) {
                val sampleRate = SAMPLE_RATE
                val minBuf =
                    runCatching {
                        AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                    }.getOrDefault(4096).coerceAtLeast(2048)

                val track =
                    runCatching {
                        AudioTrack
                            .Builder()
                            .setAudioAttributes(
                                AudioAttributes
                                    .Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build(),
                            ).setAudioFormat(
                                AudioFormat
                                    .Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build(),
                            ).setBufferSizeInBytes(minBuf)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                    }.getOrNull() ?: return@launch

                audioTrack = track
                runCatching { track.play() }.onFailure {
                    runCatching { track.release() }
                    audioTrack = null
                    return@launch
                }

                val buffer = ShortArray(minBuf / 2)
                var brownState = 0f
                var phase = 0.0
                try {
                    while (isActive && active) {
                        val amp = (volume.coerceIn(0f, 1f) * 3200f)
                        for (i in buffer.indices) {
                            val white = Random.nextFloat() * 2f - 1f
                            brownState = (brownState + 0.04f * white).coerceIn(-1f, 1f)
                            val sample =
                                when (chosen) {
                                    TRACK_RAIN -> brownState * 0.7f + white * 0.25f
                                    TRACK_FOREST -> {
                                        phase += 0.0005
                                        brownState * 0.65f + (sin(phase) * 0.2f).toFloat()
                                    }
                                    TRACK_CAFE -> brownState * 0.85f + white * 0.1f
                                    else -> white * 0.35f
                                }
                            buffer[i] = (sample * amp).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                        track.write(buffer, 0, buffer.size)
                    }
                } finally {
                    runCatching {
                        track.pause()
                        track.flush()
                        track.release()
                    }
                    if (audioTrack === track) {
                        audioTrack = null
                    }
                }
            }
    }

    private fun stopRenderer() {
        renderJob?.cancel()
        renderJob = null
        audioTrack?.let { tr ->
            runCatching {
                tr.pause()
                tr.flush()
                tr.release()
            }
        }
        audioTrack = null
    }

    fun release() {
        active = false
        stopRenderer()
    }

    companion object {
        const val TRACK_NONE = "none"
        const val TRACK_RAIN = "rain"
        const val TRACK_CAFE = "cafe"
        const val TRACK_FOREST = "forest"
        const val TRACK_WHITE_NOISE = "white_noise"
        private const val SAMPLE_RATE = 16_000
    }
}

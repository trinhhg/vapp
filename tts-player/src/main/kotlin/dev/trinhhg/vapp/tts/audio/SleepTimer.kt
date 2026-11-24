package dev.trinhhg.vapp.tts.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Countdown sleep timer emitting remaining whole minutes and invoking [onExpired] on completion.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val minuteMillis: Long = 60_000L,
) {
    private val _remainingMinutes = MutableStateFlow<Int?>(null)
    val remainingMinutes: StateFlow<Int?> = _remainingMinutes.asStateFlow()

    private var timerJob: Job? = null

    fun start(
        minutes: Int?,
        onExpired: suspend () -> Unit,
    ) {
        timerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _remainingMinutes.value = null
            return
        }
        _remainingMinutes.value = minutes
        timerJob =
            scope.launch {
                var left = minutes
                while (left > 0) {
                    delay(minuteMillis)
                    left--
                    _remainingMinutes.value = if (left > 0) left else null
                }
                onExpired()
            }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _remainingMinutes.value = null
    }
}

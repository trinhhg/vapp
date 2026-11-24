package dev.trinhhg.vapp.tts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.trinhhg.vapp.tts.api.AudiobookController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * Media3 [MediaSessionService] providing lock-screen and system notification media controls
 * backed by [AudiobookController].
 */
@OptIn(UnstableApi::class)
class AudiobookMediaService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var player: TtsBridgePlayer? = null

    override fun onCreate() {
        super.onCreate()
        val koin = GlobalContext.getOrNull()
        val controller = koin?.getOrNull<AudiobookController>()
        if (controller != null) {
            val bridgePlayer = TtsBridgePlayer(controller, controller as? AudiobookControllerImpl)
            player = bridgePlayer
            mediaSession = MediaSession.Builder(this, bridgePlayer).build()
            serviceScope.launch {
                controller.playbackState.collect {
                    bridgePlayer.notifyStateChanged()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private class TtsBridgePlayer(
        private val controller: AudiobookController,
        private val impl: AudiobookControllerImpl?,
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        fun notifyStateChanged() {
            invalidateState()
        }

        override fun getState(): State {
            val st = controller.playbackState.value
            val coverUri =
                impl?.currentPayload?.value?.coverPath?.let { path ->
                    val f = File(path)
                    if (f.exists()) Uri.fromFile(f) else null
                }
            val mediaMeta =
                MediaMetadata
                    .Builder()
                    .setTitle(st.chapterTitle.ifBlank { "vapp Audiobook" })
                    .setArtist(st.bookTitle.ifBlank { "Đọc trực tiếp" })
                    .setArtworkUri(coverUri)
                    .build()

            val mediaItem =
                MediaItemData
                    .Builder("vapp_tts_${st.bookId ?: "idle"}_${st.chapterIndex}")
                    .setMediaMetadata(mediaMeta)
                    .build()

            val commands =
                Player.Commands
                    .Builder()
                    .addAll(
                        COMMAND_PLAY_PAUSE,
                        COMMAND_PREPARE,
                        COMMAND_STOP,
                        COMMAND_SEEK_BACK,
                        COMMAND_SEEK_FORWARD,
                        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        COMMAND_GET_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_METADATA,
                    ).build()

            return State
                .Builder()
                .setAvailableCommands(commands)
                .setPlayWhenReady(st.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(if (st.bookId == null) STATE_IDLE else STATE_READY)
                .setPlaylist(listOf(mediaItem))
                .setCurrentMediaItemIndex(0)
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) controller.resume() else controller.pause()
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            controller.pause()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            val cur = controller.playbackState.value.currentParagraph
            when (seekCommand) {
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                -> controller.skipChapter(-1)
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                -> controller.skipChapter(1)
                COMMAND_SEEK_BACK -> controller.seekToParagraph((cur - 3).coerceAtLeast(0))
                COMMAND_SEEK_FORWARD -> controller.seekToParagraph(cur + 3)
            }
            return Futures.immediateVoidFuture()
        }
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, AudiobookMediaService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}

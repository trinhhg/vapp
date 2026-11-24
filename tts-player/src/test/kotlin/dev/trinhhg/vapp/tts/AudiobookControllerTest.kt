package dev.trinhhg.vapp.tts

import dev.trinhhg.vapp.tts.api.TtsEngine
import dev.trinhhg.vapp.tts.api.TtsUtteranceResult
import dev.trinhhg.vapp.tts.api.TtsVoiceInfo
import dev.trinhhg.vapp.tts.audio.SleepTimer
import dev.trinhhg.vapp.tts.text.CleanupPreviewRule
import dev.trinhhg.vapp.tts.text.ReaderTextParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookControllerTest {
    @Test
    fun `ReaderTextParser splits paragraphs cleanly and previews cleanup rules`() {
        val html =
            """
            <div>
              <script>bad()</script>
              <p>Hàn Lập bước vào Thất Huyền Môn.</p>
              <p>[Quảng cáo: doc-truyen.xyz]</p>
              <p>Hắn khẽ thở dài.<br/>Trời sắp tối rồi.</p>
            </div>
            """.trimIndent()

        val rules =
            listOf(
                CleanupPreviewRule(pattern = "\\[Quảng cáo:[^\\]]+\\]", replacement = "", isRegex = true),
            )
        val paragraphs = ReaderTextParser.parseHtmlToParagraphs(html, rules)
        assertEquals(
            listOf(
                "Hàn Lập bước vào Thất Huyền Môn.",
                "Hắn khẽ thở dài.",
                "Trời sắp tối rồi.",
            ),
            paragraphs,
        )

        val preview = ReaderTextParser.previewCleanup(html, rules)
        assertFalse(preview.contains("Quảng cáo"))
        assertTrue(preview.contains("Trời sắp tối rồi."))
    }

    @Test
    fun `SleepTimer counts down whole minutes and triggers expiration callback`() =
        runTest {
            val timer = SleepTimer(backgroundScope, minuteMillis = 1_000L)
            var expired = false
            timer.start(3) { expired = true }

            assertEquals(3, timer.remainingMinutes.value)
            advanceTimeBy(1_050L)
            assertEquals(2, timer.remainingMinutes.value)
            assertFalse(expired)

            advanceTimeBy(2_100L)
            assertNull(timer.remainingMinutes.value)
            assertTrue(expired)
        }

    @Test
    fun `AudiobookController advances paragraphs and auto-switches to next chapter`() =
        runTest {
            val spoken = ArrayList<String>()

            val fakeTts =
                object : TtsEngine {
                    override suspend fun listVoices(): List<TtsVoiceInfo> = emptyList()

                    override suspend fun speakParagraph(
                        text: String,
                        voiceId: String?,
                        rate: Float,
                        pitch: Float,
                    ): TtsUtteranceResult {
                        spoken.add(text)
                        return TtsUtteranceResult(completed = true)
                    }

                    override fun stop() = Unit
                }

            val chapters =
                mapOf(
                    0 to
                        AudiobookChapterPayload(
                            bookId = "b1",
                            bookTitle = "Phàm Nhân",
                            chapterIndex = 0,
                            chapterTitle = "Chương 1",
                            totalChapters = 2,
                            paragraphs = listOf("Đoạn 1.1", "Đoạn 1.2"),
                        ),
                    1 to
                        AudiobookChapterPayload(
                            bookId = "b1",
                            bookTitle = "Phàm Nhân",
                            chapterIndex = 1,
                            chapterTitle = "Chương 2",
                            totalChapters = 2,
                            paragraphs = listOf("Đoạn 2.1"),
                        ),
                )

            val progressEvents = ArrayList<Pair<Int, Int>>()
            val ctrlJob = SupervisorJob()
            val ctrlScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + ctrlJob)
            val controller =
                AudiobookControllerImpl(
                    ttsEngine = fakeTts,
                    chapterSource = AudiobookChapterSource { _, idx -> chapters[idx] },
                    sleepTimer = SleepTimer(ctrlScope, minuteMillis = 1_000L),
                    ambienceMixer = null,
                    scope = ctrlScope,
                    onProgressPersist = { _, chIdx, pIdx, _ -> progressEvents.add(chIdx to pIdx) },
                )

            controller.playChapter("b1", 0, 0)
            advanceUntilIdle()
            ctrlJob.cancel()

            assertEquals(listOf("Đoạn 1.1", "Đoạn 1.2", "Đoạn 2.1"), spoken)
            assertEquals(1, controller.playbackState.value.chapterIndex)
            assertFalse(controller.playbackState.value.isPlaying)
            assertTrue(progressEvents.contains(0 to 0))
            assertTrue(progressEvents.contains(1 to 0))
        }

    @Test
    fun `AudiobookController pause stops active TTS and preserves resume position`() =
        runTest {
            val speakEntered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<TtsUtteranceResult>()
            var stopCalled = false

            val blockingTts =
                object : TtsEngine {
                    override suspend fun listVoices(): List<TtsVoiceInfo> = emptyList()

                    override suspend fun speakParagraph(
                        text: String,
                        voiceId: String?,
                        rate: Float,
                        pitch: Float,
                    ): TtsUtteranceResult {
                        speakEntered.complete(Unit)
                        return gate.await()
                    }

                    override fun stop() {
                        stopCalled = true
                        gate.complete(TtsUtteranceResult(completed = false))
                    }
                }

            val payload =
                AudiobookChapterPayload(
                    bookId = "b2",
                    bookTitle = "Đấu Phá",
                    chapterIndex = 2,
                    chapterTitle = "Chương 3",
                    totalChapters = 5,
                    paragraphs = listOf("Câu 0", "Câu 1", "Câu 2"),
                )
            val ctrlJob = SupervisorJob()
            val ctrlScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + ctrlJob)
            val controller =
                AudiobookControllerImpl(
                    ttsEngine = blockingTts,
                    chapterSource = AudiobookChapterSource { _, _ -> payload },
                    sleepTimer = SleepTimer(ctrlScope),
                    ambienceMixer = null,
                    scope = ctrlScope,
                )

            controller.playChapter("b2", 2, 1)
            speakEntered.await()
            assertTrue(controller.playbackState.value.isPlaying)
            assertEquals(1, controller.playbackState.value.currentParagraph)

            controller.pause()
            advanceUntilIdle()
            ctrlJob.cancel()
            assertTrue(stopCalled)
            assertFalse(controller.playbackState.value.isPlaying)
            assertEquals(1, controller.playbackState.value.currentParagraph)
        }
}

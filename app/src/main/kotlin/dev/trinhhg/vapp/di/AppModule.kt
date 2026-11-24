package dev.trinhhg.vapp.di

import dev.trinhhg.vapp.backup.BackupExporter
import dev.trinhhg.vapp.backup.ValidatedBackupImporter
import dev.trinhhg.vapp.downloader.BookLibraryRepository
import dev.trinhhg.vapp.downloader.api.DownloadQueueManager
import dev.trinhhg.vapp.downloader.db.VappDatabase
import dev.trinhhg.vapp.downloader.queue.ChapterDownloadRunner
import dev.trinhhg.vapp.downloader.queue.DownloadQueueManagerImpl
import dev.trinhhg.vapp.downloader.storage.ChapterFileStore
import dev.trinhhg.vapp.downloader.storage.CoverStore
import dev.trinhhg.vapp.downloader.worker.DownloadWorker
import dev.trinhhg.vapp.downloader.worker.VappWorkerFactory
import dev.trinhhg.vapp.engine.api.ExtensionExecutor
import dev.trinhhg.vapp.export.AndroidCoverNormalizer
import dev.trinhhg.vapp.export.SafExportSaver
import dev.trinhhg.vapp.exporter.BookExporterImpl
import dev.trinhhg.vapp.exporter.api.BookExporter
import dev.trinhhg.vapp.exporter.api.CoverNormalizer
import dev.trinhhg.vapp.settings.AppSettingsRepository
import dev.trinhhg.vapp.tts.AudiobookChapterPayload
import dev.trinhhg.vapp.tts.AudiobookChapterSource
import dev.trinhhg.vapp.tts.AudiobookControllerImpl
import dev.trinhhg.vapp.tts.AudiobookMediaService
import dev.trinhhg.vapp.tts.api.AudiobookController
import dev.trinhhg.vapp.tts.api.TtsEngine
import dev.trinhhg.vapp.tts.audio.AmbienceAudioMixer
import dev.trinhhg.vapp.tts.audio.SleepTimer
import dev.trinhhg.vapp.tts.engine.AndroidSystemTtsEngine
import dev.trinhhg.vapp.tts.text.CleanupPreviewRule
import dev.trinhhg.vapp.tts.text.ReaderTextParser
import dev.trinhhg.vapp.ui.reader.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val DATA_ROOT = named("vapp_data_root")

val downloaderModule =
    module {
        single<Path>(DATA_ROOT) {
            androidContext().filesDir.resolve("vapp").toOkioPath()
        }

        single {
            VappDatabase.createPersistent(androidContext())
        }

        single {
            ChapterFileStore(vappRootDir = get(DATA_ROOT))
        }

        single {
            CoverStore(vappRootDir = get(DATA_ROOT))
        }

        single {
            BookLibraryRepository(
                db = get(),
                fileStore = get(),
            )
        }

        single {
            ChapterDownloadRunner(
                db = get(),
                fileStore = get(),
                extensionExecutor = get(),
                log = get(),
            )
        }

        single {
            val settingsRepo: AppSettingsRepository = get()
            DownloadWorker.DownloaderConfigProvider {
                settingsRepo.current().toDownloaderConfig()
            }
        }

        single {
            VappWorkerFactory(
                runner = get(),
                configProvider = get(),
                log = get(),
            )
        }

        single<DownloadQueueManager> {
            DownloadQueueManagerImpl(
                appContext = androidContext(),
                db = get(),
                fileStore = get(),
                log = get(),
            )
        }
    }

val exporterModule =
    module {
        single<CoverNormalizer> {
            AndroidCoverNormalizer()
        }

        single<BookExporter> {
            BookExporterImpl(coverNormalizer = get())
        }

        single {
            SafExportSaver(appContext = androidContext())
        }
    }

val ttsModule =
    module {
        single { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

        single<TtsEngine> {
            AndroidSystemTtsEngine(androidContext())
        }

        single {
            SleepTimer(scope = get())
        }

        single {
            AmbienceAudioMixer(scope = get())
        }

        single {
            val libraryRepo: BookLibraryRepository = get()
            val fileStore: ChapterFileStore = get()
            val coverStore: CoverStore = get()
            val db: VappDatabase = get()
            val executor: ExtensionExecutor = get()
            AudiobookChapterSource { bookId, chapterIndex ->
                val book = libraryRepo.getBook(bookId) ?: return@AudiobookChapterSource null
                val chapters = libraryRepo.getChapters(bookId)
                val chapter = chapters.getOrNull(chapterIndex) ?: return@AudiobookChapterSource null
                val rawHtml =
                    fileStore.readChapterHtml(chapter.contentPath)
                        ?: book.extensionId?.let { extId ->
                            executor.fetchChapterContent(extId, chapter.sourceUrl).getOrNull()?.html
                        }
                        ?: return@AudiobookChapterSource null
                val rules =
                    db.cleanupRuleDao().listEnabled().map {
                        CleanupPreviewRule(it.pattern, it.replacement, it.isRegex, it.enabled)
                    }
                val paragraphs = ReaderTextParser.parseHtmlToParagraphs(rawHtml, rules)
                AudiobookChapterPayload(
                    bookId = book.id,
                    bookTitle = book.title,
                    author = book.author,
                    coverPath = coverStore.resolveCoverPath(book.coverLocalPath)?.toString(),
                    chapterIndex = chapterIndex,
                    chapterTitle = chapter.title,
                    totalChapters = chapters.size,
                    paragraphs = paragraphs,
                )
            }
        }

        single<AudiobookController> {
            val libraryRepo: BookLibraryRepository = get()
            val appCtx = androidContext()
            AudiobookControllerImpl(
                ttsEngine = get(),
                chapterSource = get(),
                sleepTimer = get(),
                ambienceMixer = get(),
                scope = get(),
                onStartMediaService = { AudiobookMediaService.start(appCtx) },
                onProgressPersist = { bId, chIdx, pIdx, total ->
                    val pct = if (total <= 0) 0f else ((chIdx + 1).toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    libraryRepo.updateReadingProgress(bId, chIdx, pIdx, pct)
                },
            )
        }
    }

val appModule =
    module {
        single {
            AppSettingsRepository(androidContext())
        }

        factory {
            ReaderViewModel(
                db = get(),
                libraryRepo = get(),
                fileStore = get(),
                extensionExecutor = get(),
                settingsRepo = get(),
            )
        }
    }

val backupModule =
    module {
        single {
            BackupExporter(
                db = get(),
                settingsRepo = get(),
                cookieStore = get(),
                engineRootDir = get(),
                dataRootDir = get(DATA_ROOT),
            )
        }

        single {
            ValidatedBackupImporter(
                db = get(),
                settingsRepo = get(),
                cookieStore = get(),
                extensionManager = get(),
                engineRootDir = get(),
                dataRootDir = get(DATA_ROOT),
                stagingParentDir = androidContext().cacheDir,
            )
        }
    }

val allAppModules = listOf(engineModule, downloaderModule, exporterModule, ttsModule, backupModule, appModule)

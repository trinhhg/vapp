package dev.trinhhg.vapp.backup

import dev.trinhhg.vapp.downloader.db.BookEntity
import dev.trinhhg.vapp.downloader.db.BookmarkEntity
import dev.trinhhg.vapp.downloader.db.ChapterDownloadStatus
import dev.trinhhg.vapp.downloader.db.ChapterEntity
import dev.trinhhg.vapp.downloader.db.CleanupRuleEntity
import dev.trinhhg.vapp.downloader.db.ExtensionPackageEntity
import dev.trinhhg.vapp.downloader.db.RepositorySourceEntity
import dev.trinhhg.vapp.engine.vbook.ExtensionType
import dev.trinhhg.vapp.settings.AppSettings
import dev.trinhhg.vapp.settings.DefaultExportFormat
import dev.trinhhg.vapp.settings.ReadMode
import dev.trinhhg.vapp.settings.ReaderTextAlign
import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
    val appVersion: String = "0.1.0",
    val createdAtEpochMs: Long,
    val bookCount: Int,
    val chapterCount: Int,
    val extensionCount: Int,
    val includesChapterContent: Boolean,
) {
    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

@Serializable
data class BackupDbSnapshot(
    val repositories: List<RepositoryBackupDto> = emptyList(),
    val extensions: List<ExtensionBackupDto> = emptyList(),
    val books: List<BookBackupDto> = emptyList(),
    val chapters: List<ChapterBackupDto> = emptyList(),
    val bookmarks: List<BookmarkBackupDto> = emptyList(),
    val cleanupRules: List<CleanupRuleBackupDto> = emptyList(),
)

@Serializable
data class RepositoryBackupDto(
    val id: String,
    val url: String,
    val author: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val lastSyncedAt: Long? = null,
)

@Serializable
data class ExtensionBackupDto(
    val id: String,
    val name: String,
    val author: String = "",
    val version: Int = 1,
    val sourceUrl: String = "",
    val urlRegex: String = "",
    val type: String = ExtensionType.NOVEL.name,
    val locale: String = "vi_VN",
    val description: String = "",
    val iconPath: String? = null,
    val scriptsDir: String = "",
    val scriptMapJson: String = "{}",
    val configSpecJson: String = "{}",
    val userConfigJson: String = "{}",
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class BookBackupDto(
    val id: String,
    val sourceUrl: String,
    val extensionId: String? = null,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val coverLocalPath: String? = null,
    val synopsis: String = "",
    val detailInfo: String = "",
    val ongoing: Boolean = false,
    val inLibrary: Boolean = false,
    val totalChapters: Int = 0,
    val downloadedChapters: Int = 0,
    val lastReadChapterIndex: Int = 0,
    val lastReadScrollOffset: Int = 0,
    val lastReadPercent: Float = 0.0f,
    val addedAt: Long = 0L,
    val lastReadAt: Long? = null,
    val updatedAt: Long = 0L,
)

@Serializable
data class ChapterBackupDto(
    val id: String,
    val bookId: String,
    val orderIndex: Int,
    val partName: String? = null,
    val title: String,
    val sourceUrl: String,
    val downloadStatus: String = ChapterDownloadStatus.NOT_DOWNLOADED.name,
    val contentPath: String? = null,
    val charCount: Int = 0,
    val isImageChapter: Boolean = false,
    val errorMessage: String? = null,
    val updatedAt: Long = 0L,
)

@Serializable
data class BookmarkBackupDto(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val scrollOffset: Int,
    val snippet: String = "",
    val note: String? = null,
    val createdAt: Long = 0L,
)

@Serializable
data class CleanupRuleBackupDto(
    val id: String,
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)

@Serializable
data class BackupSettingsSnapshot(
    val fontKey: String = "literata",
    val fontSizeSp: Int = 20,
    val lineHeightMultiplier: Float = 1.6f,
    val paragraphIndentEm: Float = 1.5f,
    val textAlign: String = ReaderTextAlign.JUSTIFY.name,
    val marginHorizontalDp: Int = 18,
    val themePresetId: String = "light",
    val customBgHex: String = "#F6F1E7",
    val customFgHex: String = "#1F1B16",
    val brightness: Float = -1f,
    val readMode: String = ReadMode.VERTICAL_SCROLL.name,
    val autoScrollSpeed: Int = 4,
    val ttsVoiceName: String = "",
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsSleepTimerMinutes: Int = 0,
    val ttsAmbienceEnabled: Boolean = false,
    val ttsAmbienceTrack: String = "rain",
    val ttsAmbienceVolume: Float = 0.25f,
    val maxConcurrency: Int = 3,
    val requestDelayMs: Int = 0,
    val requestTimeoutMs: Int = 30_000,
    val maxRetriesPerChapter: Int = 2,
    val circuitBreakerThreshold: Int = 8,
    val defaultExportFormat: String = DefaultExportFormat.EPUB3.name,
    val includeCover: Boolean = true,
    val includeImages: Boolean = true,
    val autoSplitVolumes: Boolean = false,
    val zipCompressionLevel: Int = 6,
    val uiLanguage: String = "vi",
    val cookies: Map<String, String> = emptyMap(),
)

internal fun RepositorySourceEntity.toDto() = RepositoryBackupDto(id, url, author, description, enabled, lastSyncedAt)

internal fun RepositoryBackupDto.toEntity() = RepositorySourceEntity(id, url, author, description, enabled, lastSyncedAt)

internal fun ExtensionPackageEntity.toDto() =
    ExtensionBackupDto(
        id,
        name,
        author,
        version,
        sourceUrl,
        urlRegex,
        type.name,
        locale,
        description,
        iconPath,
        scriptsDir,
        scriptMapJson,
        configSpecJson,
        userConfigJson,
        enabled,
        installedAt,
        updatedAt,
    )

internal fun ExtensionBackupDto.toEntity() =
    ExtensionPackageEntity(
        id,
        name,
        author,
        version,
        sourceUrl,
        urlRegex,
        runCatching { ExtensionType.valueOf(type) }.getOrDefault(ExtensionType.OTHER),
        locale,
        description,
        iconPath,
        scriptsDir,
        scriptMapJson,
        configSpecJson,
        userConfigJson,
        enabled,
        installedAt,
        updatedAt,
    )

internal fun BookEntity.toDto() =
    BookBackupDto(
        id,
        sourceUrl,
        extensionId,
        title,
        author,
        coverUrl,
        coverLocalPath,
        synopsis,
        detailInfo,
        ongoing,
        inLibrary,
        totalChapters,
        downloadedChapters,
        lastReadChapterIndex,
        lastReadScrollOffset,
        lastReadPercent,
        addedAt,
        lastReadAt,
        updatedAt,
    )

internal fun BookBackupDto.toEntity() =
    BookEntity(
        id,
        sourceUrl,
        extensionId,
        title,
        author,
        coverUrl,
        coverLocalPath,
        synopsis,
        detailInfo,
        ongoing,
        inLibrary,
        totalChapters,
        downloadedChapters,
        lastReadChapterIndex,
        lastReadScrollOffset,
        lastReadPercent,
        addedAt,
        lastReadAt,
        updatedAt,
    )

internal fun ChapterEntity.toDto() =
    ChapterBackupDto(
        id,
        bookId,
        orderIndex,
        partName,
        title,
        sourceUrl,
        downloadStatus.name,
        contentPath,
        charCount,
        isImageChapter,
        errorMessage,
        updatedAt,
    )

internal fun ChapterBackupDto.toEntity() =
    ChapterEntity(
        id,
        bookId,
        orderIndex,
        partName,
        title,
        sourceUrl,
        runCatching { ChapterDownloadStatus.valueOf(downloadStatus) }.getOrDefault(ChapterDownloadStatus.NOT_DOWNLOADED),
        contentPath,
        charCount,
        isImageChapter,
        errorMessage,
        updatedAt,
    )

internal fun BookmarkEntity.toDto() = BookmarkBackupDto(id, bookId, chapterIndex, chapterTitle, scrollOffset, snippet, note, createdAt)

internal fun BookmarkBackupDto.toEntity() = BookmarkEntity(id, bookId, chapterIndex, chapterTitle, scrollOffset, snippet, note, createdAt)

internal fun CleanupRuleEntity.toDto() = CleanupRuleBackupDto(id, pattern, replacement, isRegex, enabled, sortOrder)

internal fun CleanupRuleBackupDto.toEntity() = CleanupRuleEntity(id, pattern, replacement, isRegex, enabled, sortOrder)

internal fun AppSettings.toBackupDto(cookies: Map<String, String>) =
    BackupSettingsSnapshot(
        fontKey,
        fontSizeSp,
        lineHeightMultiplier,
        paragraphIndentEm,
        textAlign.name,
        marginHorizontalDp,
        themePresetId,
        customBgHex,
        customFgHex,
        brightness,
        readMode.name,
        autoScrollSpeed,
        ttsVoiceName,
        ttsSpeechRate,
        ttsPitch,
        ttsSleepTimerMinutes,
        ttsAmbienceEnabled,
        ttsAmbienceTrack,
        ttsAmbienceVolume,
        maxConcurrency,
        requestDelayMs,
        requestTimeoutMs,
        maxRetriesPerChapter,
        circuitBreakerThreshold,
        defaultExportFormat.name,
        includeCover,
        includeImages,
        autoSplitVolumes,
        zipCompressionLevel,
        uiLanguage,
        cookies,
    )

internal fun BackupSettingsSnapshot.toAppSettings(): AppSettings =
    AppSettings(
        fontKey = fontKey,
        fontSizeSp = fontSizeSp,
        lineHeightMultiplier = lineHeightMultiplier,
        paragraphIndentEm = paragraphIndentEm,
        textAlign = runCatching { ReaderTextAlign.valueOf(textAlign) }.getOrDefault(ReaderTextAlign.JUSTIFY),
        marginHorizontalDp = marginHorizontalDp,
        themePresetId = themePresetId,
        customBgHex = customBgHex,
        customFgHex = customFgHex,
        brightness = brightness,
        readMode = runCatching { ReadMode.valueOf(readMode) }.getOrDefault(ReadMode.VERTICAL_SCROLL),
        autoScrollSpeed = autoScrollSpeed,
        ttsVoiceName = ttsVoiceName,
        ttsSpeechRate = ttsSpeechRate,
        ttsPitch = ttsPitch,
        ttsSleepTimerMinutes = ttsSleepTimerMinutes,
        ttsAmbienceEnabled = ttsAmbienceEnabled,
        ttsAmbienceTrack = ttsAmbienceTrack,
        ttsAmbienceVolume = ttsAmbienceVolume,
        maxConcurrency = maxConcurrency,
        requestDelayMs = requestDelayMs,
        requestTimeoutMs = requestTimeoutMs,
        maxRetriesPerChapter = maxRetriesPerChapter,
        circuitBreakerThreshold = circuitBreakerThreshold,
        defaultExportFormat = runCatching { DefaultExportFormat.valueOf(defaultExportFormat) }.getOrDefault(DefaultExportFormat.EPUB3),
        includeCover = includeCover,
        includeImages = includeImages,
        autoSplitVolumes = autoSplitVolumes,
        zipCompressionLevel = zipCompressionLevel,
        uiLanguage = uiLanguage,
    )

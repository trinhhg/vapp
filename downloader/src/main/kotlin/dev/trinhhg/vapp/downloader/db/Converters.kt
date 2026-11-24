package dev.trinhhg.vapp.downloader.db

import androidx.room.TypeConverter
import dev.trinhhg.vapp.engine.vbook.ExtensionType

class Converters {
    @TypeConverter
    fun fromExtensionType(value: ExtensionType): String = value.name

    @TypeConverter
    fun toExtensionType(raw: String): ExtensionType = runCatching { ExtensionType.valueOf(raw) }.getOrDefault(ExtensionType.OTHER)

    @TypeConverter
    fun fromChapterStatus(value: ChapterDownloadStatus): String = value.name

    @TypeConverter
    fun toChapterStatus(raw: String): ChapterDownloadStatus =
        runCatching { ChapterDownloadStatus.valueOf(raw) }.getOrDefault(ChapterDownloadStatus.NOT_DOWNLOADED)

    @TypeConverter
    fun fromTaskStatus(value: DownloadTaskStatus): String = value.name

    @TypeConverter
    fun toTaskStatus(raw: String): DownloadTaskStatus =
        runCatching { DownloadTaskStatus.valueOf(raw) }.getOrDefault(DownloadTaskStatus.PENDING)
}

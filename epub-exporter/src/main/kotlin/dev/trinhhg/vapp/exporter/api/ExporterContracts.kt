package dev.trinhhg.vapp.exporter.api

enum class ExportFormat {
    EPUB_2,
    EPUB_3,
    TXT,
}

data class BookExportMetadata(
    val id: String,
    val title: String,
    val author: String = "",
    val synopsis: String = "",
    val language: String = "vi",
    val coverBytes: ByteArray? = null,
    val coverMimeType: String? = null,
    val sourceUrl: String = "",
)

data class ChapterExportInput(
    val orderIndex: Int,
    val title: String,
    val partName: String? = null,
    val htmlContent: String = "",
    val images: Map<String, ByteArray> = emptyMap(),
    val htmlProvider: (() -> String)? = null,
) {
    fun resolveHtml(): String = htmlProvider?.invoke() ?: htmlContent
}

data class CleanupRuleSpec(
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
    val enabled: Boolean = true,
)

data class ExportRequest(
    val book: BookExportMetadata,
    val chapters: List<ChapterExportInput>,
    val format: ExportFormat,
    val includeCover: Boolean = true,
    val includeImages: Boolean = true,
    val autoSplitVolumes: Boolean = false,
    val maxChaptersPerVolume: Int = 500,
    val chapterRangeSpec: String? = null,
    val compressionLevel: Int = 6,
    val cleanupRules: List<CleanupRuleSpec> = emptyList(),
    val outputFilePath: String,
)

data class ExportOutput(
    val outputFiles: List<String>,
    val totalChaptersExported: Int,
    val totalVolumes: Int,
    val totalBytes: Long,
)

data class NormalizedCover(
    val bytes: ByteArray,
    val extension: String = "jpg",
    val mimeType: String = "image/jpeg",
)

interface CoverNormalizer {
    fun normalizeToJpeg(
        rawBytes: ByteArray,
        mimeType: String? = null,
    ): NormalizedCover?
}

/**
 * Pure-JVM fallback normalizer preserving JPEG/PNG bytes as-is; Android uses `AndroidCoverNormalizer`
 * (`BitmapFactory`) to convert WebP/PNG covers into universal EPUB-safe JPEG.
 */
object PassThroughCoverNormalizer : CoverNormalizer {
    override fun normalizeToJpeg(
        rawBytes: ByteArray,
        mimeType: String?,
    ): NormalizedCover? {
        if (rawBytes.isEmpty()) return null
        return when {
            isJpeg(rawBytes) -> NormalizedCover(rawBytes, "jpg", "image/jpeg")
            isPng(rawBytes) -> NormalizedCover(rawBytes, "png", "image/png")
            else -> NormalizedCover(rawBytes, "jpg", mimeType ?: "image/jpeg")
        }
    }

    private fun isJpeg(b: ByteArray): Boolean = b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    private fun isPng(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
}

interface BookExporter {
    suspend fun export(
        request: ExportRequest,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Result<ExportOutput>
}

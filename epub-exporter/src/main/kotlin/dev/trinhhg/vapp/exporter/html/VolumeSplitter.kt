package dev.trinhhg.vapp.exporter.html

import dev.trinhhg.vapp.exporter.api.ChapterExportInput

data class VolumeChunk(
    val volumeNumber: Int,
    val totalVolumes: Int,
    val volumeTitle: String,
    val chapters: List<ChapterExportInput>,
)

/**
 * Filters exported chapters by optional 1-based range spec (e.g. `"1-100,105"`) and splits
 * large books into balanced volumes when `autoSplitVolumes == true`.
 */
object VolumeSplitter {
    fun filterAndSplit(
        bookTitle: String,
        chapters: List<ChapterExportInput>,
        chapterRangeSpec: String?,
        autoSplitVolumes: Boolean,
        maxChaptersPerVolume: Int,
    ): List<VolumeChunk> {
        val sorted = chapters.sortedBy { it.orderIndex }
        val filtered =
            if (chapterRangeSpec.isNullOrBlank()) {
                sorted
            } else {
                val allowedOneBased = parseOneBasedRange(chapterRangeSpec, sorted.size)
                sorted.filterIndexed { zeroIdx, _ -> (zeroIdx + 1) in allowedOneBased }
            }

        if (filtered.isEmpty()) return emptyList()

        if (!autoSplitVolumes || filtered.size <= maxChaptersPerVolume.coerceAtLeast(1)) {
            return listOf(
                VolumeChunk(
                    volumeNumber = 1,
                    totalVolumes = 1,
                    volumeTitle = bookTitle,
                    chapters = filtered,
                ),
            )
        }

        val chunkSize = maxChaptersPerVolume.coerceAtLeast(1)
        val rawSlices = filtered.chunked(chunkSize)
        return rawSlices.mapIndexed { idx, slice ->
            val volNum = idx + 1
            VolumeChunk(
                volumeNumber = volNum,
                totalVolumes = rawSlices.size,
                volumeTitle = "$bookTitle — Tập $volNum",
                chapters = slice,
            )
        }
    }

    fun parseOneBasedRange(
        spec: String,
        maxChapters: Int,
    ): Set<Int> {
        val result = LinkedHashSet<Int>()
        spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            if (token.contains("-")) {
                val parts = token.split("-", limit = 2)
                val a = parts[0].trim().toIntOrNull()
                val b = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (a != null && b != null) {
                    val lo = minOf(a, b).coerceAtLeast(1)
                    val hi = maxOf(a, b).coerceAtMost(maxChapters)
                    for (i in lo..hi) result.add(i)
                }
            } else {
                val single = token.toIntOrNull()
                if (single != null && single in 1..maxChapters) {
                    result.add(single)
                }
            }
        }
        return result
    }
}

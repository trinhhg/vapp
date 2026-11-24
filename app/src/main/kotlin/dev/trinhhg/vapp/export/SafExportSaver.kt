package dev.trinhhg.vapp.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Publishes exported EPUB/TXT files to Android SAF (`ACTION_CREATE_DOCUMENT`),
 * `MediaStore.Downloads`, or Android share sheet via [FileProvider].
 */
class SafExportSaver(
    private val appContext: Context,
) {
    fun stagingDir(): File {
        val dir = appContext.filesDir.resolve("vapp/exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createDocumentIntent(
        suggestedFileName: String,
        mimeType: String,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedFileName)
        }

    fun copyToDocumentUri(
        sourceFile: File,
        destUri: Uri,
    ) {
        val out =
            appContext.contentResolver.openOutputStream(destUri)
                ?: throw IOException("Cannot open SAF output stream for $destUri")
        out.use { dst ->
            sourceFile.inputStream().use { src ->
                src.copyTo(dst)
            }
        }
    }

    fun saveToDownloads(
        sourceFile: File,
        displayName: String = sourceFile.name,
        mimeType: String = guessMimeType(displayName),
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/vapp")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert failed for $displayName")
            try {
                copyToDocumentUri(sourceFile, uri)
                val readyValues =
                    ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                resolver.update(uri, readyValues, null, null)
                return uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } else {
            val downloadsDir =
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: appContext.filesDir.resolve("exports")
            val vappDir = File(downloadsDir, "vapp").apply { if (!exists()) mkdirs() }
            val dest = File(vappDir, displayName)
            sourceFile.copyTo(dest, overwrite = true)
            return fileProviderUri(dest)
        }
    }

    fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )

    fun createShareIntent(
        file: File,
        chooserTitle: String = "Chia sẻ sách",
        mimeType: String = guessMimeType(file.name),
    ): Intent {
        val uri = fileProviderUri(file)
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val MIME_EPUB = "application/epub+zip"
        const val MIME_TXT = "text/plain"

        fun guessMimeType(fileName: String): String = if (fileName.endsWith(".txt", ignoreCase = true)) MIME_TXT else MIME_EPUB
    }
}

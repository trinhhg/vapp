package dev.trinhhg.vapp.engine.log

import okio.FileSystem
import okio.Path
import java.util.ArrayDeque

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class EngineLogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * Thread-safe ring-buffer + optional rotating file logger for extension and HTTP diagnostics.
 */
class EngineLogger(
    private val logFile: Path? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<EngineLogEntry>(maxEntries)
    var debugEnabled: Boolean = true

    fun add(
        message: String,
        tag: String = "Engine",
        level: LogLevel = LogLevel.INFO,
    ) {
        val entry =
            EngineLogEntry(
                timestampMs = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
            )
        synchronized(lock) {
            if (buffer.size >= maxEntries) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            appendToFileLocked(entry)
        }
    }

    fun debug(
        message: String,
        tag: String = "Engine",
    ) {
        if (debugEnabled) {
            add(message, tag, LogLevel.DEBUG)
        }
    }

    fun warn(
        message: String,
        tag: String = "Engine",
    ) {
        add(message, tag, LogLevel.WARN)
    }

    fun error(
        message: String,
        throwable: Throwable? = null,
        tag: String = "Engine",
    ) {
        val full =
            if (throwable != null) {
                "$message (${throwable::class.simpleName}: ${throwable.message})"
            } else {
                message
            }
        add(full, tag, LogLevel.ERROR)
    }

    fun add(
        throwable: Throwable,
        tag: String = "Engine",
    ) {
        error(throwable.message ?: throwable::class.simpleName ?: "Unknown error", throwable, tag)
    }

    fun snapshot(): List<EngineLogEntry> = synchronized(lock) { buffer.toList() }

    fun formattedText(): String =
        snapshot().joinToString("\n") { entry ->
            "[${entry.level}] [${entry.tag}] ${entry.message}"
        }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            logFile?.let { path -> runCatching { fileSystem.delete(path) } }
        }
    }

    private fun appendToFileLocked(entry: EngineLogEntry) {
        val path = logFile ?: return
        runCatching {
            path.parent?.let { fileSystem.createDirectories(it) }
            val currentSize = fileSystem.metadataOrNull(path)?.size ?: 0L
            if (currentSize > maxFileBytes) {
                val backup = path.parent?.resolve("${path.name}.1")
                if (backup != null) {
                    runCatching { fileSystem.delete(backup) }
                    fileSystem.atomicMove(path, backup)
                } else {
                    fileSystem.delete(path)
                }
            }
            fileSystem.appendingSink(path).use { sink ->
                val line = "${entry.timestampMs} [${entry.level}] [${entry.tag}] ${entry.message}\n"
                val bytes = line.encodeToByteArray()
                val buf = okio.Buffer().write(bytes)
                sink.write(buf, bytes.size.toLong())
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 500
        private const val DEFAULT_MAX_FILE_BYTES = 1L * 1024 * 1024
    }
}

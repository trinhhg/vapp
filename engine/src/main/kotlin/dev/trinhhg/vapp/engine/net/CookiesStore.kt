package dev.trinhhg.vapp.engine.net

import dev.trinhhg.vapp.engine.util.parseHost
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Persistence contract for per-host cookies shared across OkHttp and Android WebView.
 * Lives in `:engine` as a pure JVM interface (no Android/Room dependency).
 */
interface CookiePersistence {
    fun getCookieHeader(url: String): String?

    fun saveSetCookieHeaders(
        url: String,
        setCookieValues: List<String>,
    )

    fun setRawCookie(
        hostOrUrl: String,
        cookieHeader: String,
    )

    fun allCookies(): Map<String, String>

    fun clearHost(hostOrUrl: String)

    fun clearAll()
}

/**
 * Thread-safe, atomic Okio JSON cookie store (`cookies/cookies.json`) for `:engine`.
 */
class OkioJsonCookieStore(
    private val cookieFile: Path? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : CookiePersistence {
    private val lock = Any()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    private val hostCookies = LinkedHashMap<String, LinkedHashMap<String, String>>()

    init {
        loadFromDisk()
    }

    override fun getCookieHeader(url: String): String? {
        val host = resolveHostKey(url) ?: return null
        synchronized(lock) {
            val map = hostCookies[host] ?: return null
            if (map.isEmpty()) return null
            return map.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        }
    }

    override fun saveSetCookieHeaders(
        url: String,
        setCookieValues: List<String>,
    ) {
        if (setCookieValues.isEmpty()) return
        val host = resolveHostKey(url) ?: return
        synchronized(lock) {
            val map = hostCookies.getOrPut(host) { LinkedHashMap() }
            for (header in setCookieValues) {
                val pair = header.substringBefore(';').trim()
                val eqIdx = pair.indexOf('=')
                if (eqIdx <= 0) continue
                val name = pair.substring(0, eqIdx).trim()
                val value = pair.substring(eqIdx + 1).trim()
                if (name.isNotEmpty()) {
                    if (value.isEmpty() && header.contains("Max-Age=0", ignoreCase = true)) {
                        map.remove(name)
                    } else {
                        map[name] = value
                    }
                }
            }
            persistLocked()
        }
    }

    override fun setRawCookie(
        hostOrUrl: String,
        cookieHeader: String,
    ) {
        val host = resolveHostKey(hostOrUrl) ?: return
        synchronized(lock) {
            val map = hostCookies.getOrPut(host) { LinkedHashMap() }
            for (part in cookieHeader.split(";")) {
                val trimmed = part.trim()
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx <= 0) continue
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isNotEmpty()) {
                    map[name] = value
                }
            }
            persistLocked()
        }
    }

    override fun allCookies(): Map<String, String> =
        synchronized(lock) {
            hostCookies.mapValues { (_, pairs) ->
                pairs.entries.joinToString("; ") { (k, v) -> "$k=$v" }
            }
        }

    override fun clearHost(hostOrUrl: String) {
        val host = resolveHostKey(hostOrUrl) ?: return
        synchronized(lock) {
            hostCookies.remove(host)
            persistLocked()
        }
    }

    override fun clearAll() {
        synchronized(lock) {
            hostCookies.clear()
            persistLocked()
        }
    }

    private fun resolveHostKey(hostOrUrl: String): String? {
        val trimmed = hostOrUrl.trim().lowercase()
        if (trimmed.isEmpty()) return null
        val host = if (trimmed.contains("://")) parseHost(trimmed) else trimmed.substringBefore('/')
        return host?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
    }

    private fun loadFromDisk() {
        val path = cookieFile ?: return
        runCatching {
            if (!fileSystem.exists(path)) return
            val raw = fileSystem.read(path) { readUtf8() }
            val parsed = json.decodeFromString<Map<String, Map<String, String>>>(raw)
            synchronized(lock) {
                hostCookies.clear()
                parsed.forEach { (host, map) ->
                    hostCookies[host] = LinkedHashMap(map)
                }
            }
        }
    }

    private fun persistLocked() {
        val path = cookieFile ?: return
        runCatching {
            path.parent?.let { fileSystem.createDirectories(it) }
            val tmp = path.parent?.resolve("${path.name}.tmp") ?: path
            val payload = json.encodeToString(hostCookies as Map<String, Map<String, String>>)
            fileSystem.write(tmp) { writeUtf8(payload) }
            if (tmp != path) {
                fileSystem.atomicMove(tmp, path)
            }
        }
    }
}

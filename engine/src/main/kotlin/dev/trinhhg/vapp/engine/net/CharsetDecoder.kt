package dev.trinhhg.vapp.engine.net

import java.nio.charset.Charset

/**
 * Decodes HTTP response bytes using VBook's precedence:
 * 1. Explicit charset parameter passed by the extension script
 * 2. HTTP `Content-Type` header charset
 * 3. HTML `<meta charset="...">` / `<meta http-equiv="Content-Type">` in first 4 KB
 * 4. UTF-8 fallback (stripping BOM)
 */
object CharsetDecoder {
    private val CONTENT_TYPE_CHARSET = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
    private val META_CHARSET = Regex("<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)

    fun decode(
        bytes: ByteArray,
        explicitCharset: String? = null,
        contentTypeHeader: String? = null,
    ): String {
        if (bytes.isEmpty()) return ""
        val resolved = resolveCharset(bytes, explicitCharset, contentTypeHeader)
        var text = String(bytes, resolved)
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1)
        }
        return text
    }

    fun resolveCharset(
        bytes: ByteArray,
        explicitCharset: String? = null,
        contentTypeHeader: String? = null,
    ): Charset {
        tryCharset(explicitCharset)?.let { return it }
        if (!contentTypeHeader.isNullOrBlank()) {
            CONTENT_TYPE_CHARSET.find(contentTypeHeader)?.groupValues?.getOrNull(1)?.let { name ->
                tryCharset(name)?.let { return it }
            }
        }
        val sniffLen = minOf(bytes.size, 4096)
        val asciiHead = String(bytes, 0, sniffLen, Charsets.ISO_8859_1)
        META_CHARSET.find(asciiHead)?.groupValues?.getOrNull(1)?.let { name ->
            tryCharset(name)?.let { return it }
        }
        return Charsets.UTF_8
    }

    private fun tryCharset(name: String?): Charset? {
        val raw = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val mapped =
            when (raw) {
                "gb2312", "gb_2312", "gbk" -> "GBK"
                "gb18030" -> "GB18030"
                "big5", "big-5" -> "Big5"
                "utf8", "utf-8" -> "UTF-8"
                else -> raw
            }
        return runCatching {
            if (Charset.isSupported(mapped)) Charset.forName(mapped) else null
        }.getOrNull()
    }
}

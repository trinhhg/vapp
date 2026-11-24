package dev.trinhhg.vapp.engine.util

import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer

fun String?.normalizeUrl(): String = this?.trim()?.trimEnd('/') ?: ""

fun String?.isValidHttpUrl(): Boolean =
    this != null && (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true))

fun parseHost(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()
}

fun parseBaseUrl(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return runCatching {
        val uri = URI(url.trim())
        val scheme = uri.scheme ?: return@runCatching ""
        val host = uri.host ?: return@runCatching ""
        val port = if (uri.port != -1) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrDefault("")
}

fun resolveUrl(
    baseUrl: String?,
    maybeRelative: String?,
): String {
    val raw = maybeRelative?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    if (raw.startsWith("//")) {
        val scheme = baseUrl?.substringBefore("://")?.takeIf { it == "http" || it == "https" } ?: "https"
        return "$scheme:$raw"
    }
    val base = parseBaseUrl(baseUrl)
    if (base.isEmpty()) return raw
    return runCatching { URI(base).resolve(raw).toString() }.getOrDefault("$base/${raw.trimStart('/')}")
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun String.sha256Hex(): String = sha256Hex(encodeToByteArray())

fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

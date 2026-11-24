package dev.trinhhg.vapp.engine.security

import dev.trinhhg.vapp.engine.util.parseHost

object NetworkSecurityValidator {
    private const val MAX_DOWNLOAD_SIZE_BYTES = 50L * 1024 * 1024
    private val ALLOWED_CONTENT_TYPES =
        arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
        )

    fun validateUrl(urlString: String?) {
        if (urlString.isNullOrBlank()) {
            throw SecurityValidationException("URL cannot be empty")
        }
        if (!urlString.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*://.+"))) {
            throw SecurityValidationException("Invalid URL: $urlString")
        }
        val protocol = urlString.substringBefore("://").lowercase()
        val host = parseHost(urlString)
        if (protocol != "https" && !isLocalhost(protocol, host)) {
            throw SecurityValidationException(
                "Only HTTPS URLs are allowed for security (found: $protocol): $urlString",
            )
        }
    }

    private fun isLocalhost(
        protocol: String,
        host: String?,
    ): Boolean {
        if (protocol == "file") return true
        if (host == null) return false
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host.startsWith("127.")
    }

    fun validateResponse(
        contentType: String?,
        contentLength: Long,
    ) {
        if (!contentType.isNullOrEmpty()) {
            val lower = contentType.lowercase()
            if (ALLOWED_CONTENT_TYPES.none { lower.contains(it) }) {
                throw SecurityValidationException("Invalid content type for plugin download: $contentType")
            }
        }
        if (contentLength > MAX_DOWNLOAD_SIZE_BYTES) {
            throw SecurityValidationException(
                "Download too large: ${contentLength / (1024 * 1024)} MB (max: 50 MB)",
            )
        }
    }
}

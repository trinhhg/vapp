package dev.trinhhg.vapp.engine

/** Typed error categories surfaced by `:engine` instead of silent/mock fallbacks. */
enum class EngineErrorCode {
    ENCRYPTED_UNSUPPORTED,
    NETWORK_ERROR,
    HTTP_ERROR,
    CHALLENGE_REQUIRED,
    SCRIPT_ERROR,
    VALIDATION_ERROR,
    EXTENSION_NOT_FOUND,
}

/** Explicit engine exception carrying a typed [code] for UI and diagnostics. */
class EngineException(
    val code: EngineErrorCode,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)

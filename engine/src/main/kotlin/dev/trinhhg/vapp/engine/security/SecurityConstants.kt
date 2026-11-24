package dev.trinhhg.vapp.engine.security

class SecurityValidationException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)

object SecurityConstants {
    val BLOCKED_CLASS_PREFIXES =
        arrayOf(
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.ClassLoader",
            "java.lang.Thread",
            "java.io.File",
            "java.lang.System",
            "java.lang.reflect",
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.nio.file",
        )

    val ALLOWED_CLASS_PREFIXES =
        arrayOf(
            "java.lang.String",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Double",
            "java.lang.Boolean",
            "java.lang.Object",
            "java.util",
            "java.math",
        )

    fun isClassAllowed(className: String): Boolean {
        for (blocked in BLOCKED_CLASS_PREFIXES) {
            if (className.startsWith(blocked)) return false
        }
        for (allowed in ALLOWED_CLASS_PREFIXES) {
            if (className.startsWith(allowed)) return true
        }
        return false
    }
}
